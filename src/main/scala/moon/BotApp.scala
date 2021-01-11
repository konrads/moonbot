package moon

import java.io.File
import java.util

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, SupervisorStrategy}
import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import moon.Dir._
import moon.RunType._
import org.rogach.scallop.ScallopConf
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.collection.JavaConverters._


object BotApp extends App {
  val log = Logger("BotApp")

  class CliConf extends ScallopConf(args) {
    val config = opt[String](default = Some("application.conf"))
    verify()
  }
  val cliConf = new CliConf()

  val conf = ConfigFactory.load(cliConf.config())
    .withFallback(ConfigFactory.parseFile(new File("application.private.conf")))
    .withFallback(ConfigFactory.parseResources("application.private.conf"))
    .resolve()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")

  // for deployment purposes, allow env var to overwrite "graphite_host" config param
  val graphiteHost      = sys.env.get("graphite_host").orElse(conf.optString("graphite.host"))
  val graphitePort      = conf.optInt("graphite.port")

  val wssSubscriptions       = conf.getString("bot.wssSubscriptions").split(",").map(_.trim)
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val backtestEventDataDir   = conf.optString("bot.backtestEventDataDir")
  val backtestCsvDir         = conf.optString("bot.backtestCsvDir")
  val backtestCandleFile     = conf.optString("bot.backtestCandleFile")
  val useSynthetics          = conf.optBoolean("bot.useSynthetics").getOrElse(false)
  val takerFee               = conf.optDouble("bot.takerFee").getOrElse(.00075)

  // pair specific
  val namespace              = conf.getString("bot.namespace")
  val symbol                 = conf.getString("bot.symbol")
  val takeProfitPerc         = conf.optDouble("bot.takeProfitPerc").getOrElse(0.005)
  val drainPriceDeltaPct     = conf.optDouble("bot.drainPriceDeltaPct").getOrElse(0.005)
  val drainMinPosition       = conf.optDouble("drainMinPosition").getOrElse(5.0)
  val dir                    = conf.optString("bot.dir").map(Dir.withName).getOrElse(LongDir)
  val tiers                  = conf.getList("bot.tiers").asScala.toSeq.map(_.unwrapped.asInstanceOf[util.ArrayList[Number]]).map(l => (l.get(0).doubleValue, l.get(1).doubleValue))

  val runType                = conf.optString("bot.runType").map(_.toLowerCase).map(x => RunType.withName(capFirst(x))).getOrElse(Live)
  assert(runType != RunType.Backtest || backtestEventDataDir.isDefined || backtestCsvDir.isDefined || backtestCandleFile.isDefined)

  val strategyName = conf.getString("strategy.selection")
  log.info(
    s"""
      |
      | ███▄ ▄███▓ ▒█████   ▒█████   ███▄    █     ▄▄▄▄    ▒█████  ▄▄▄█████▓
      |▓██▒▀█▀ ██▒▒██▒  ██▒▒██▒  ██▒ ██ ▀█   █    ▓█████▄ ▒██▒  ██▒▓  ██▒ ▓▒
      |▓██    ▓██░▒██░  ██▒▒██░  ██▒▓██  ▀█ ██▒   ▒██▒ ▄██▒██░  ██▒▒ ▓██░ ▒░
      |▒██    ▒██ ▒██   ██░▒██   ██░▓██▒  ▐▌██▒   ▒██░█▀  ▒██   ██░░ ▓██▓ ░
      |▒██▒   ░██▒░ ████▓▒░░ ████▓▒░▒██░   ▓██░   ░▓█  ▀█▓░ ████▓▒░  ▒██▒ ░
      |░ ▒░   ░  ░░ ▒░▒░▒░ ░ ▒░▒░▒░ ░ ▒░   ▒ ▒    ░▒▓███▀▒░ ▒░▒░▒░   ▒ ░░
      |░  ░      ░  ░ ▒ ▒░   ░ ▒ ▒░ ░ ░░   ░ ▒░   ▒░▒   ░   ░ ▒ ▒░     ░
      |░      ░   ░ ░ ░ ▒  ░ ░ ░ ▒     ░   ░ ░     ░    ░ ░ ░ ░ ▒    ░
      |       ░       ░ ░      ░ ░           ░     ░          ░ ░
      |                                                 ░
      |Initialized with params...
      |• runType:              $runType
      |• bitmexUrl:            $bitmexUrl
      |• bitmexWsUrl:          $bitmexWsUrl
      |• wssSubscriptions:     ${wssSubscriptions.mkString(",")}
      |• graphiteHost:         $graphiteHost
      |• graphitePort:         $graphitePort
      |• takerFee:             $takerFee
      |• restSyncTimeoutMs:    $restSyncTimeoutMs
      |• useSynthetics:        $useSynthetics
      |• backtestEventDataDir: $backtestEventDataDir
      |
      |$namespace:
      |• symbol:               $symbol
      |• dir:                  $dir
      |• backtestCsvDir:       $backtestCsvDir
      |• backtestCandleFile:   $backtestCandleFile
      |• takeProfitPerc:       $takeProfitPerc
      |• tiers:                ${tiers.mkString(",")}
      |""".stripMargin)

  val metrics = for { h <- graphiteHost; p <- graphitePort } yield Metrics(h, p)
  val strategy = Strategy(name = strategyName, config = conf.getObject(s"strategy.$strategyName").toConfig, parentConfig = conf.getObject(s"strategy").toConfig)
  val tierCalc = TierCalcImpl(dir = dir, tiers = tiers)

  if (runType == Backtest) {
    log.info(s"Instantiating $runType on $backtestEventDataDir or $backtestCandleFile...")
    val sim = new ExchangeSim(
      eventDataDir = backtestEventDataDir.orNull,
      eventCsvDir = backtestCsvDir.orNull,
      candleFile = backtestCandleFile.orNull,
      strategy = strategy,
      tierCalc = tierCalc,
      dir = dir,
      takeProfitPerc = takeProfitPerc,
      metrics = metrics,
      namespace = namespace,
      useSynthetics = useSynthetics)
    val (finalCtx, finalExchangeCtx) = sim.run()
    log.info(s"Final Ctx running PandL: ${finalCtx.ledger.ledgerMetrics.runningPandl} ($$${finalCtx.ledger.ledgerMetrics.runningPandl * (finalCtx.ledger.askPrice + finalCtx.ledger.bidPrice)/2}) over ${finalCtx.ledger.myTrades.size} trades")
  } else {
    implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
    val wsGateway = new WsGateway(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, wssSubscriptions=wssSubscriptions)
    val behaviorDsl=Orchestrator.asDsl(
      strategy=strategy,
      tierCalc = tierCalc,
      takeProfitPerc=takeProfitPerc,
      dir=dir)

    val orchestrator = if (runType == Live) {
      log.info(s"Instantiating Live Run...")
      val restGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs)
      Behaviour.asLiveBehavior(
        restGateway = restGateway,
        metrics=metrics,
        namespace=namespace,
        symbol=symbol,
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()),
        bootstrap=restGateway.drainSync(symbol=symbol, dir=dir, priceDeltaPct=drainPriceDeltaPct, minPosition=drainMinPosition))
    } else if (runType == Dry) {
      log.info(s"Instantiating Dry Run...")
      Behaviour.asLiveBehavior(
        restGateway=new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs),
        metrics=metrics,
        namespace=namespace,
        symbol=symbol,
        behaviorDsl=behaviorDsl,
        initCtx=IdleCtx(Ledger()))
    } else if (runType == Backtest) {
      log.info(s"Instantiating Backtest Run...")
      Behaviour.asDryBehavior(
        metrics=metrics,
        namespace=namespace,
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()),
        askBidFromTrades=false)
    } else
      ???

    val orchestratorActor = ActorSystem(
      Behaviors.supervise(orchestrator).onFailure[Throwable](SupervisorStrategy.restartWithBackoff(minBackoff=2.seconds, maxBackoff=30.seconds, randomFactor=0.1)),
      "orchestrator-actor")
    orchestratorActor.scheduler.scheduleAtFixedRate(tillEO30s, 30.seconds)(() => orchestratorActor ! On30s(None))
    orchestratorActor.scheduler.scheduleAtFixedRate(tillEOM,    1.minute) (() => orchestratorActor ! On1m(None))
    orchestratorActor.scheduler.scheduleAtFixedRate(tillEO5m,   5.minute) (() => orchestratorActor ! On5m(None))
    // feed the WS events from actual exchange
    class CachedConsumer {
      var cache: OrderBookSummary = null
      val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
        case JsSuccess(value:OrderBook, _) =>
          val summary = value.summary
          if (! summary.isEquivalent(cache)) {
            cache = summary
            orchestratorActor ! WsEvent(summary)
          }
        case JsSuccess(value, _) => orchestratorActor ! WsEvent(value)
        case e:JsError           => log.error("WS consume error!", e)
      }
    }
    wsGateway.run(new CachedConsumer().wsMessageConsumer)
  }
}
