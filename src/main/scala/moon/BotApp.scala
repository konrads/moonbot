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
  val backtestDataDir        = conf.optString("bot.backtestDataDir")
  val runType                = conf.optString("bot.runType").map(_.toLowerCase) match {
    case Some("live")     => Live
    case Some("dry")      => Dry
    case Some("backtest") => Backtest
    case Some(other)      => throw new Exception(s"Invalid bot.runType: $other")
    case None             => Live
  }
  assert(runType != RunType.Backtest || backtestDataDir.isDefined)

  val strategyName = conf.getString("strategy.selection")

  val notLiveWarning = runType match {
    case Live => ""
    case Dry =>
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
    case Backtest =>
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
      |• backtestDataDir:      $backtestDataDir
      |""".stripMargin)

  val metrics = Metrics(graphiteHost, graphitePort, namespace)
  val strategy = Strategy(name = strategyName, config = conf.getObject(s"strategy.$strategyName").toConfig, parentConfig = conf.getObject(s"strategy").toConfig)

  if (runType == Backtest) {
    log.info(s"Instantiating Backtest on $backtestDataDir...")
    val sim = new ExchangeSim(
      dataDir = backtestDataDir.get,
      strategy = strategy,
      tradeQty = tradeQty,
      takeProfitMargin = takeProfitMargin, stoplossMargin = stoplossMargin,
      metrics = Some(metrics),
      openWithMarket = openWithMarket)
    val finalCtx = sim.run()
    log.info(s"Final Ctx running PandL: ${finalCtx.ledger.ledgerMetrics.runningPandl}")
  } else {
    implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
    val wsGateway = new WsGateway(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)
    val behaviorDsl=OrchestratorActor.asDsl(
      strategy=strategy,
      tradeQty=tradeQty,
      takeProfitMargin=takeProfitMargin,
      stoplossMargin=stoplossMargin,
      openWithMarket=openWithMarket,
      useTrailingStoploss=useTrailingStoploss)

    val orchestrator = if (runType == Live) {
      log.info(s"Instantiating Live Run...")
      OrchestratorActor.asLiveBehavior(
        restGateway=new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs),
        metrics=Some(metrics),
        flushSessionOnRestart=flushSessionOnRestart,
        behaviorDsl=behaviorDsl)
    } else if (runType == Dry) {
      log.info(s"Instantiating Dry Run...")
      OrchestratorActor.asDryBehavior(
        metrics=Some(metrics),
        behaviorDsl=behaviorDsl)
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
