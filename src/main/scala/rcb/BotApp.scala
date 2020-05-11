package rcb

import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import akka.actor.typed.{ActorRef, ActorSystem}


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

  val fsmTradeQty         = conf.getInt("fsm.tradeQty")
  val fsmTradeTimeoutSecs = conf.getInt("fsm.tradeTimeoutSecs")
  val fsmHoldTimeoutSecs  = conf.getInt("fsm.holdTimeoutSecs")

  implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
  val restGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, maxRetries=bitmexRetries, retryBackoffMs = bitmexRetryBackoffMs, syncTimeoutSecs = bitmexRestSyncTimeout)
  val wsGateway = new WsGateWay(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)

  val orchestrator = OrchestratorActor(
    restGateway=restGateway,
    tradeQty=fsmTradeQty,
    tradeTimeoutSecs=fsmTradeTimeoutSecs, holdTimeoutSecs=fsmHoldTimeoutSecs, maxRetries=bitmexRetries)
  val orchestratorActor: ActorRef[ActorEvent] = ActorSystem(orchestrator, "orchestrator-actor")

  val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook,    _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value:UpdatedOrder, _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value, _)              => log.info(s"Got orchestrator ignorable message: $value")
    case s:JsError                        => log.error(s"error!: $s")
  }
  wsGateway.run(wsMessageConsumer)
}
