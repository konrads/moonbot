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

  val conf = ConfigFactory.load(ConfigFactory.parseFile(new File("application.private.conf")))
    .withFallback(ConfigFactory.parseResources("application.private.conf"))
    .withFallback(ConfigFactory.parseResources(cliConf.config()))
    .resolve()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")

  // for deployment purposes, allow env var to overwrite "graphite_host" config param
  val graphiteHost      = sys.env.get("graphite_host").orElse(conf.optString("graphite.host"))
  val graphitePort      = conf.optInt("graphite.port")

  val runType                = conf.optString("bot.runType").map(_.toLowerCase).map(x => RunType.withName(capFirst(x))).getOrElse(Live)
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val backtestEventDataDir   = conf.optString("bot.backtestEventDataDir")
  val backtestCsvDir         = conf.optString("bot.backtestCsvDir")
  val backtestCandleFile     = conf.optString("bot.backtestCandleFile")
  val useSynthetics          = conf.optBoolean("bot.useSynthetics").getOrElse(false)
  val takerFee               = conf.optDouble("bot.takerFee").getOrElse(.00075)

  // bots specific
  val bots = conf.getObject("bot.bots").keySet().asScala.map {
    botName =>
      val c = conf.getConfig(s"bot.bots.$botName")
      BotSpec(
        namespace            = c.optString("namespace").getOrElse(s"moon.$botName"),
        symbol               = botName.toUpperCase,
        wssSubscriptions     = c.getString("wssSubscriptions").split(",").map(_.trim),
        dir                  = c.optString("dir").map(Dir.withName).getOrElse(LongDir),
        takeProfitPerc       = c.optDouble("takeProfitPerc").getOrElse(0.005),
        drainPriceDeltaPct   = c.optDouble("drainPriceDeltaPct").getOrElse(0.005),
        drainMinPosition     = c.optDouble("drainMinPosition").getOrElse(5.0),
        tiers                = c.getList("tiers").asScala.toSeq.map(_.unwrapped.asInstanceOf[util.ArrayList[Number]]).map(l => (l.get(0).doubleValue, l.get(1).doubleValue)),
        strategyName         = c.getString("strategy.selection"),
        strategyConf         = c.getObject(s"strategy.${c.getString("strategy.selection")}").toConfig)
  }

  assert(runType != RunType.Backtest || backtestEventDataDir.isDefined || backtestCsvDir.isDefined || backtestCandleFile.isDefined)

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
      |
      |• graphiteHost:         $graphiteHost
      |• graphitePort:         $graphitePort
      |• takerFee:             $takerFee
      |• restSyncTimeoutMs:    $restSyncTimeoutMs
      |• useSynthetics:        $useSynthetics
      |• backtestEventDataDir: $backtestEventDataDir
      |• backtestCandleFile:   $backtestCandleFile
      |• backtestCsvDir:       $backtestCsvDir
      |
      |bots:
      |${bots.mkString("\n")}
      |""".stripMargin)

  val metrics = for { h <- graphiteHost; p <- graphitePort } yield Metrics(h, p)
  if (runType == Backtest) {
    bots foreach { bot =>
      log.info(s"Instantiating ${bot.namespace} $runType on $backtestEventDataDir or $backtestCandleFile...")
      val strategy = Strategy(name = bot.strategyName, config = conf.getObject(s"strategy.${bot.strategyName}").toConfig)
      val tierCalc = TierCalcImpl(dir = bot.dir, tiers = bot.tiers)
      val sim = new ExchangeSim(
        eventDataDir = backtestEventDataDir.orNull,
        eventCsvDir = backtestCsvDir.orNull,
        candleFile = backtestCandleFile.orNull,
        strategy = strategy,
        tierCalc = tierCalc,
        dir = bot.dir,
        takeProfitPerc = bot.takeProfitPerc,
        metrics = metrics,
        symbol = bot.symbol,
        namespace = bot.namespace,
        useSynthetics = useSynthetics)
      val (finalCtx, finalExchangeCtx) = sim.run()
      log.info(s"Final Ctx running PandL: ${finalCtx.ledger.ledgerMetrics.runningPandl} ($$${finalCtx.ledger.ledgerMetrics.runningPandl * (finalCtx.ledger.askPrice + finalCtx.ledger.bidPrice)/2}) over ${finalCtx.ledger.myTrades.size} trades")
    }
  } else if (runType == Dry) {
    ??? // FIXME: No implementation for dry run, had same one as live before, need to remove "trade" flag...
  } else if (runType == Live) {
    implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
    val wssSubscriptions = bots.flatMap(_.wssSubscriptions).toSeq
    val wsGateway = new WsGateway(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, wssSubscriptions=wssSubscriptions)
    val restGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs)
    val actorBySymbol = (bots map { bot =>
      val strategy = Strategy(
        name = bot.strategyName,
        config = bot.strategyConf)
      val tierCalc = TierCalcImpl(dir = bot.dir, tiers = bot.tiers)

      val behaviorDsl = Orchestrator.asDsl(
        strategy = strategy,
        tierCalc = tierCalc,
        takeProfitPerc = bot.takeProfitPerc,
        dir = bot.dir)

      log.info(s"Instantiating Live Run for ${bot.namespace}...")
      val orchestrator = Behaviour.asLiveBehavior(
        restGateway = restGateway,
        metrics=metrics,
        namespace=bot.namespace,
        symbol=bot.symbol,
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()),
        bootstrap=restGateway.drainSync(symbol=bot.symbol, dir=bot.dir, priceDeltaPct=bot.drainPriceDeltaPct, minPosition=bot.drainMinPosition))
      val actor = ActorSystem(
        Behaviors.supervise(orchestrator).onFailure[Throwable](SupervisorStrategy.restartWithBackoff(minBackoff=2.seconds, maxBackoff=30.seconds, randomFactor=0.1)),
        s"${bot.symbol}-actor")
      actor.scheduler.scheduleAtFixedRate(tillEO30s, 30.seconds)(() => actor ! On30s(None))
      actor.scheduler.scheduleAtFixedRate(tillEOM,    1.minute) (() => actor ! On1m(None))
      actor.scheduler.scheduleAtFixedRate(tillEO5m,   5.minute) (() => actor ! On5m(None))
      (bot.symbol, actor)
    }).toMap
    // feed the WS events from actual exchange
    class CachedConsumer {
      var cache: Map[String, OrderBookSummary] = Map.empty
      val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
        case JsSuccess(value, _path) =>
          WsModel.bySymbol(value) foreach {
            case (symbol, value: OrderBook) if actorBySymbol.keySet.contains(symbol) =>
              val summary = value.summary
              cache.get(symbol) match {
                case Some(cached) if !summary.isEquivalent(cached) =>
                  cache += symbol -> summary
                  actorBySymbol(symbol) ! WsEvent(summary)
                case Some(cached) =>
                  ()
                case None =>
                  cache += symbol -> summary
                  actorBySymbol(symbol) ! WsEvent(summary)
              }
            case (symbol, value) if actorBySymbol.keySet.contains(symbol) =>
              actorBySymbol(symbol) ! WsEvent(value)
            case ("other", value) =>
              log.info(s"Received actor unrelated WsEvent: $value")
            case (symbol, value) =>
              throw new Exception(s"Received unexpected WsEvent for symbol $symbol: $value")
          }
        case e: JsError =>
          log.error("WS consume error!", e)
      }
    }
    wsGateway.run(new CachedConsumer().wsMessageConsumer)
  }
}

case class BotSpec(namespace: String, symbol: String, takeProfitPerc: Double, wssSubscriptions: Seq[String], dir: Dir.Value, drainPriceDeltaPct: Double, drainMinPosition: Double, tiers: Seq[(Double, Double)], strategyName: String, strategyConf: Config)
