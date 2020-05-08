package rcb

import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import akka.actor.typed.{ActorRef, ActorSystem}

object BotApp extends App {
  private val log = Logger[WsGateWay]

  val conf = ConfigFactory.load()
  val bitmexUrl = conf.getString("bitmex.url")
  val bitmexWsUrl = conf.getString("bitmex.wsUrl")
  val bitmexApiKey = conf.getString("bitmex.apiKey")
  val bitmexApiSecret = conf.getString("bitmex.apiSecret")

  implicit val serviceSystem = akka.actor.ActorSystem()
  val httpGateway = new HttpGateway()
  val wsGateway = new WsGateWay(wsUrl = bitmexWsUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret)

  val orchestrator: ActorRef[OrchestratorModel] = ActorSystem(OrchestratorActor(), "orchestrator-actor")

  val wsMessageConsumer = (jsResult: JsResult[WsModel]) => {
    val asStr = jsResult match {
      case JsSuccess(value:OrderBook,    _) => orchestrator ! NotifyWs(value)
      case JsSuccess(value:UpdatedOrder, _) => orchestrator ! NotifyWs(value)
      case JsSuccess(value, _)              => log.info(s"Got orchestrator ignorable message: $value")
      case s:JsError                        => log.error(s"error!: $s")
    }
  }
  wsGateway.run(wsMessageConsumer)
}
