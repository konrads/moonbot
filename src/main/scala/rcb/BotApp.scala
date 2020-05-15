package rcb

import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import akka.actor.typed.{ActorRef, ActorSystem}

import scala.util.Try


object BotApp extends App {
  private val log = Logger("BotApp")

  val conf = ConfigFactory.load()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")
  val bitmexRetries     = conf.getInt("bitmex.restRetries")
  val bitmexRetryBackoffMs  = conf.getLong("bitmex.retryBackoffMs")
  val bitmexRestSyncTimeout = conf.getInt("bitmex.restSyncTimeout")

  val graphiteNamespace = conf.getString("graphite.namespace")
  val graphiteHost      = conf.getString("graphite.host")
  val graphitePort      = conf.getInt("graphite.port")

  val tradeQty               = conf.getInt("bot.tradeQty")
  val openPositionTimeoutMs  = conf.getLong("bot.openPositionTimeoutMs")
  val closePositionTimeoutMs = conf.getLong("bot.closePositionTimeoutMs")
  val backoffMs              = conf.getLong("bot.backoffMs")
  val maxReqRetries          = conf.getInt("bot.maxReqRetries")
  val maxPostOnlyRetries     = conf.getInt("bot.maxPostOnlyRetries")
  val takeProfitAmount       = conf.getDouble("bot.takeProfitAmount")
  val stoplossAmount         = conf.getDouble("bot.stoplossAmount")
  val postOnlyPriceAdjAmount = conf.getDouble("bot.postOnlyPriceAdjAmount")

  implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
  val restGateway: IRestGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, maxRetries=bitmexRetries, retryBackoffMs = bitmexRetryBackoffMs, syncTimeoutMs = bitmexRestSyncTimeout)
  val wsGateway = new WsGateWay(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)

  val orchestrator = OrchestratorActor(
    restGateway=restGateway,
    tradeQty=tradeQty,
    openPositionTimeoutMs=openPositionTimeoutMs, closePositionTimeoutMs=closePositionTimeoutMs, backoffMs=backoffMs,
    maxReqRetries=maxReqRetries, maxPostOnlyRetries=maxPostOnlyRetries,
    takeProfitAmount=takeProfitAmount, stoplossAmount=stoplossAmount, postOnlyPriceAdjAmount=postOnlyPriceAdjAmount)
  val orchestratorActor: ActorRef[ActorEvent] = ActorSystem(orchestrator, "orchestrator-actor")

  val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook,    _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value:UpdatedOrder, _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value, _)              => log.info(s"Got orchestrator ignorable WS message: $value")
    case e:JsError                        => log.error(s"WS consume error!: $e")
  }
  wsGateway.run(wsMessageConsumer)
}
