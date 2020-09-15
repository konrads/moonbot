package moon

import java.io.File

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, SupervisorStrategy}
import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import moon.RunType._
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


object BotApp extends App {
  val log = Logger("BotApp")

  val conf = ConfigFactory.load()
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .withFallback(ConfigFactory.parseFile(new File("application.private.conf")))
    .withFallback(ConfigFactory.parseResources("application.private.conf"))
    .resolve()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")

  val graphiteHost      = conf.getString("graphite.host")
  val graphitePort      = conf.getInt("graphite.port")

  val namespace              = conf.getString("bot.namespace")
  val flushSessionOnRestart  = conf.getBoolean("bot.flushSessionOnRestart")
  val tradeQty               = conf.getInt("bot.tradeQty")
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val takeProfitMargin       = conf.getDouble("bot.takeProfitMargin")
  val stoplossMargin         = conf.getDouble("bot.stoplossMargin")
  val openWithMarket         = conf.optBoolean("bot.openWithMarket").getOrElse(false)
  val useTrailingStoploss    = conf.optBoolean("bot.useTrailingStoploss").getOrElse(false)
  val backtestEventDataDir   = conf.optString("bot.backtestEventDataDir")
  val backtestCandleFile     = conf.optString("bot.backtestCandleFile")
  val useSynthetics          = conf.optBoolean("bot.useSynthetics").getOrElse(false)
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
  assert(runType != RunType.BacktestMoon || backtestEventDataDir.isDefined || backtestCandleFile.isDefined)

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
      |• graphiteHost:         $graphiteHost
      |• graphitePort:         $graphitePort
      |• namespace:            $namespace
      |• tradeQty:             $tradeQty
      |• restSyncTimeoutMs:    $restSyncTimeoutMs
      |• takeProfitMargin:     $takeProfitMargin
      |• stoplossMargin:       $stoplossMargin
      |• openWithMarket:       $openWithMarket
      |• runType:              $runType
      |• backtestEventDataDir: $backtestEventDataDir
      |• backtestCandleFile:   $backtestCandleFile
      |• useSynthetics:        $useSynthetics
      |""".stripMargin)

  val metrics = Metrics(graphiteHost, graphitePort, namespace)
  val strategy = Strategy(name = strategyName, config = conf.getObject(s"strategy.$strategyName").toConfig, parentConfig = conf.getObject(s"strategy").toConfig)

  if (runType == BacktestMoon || runType == BacktestYabol) {
    log.info(s"Instantiating $runType on $backtestEventDataDir or $backtestCandleFile...")
    val sim = new ExchangeSim(
      runType = runType,
      eventDataDir = backtestEventDataDir.orNull,
      candleFile = backtestCandleFile.orNull,
      strategy = strategy,
      tradeQty = tradeQty,
      takeProfitMargin = takeProfitMargin, stoplossMargin = stoplossMargin,
      metrics = Some(metrics),
      openWithMarket = openWithMarket,
      useSynthetics = useSynthetics)
    val (finalCtx, finalExchangeCtx) = sim.run()
    log.info(s"Final Ctx running PandL: ${finalCtx.ledger.ledgerMetrics.runningPandl}")
  } else {
    implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
    val wsGateway = new WsGateway(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)
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
        metrics=Some(metrics),
        flushSessionOnRestart=flushSessionOnRestart,
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()))
    } else if (runType == LiveYabol) {
      log.info(s"Instantiating Live Yabol Run...")
      Behaviour.asLiveBehavior(
        restGateway=new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs),
        metrics=Some(metrics),
        flushSessionOnRestart=flushSessionOnRestart,
        behaviorDsl=yabolBehaviorDsl,
        initCtx=YabolIdleCtx(Ledger()))
    } else if (runType == DryMoon) {
      log.info(s"Instantiating Dry Run...")
      Behaviour.asDryBehavior(
        metrics=Some(metrics),
        behaviorDsl=behaviorDsl,
        initCtx=InitCtx(Ledger()))
    } else if (runType == DryYabol) {
      log.info(s"Instantiating Dry Yabol Run...")
      Behaviour.asDryBehavior(
        metrics=Some(metrics),
        behaviorDsl=yabolBehaviorDsl,
        initCtx=YabolIdleCtx(Ledger()))
    } else
      ???

    val orchestratorActor = ActorSystem(
      Behaviors.supervise(orchestrator).onFailure[Throwable](SupervisorStrategy.restartWithBackoff(minBackoff=2.seconds, maxBackoff=30.seconds, randomFactor=0.1)),
      "orchestrator-actor")
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
