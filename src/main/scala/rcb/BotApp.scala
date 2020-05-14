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

  val fsmTradeQty           = conf.getInt("fsm.tradeQty")
  val fsmRequestTimeoutSecs = conf.getInt("fsm.requestTimeoutSecs")
  val fsmHoldTimeoutSecs    = conf.getInt("fsm.holdTimeoutSecs")

  implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
  val restGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, maxRetries=bitmexRetries, retryBackoffMs = bitmexRetryBackoffMs, syncTimeoutSecs = bitmexRestSyncTimeout)
  val wsGateway = new WsGateWay(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)

  val orchestrator = OrchestratorActor(
    restGateway=restGateway,
    tradeQty=fsmTradeQty,
    requestTimeoutSecs=fsmRequestTimeoutSecs, holdTimeoutSecs=fsmHoldTimeoutSecs, maxRetries=bitmexRetries)
  val orchestratorActor: ActorRef[ActorEvent] = ActorSystem(orchestrator, "orchestrator-actor")

  val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook,    _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value:UpdatedOrder, _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value, _)              => log.info(s"Got orchestrator ignorable WS message: $value")
    case e:JsError                        => log.error(s"WS consume error!: $e")
  }
  wsGateway.run(wsMessageConsumer)

  val restMessageConsumer: PartialFunction[Try[(String, JsResult[RestModel])], Unit] = {
    case scala.util.Success((clientOrderID, JsSuccess(value:Order,  _))) => orchestratorActor ! RestEvent(clientOrderID, value)
    case scala.util.Success((clientOrderID, JsSuccess(value:Orders, _))) => orchestratorActor ! RestEvent(clientOrderID, value)
    case scala.util.Success((clientOrderID, JsSuccess(value:, _)))       => log.info(s"Got orchestrator ignorable REST clientOrderID: $clientOrderID, message: $value")
    case scala.util.Success((clientOrderID, e:JsError))                  => orchestratorActor ! RestEvent(clientOrderID, Error(ErrorDetail(name="JsError", message=e.toString)))  // FIXME
    case scala.util.Failure(exc)                                         => throw exc
  }
  restGateway.run(restMessageConsumer)
}
