package moon

import java.io.File

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, SupervisorStrategy}
import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import moon.RunType._

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
  val minTradeVol            = conf.getInt("bot.minTradeVol")
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val openPositionExpiryMs   = conf.getLong("bot.openPositionExpiryMs")
  val reqRetries             = conf.getInt("bot.reqRetries")
  val markupRetries          = conf.getInt("bot.markupRetries")
  val takeProfitMargin       = conf.getDouble("bot.takeProfitMargin")
  val stoplossMargin         = conf.getDouble("bot.stoplossMargin")
  val postOnlyPriceAdj       = conf.getDouble("bot.postOnlyPriceAdj")
  val openWithMarket         = conf.optBoolean("bot.openWithMarket").getOrElse(false)
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
      |• minTradeVol:          $minTradeVol
      |• restSyncTimeoutMs:    $restSyncTimeoutMs
      |• openPositionExpiryMs: $openPositionExpiryMs
      |• reqRetries:           $reqRetries
      |• markupRetries:        $markupRetries
      |• takeProfitMargin:     $takeProfitMargin
      |• stoplossMargin:       $stoplossMargin
      |• postOnlyPriceAdj:     $postOnlyPriceAdj
      |• openWithMarket:       $openWithMarket
      |• runType:              $runType
      |• backtestDataDir:      $backtestDataDir
      |""".stripMargin)

  val metrics = Metrics(graphiteHost, graphitePort, namespace)
  val strategy = Strategy(name = strategyName, config = conf.getObject(s"strategy.$strategyName").toConfig, parentConfig = conf.getObject(s"strategy").toConfig)

  if (runType == Backtest) {
    val simParent = ExchangeSim.setup(
      dataDir=backtestDataDir.get,
      strategy=strategy,
      flushSessionOnRestart=flushSessionOnRestart,
      tradeQty=tradeQty, minTradeVol=minTradeVol,
      openPositionExpiryMs=openPositionExpiryMs,
      reqRetries=reqRetries, markupRetries=markupRetries,
      takeProfitMargin=takeProfitMargin, stoplossMargin=stoplossMargin, postOnlyPriceAdj=postOnlyPriceAdj,
      metrics=Some(metrics),
      openWithMarket=openWithMarket,
      runType=runType)
    ActorSystem(simParent, "sim-parent-actor")
  } else {
    implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
    val wsGateway = new WsGateway(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)
    val restGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs=restSyncTimeoutMs)
    val orchestrator = OrchestratorActor(
      strategy=strategy,
      flushSessionOnRestart=flushSessionOnRestart,
      restGateway=restGateway,
      tradeQty=tradeQty, minTradeVol=minTradeVol,
      openPositionExpiryMs=openPositionExpiryMs,
      reqRetries=reqRetries, markupRetries=markupRetries,
      takeProfitMargin=takeProfitMargin, stoplossMargin=stoplossMargin, postOnlyPriceAdj=postOnlyPriceAdj,
      metrics=Some(metrics),
      openWithMarket=openWithMarket,
      runType=runType)

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
    //    val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
    //      case JsSuccess(value:OrderBook, _) => orchestratorActor ! WsEvent(value.summary)
    //      case JsSuccess(value, _) => orchestratorActor ! WsEvent(value)
    //      case e:JsError           => log.error("WS consume error!", e)
    //    }
    wsGateway.run(new CachedConsumer().wsMessageConsumer)
  }
}
