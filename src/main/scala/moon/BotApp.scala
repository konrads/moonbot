package moon

import java.io.File

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, SupervisorStrategy}
import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import moon.RunType._
import org.rogach.scallop.ScallopConf
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


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

  val graphiteHost      = conf.optString("graphite.host")
  val graphitePort      = conf.optInt("graphite.port")

  val namespace              = conf.getString("bot.namespace")
  val wssSubscriptions       = conf.getString("bot.wssSubscriptions").split(",").map(_.trim)
  val flushSessionOnRestart  = conf.getBoolean("bot.flushSessionOnRestart")
  val tradeQty               = conf.getInt("bot.tradeQty")
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val takeProfitMargin       = conf.optDouble("bot.takeProfitMargin").getOrElse(20.0)
  val stoplossMargin         = conf.optDouble("bot.stoplossMargin").getOrElse(10.0)
  val openWithMarket         = conf.optBoolean("bot.openWithMarket").getOrElse(false)
  val useTrailingStoploss    = conf.optBoolean("bot.useTrailingStoploss").getOrElse(false)
  val backtestEventDataDir   = conf.optString("bot.backtestEventDataDir")
  val backtestCsvDir         = conf.optString("bot.backtestCsvDir")
  val backtestCandleFile     = conf.optString("bot.backtestCandleFile")
  val useSynthetics          = conf.optBoolean("bot.useSynthetics").getOrElse(false)
  val takerFee               = conf.optDouble("bot.takerFee").getOrElse(.00075)

  val runType                = conf.optString("bot.runType").map(_.toLowerCase) match {
    case Some("live-moon")      => LiveMoon
    case Some("live-yabol")     => LiveYabol
    case Some("dry-moon")       => DryMoon
    case Some("dry-yabol")      => DryYabol
    case Some("backtest-moon")  => BacktestMoon
    case Some("backtest-yabol") => BacktestYabol
    case Some(other)            => throw new Exception(s"Invalid bot.runType: $other")
    case None                   => LiveMoon
  }
  assert(runType != RunType.BacktestMoon || backtestEventDataDir.isDefined || backtestCsvDir.isDefined || backtestCandleFile.isDefined)

  val strategyName = conf.getString("strategy.selection")

  val notLiveWarning = runType match {
    case LiveMoon =>
      """|
         |                    -=-=- MOON -=-=-
         |
         |""".stripMargin
    case LiveYabol =>
      """|
         |                    -=-=- YABOL -=-=-
         |
         |""".stripMargin
    case DryMoon =>
      s"""
         |                            ██
         |                          ██  ██
         |                        ██      ██
         |                       ██  DRY   ██
         |                      ██          ██
         |                     ██    RUN!    ██
         |                    ██              ██
         |                     ████████████████
         |
         |""".stripMargin
    case DryYabol =>
      s"""
         |                            ██
         |                          ██  ██
         |                        ██      ██
         |                       ██  YABOL ██
         |                      ██   DRY    ██
         |                     ██    RUN!    ██
         |                    ██              ██
         |                     ████████████████
         |
         |""".stripMargin
    case BacktestMoon =>
      s"""
         |                            ██
         |                          ██  ██
         |                        ██      ██
         |                       ██  BACK  ██
         |                      ██   TEST   ██
         |                     ██    RUN!    ██
         |                    ██              ██
         |                     ████████████████
         |
         |""".stripMargin
    case BacktestYabol =>
      s"""
         |                            ██
         |                          ██  ██
         |                        ██      ██
         |                       ██  YABOL ██
         |                      ██   BACK   ██
         |                     ██    TEST    ██
         |                    ██     RUN!     ██
         |                     ████████████████
         |
         |""".stripMargin
  }
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
      |$notLiveWarning
      |Initialized with params...
      |• bitmexUrl:            $bitmexUrl
      |• bitmexWsUrl:          $bitmexWsUrl
      |• wssSubscriptions:     ${wssSubscriptions.mkString(",")}
      |• graphiteHost:         $graphiteHost
      |• graphitePort:         $graphitePort
      |• namespace:            $namespace
      |• tradeQty:             $tradeQty
      |• takerFee:             $takerFee
      |• restSyncTimeoutMs:    $restSyncTimeoutMs
      |• takeProfitMargin:     $takeProfitMargin
      |• stoplossMargin:       $stoplossMargin
      |• openWithMarket:       $openWithMarket
      |• runType:              $runType
      |• backtestEventDataDir: $backtestEventDataDir
      |• backtestCsvDir:       $backtestCsvDir
      |• backtestCandleFile:   $backtestCandleFile
      |• useSynthetics:        $useSynthetics
      |""".stripMargin)

  val metrics = for { h <- graphiteHost; p <- graphitePort } yield Metrics(h, p, namespace)
  val strategy = Strategy(name = strategyName, config = conf.getObject(s"strategy.$strategyName").toConfig, parentConfig = conf.getObject(s"strategy").toConfig)

  if (runType == BacktestMoon || runType == BacktestYabol) {
    log.info(s"Instantiating $runType on $backtestEventDataDir or $backtestCandleFile...")
    val sim = new ExchangeSim(
      runType = runType,
      eventDataDir = backtestEventDataDir.orNull,
      eventCsvDir = backtestCsvDir.orNull,
      candleFile = backtestCandleFile.orNull,
      strategy = strategy,
      tradeQty = tradeQty,
      takerFee = takerFee,
      takeProfitMargin = takeProfitMargin, stoplossMargin = stoplossMargin,
      metrics = metrics,
      openWithMarket = openWithMarket,
      useSynthetics = useSynthetics)
    val (finalCtx, finalExchangeCtx) = sim.run()
    log.info(s"Final Ctx running PandL: ${finalCtx.ledger.ledgerMetrics.runningPandl} ($$${finalCtx.ledger.ledgerMetrics.runningPandl * finalCtx.ledger.tradeRollups.latestPrice}) over ${finalCtx.ledger.myTrades.size} trades")
  } else {
    implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
    val wsGateway = new WsGateway(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, wssSubscriptions=wssSubscriptions)
    val behaviorDsl=MoonOrchestrator.asDsl(
      strategy=strategy,
      tradeQty=tradeQty,
      takeProfitMargin=takeProfitMargin,
      stoplossMargin=stoplossMargin,
      openWithMarket=openWithMarket,
      useTrailingStoploss=useTrailingStoploss)
    val yabolBehaviorDsl=YabolOrchestrator.asDsl(
      strategy=strategy,
      tradeQty=tradeQty)

    val orchestrator = if (runType == LiveMoon) {
      log.info(s"Instantiating Live Run...")
      Behaviour.asLiveBehavior(
        restGateway=new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs),
        metrics=metrics,
        flushSessionOnRestart=flushSessionOnRestart,
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()))
    } else if (runType == LiveYabol) {
      log.info(s"Instantiating Live Yabol Run...")
      Behaviour.asLiveBehavior(
        restGateway=new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs),
        metrics=metrics,
        flushSessionOnRestart=flushSessionOnRestart,
        behaviorDsl=yabolBehaviorDsl,
        initCtx=YabolIdleCtx(Ledger()))
    } else if (runType == DryMoon) {
      log.info(s"Instantiating Dry Run...")
      Behaviour.asDryBehavior(
        metrics=metrics,
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()),
        askBidFromTrades=false)
    } else if (runType == DryYabol) {
      log.info(s"Instantiating Dry Yabol Run...")
      Behaviour.asDryBehavior(
        metrics=metrics,
        behaviorDsl=yabolBehaviorDsl,
        initCtx=YabolIdleCtx(Ledger()),
        askBidFromTrades=true)
    } else
      ???

    val orchestratorActor = ActorSystem(
      Behaviors.supervise(orchestrator).onFailure[Throwable](SupervisorStrategy.restartWithBackoff(minBackoff=2.seconds, maxBackoff=30.seconds, randomFactor=0.1)),
      "orchestrator-actor")
    orchestratorActor.scheduler.scheduleAtFixedRate(tillEOM, 1.minute)(() => orchestratorActor ! On1m(None))
    orchestratorActor.scheduler.scheduleAtFixedRate(tillEOH, 1.hour)  (() => orchestratorActor ! On1h(None))
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
