package moon

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl._
import com.typesafe.scalalogging.Logger
import play.api.libs.json.JsResult

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

// thanks to: https://doc.akka.io/docs/akka-http/10.0.2/scala/http/client-side/websocket-support.html#websocketclientlayer
class WsGateWay(val wsUrl: String, val apiKey: String, val apiSecret: String, minSleepInMs: Option[Long] = Some(5000))(implicit val system: ActorSystem) {
  private val log = Logger[WsGateWay]

  private var endOfLivePromise: Promise[Option[Message]] = null // for the purpose of killing the WS connection

  def run(wsConsume: PartialFunction[JsResult[WsModel], Unit]): Unit = {
    import system.dispatcher

    val incoming: Sink[Message, Future[Done]] =
      Sink.foreach[Message] {
        case message: TextMessage.Strict =>
          val asModel = WsModel.asModel(message.text)
          if (wsConsume.isDefinedAt(asModel))
            wsConsume(asModel)
          else
            log.debug(s"Ignored ws message: $asModel")
        case message: TextMessage =>
          message.toStrict(30.seconds).onComplete {
            case Success(message) =>
              val asModel = WsModel.asModel(message.text)
              if (wsConsume.isDefinedAt(asModel))
                wsConsume(asModel)
              else
                log.debug(s"Ignored ws message: $asModel")
            case Failure(exc) =>
              log.error(s"Unexpected error on ws message fetch", exc)
          }
        case other  =>
          log.error(s"Unexpected non-text message: $other")
      }

    val outgoing = {
      val nonce = System.currentTimeMillis()
      val authMessage = TextMessage(buildOpJson("authKey", apiKey, nonce, getBitmexApiSignature(s"GET/realtime$nonce", apiSecret)))
      val subscribeOrderMessage = TextMessage(buildOpJson("subscribe", "order:XBTUSD"))
      Source(List(authMessage, subscribeOrderMessage))
    }

    val flow = Flow.fromSinkAndSourceMat(incoming, outgoing.concatMat(Source.maybe[Message])(Keep.right))(Keep.right)

    val (upgradeResponse, promise) = Http().singleWebSocketRequest(WebSocketRequest(wsUrl), flow)
    endOfLivePromise = promise

    val connected = upgradeResponse.flatMap { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols)
        Future.successful(Done)
      else
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
    }

    log.info(s"Sleeping for $minSleepInMs ms...")
    // in a real application you would not side effect here
    connected.onComplete(status => log.info(s"WebSocket connection completed, status: $status"))
    Thread.sleep(minSleepInMs.getOrElse(5000))  // to capture error messages prior to closing
  }

  def buildOpJson(op: String, args: Any*): String = {
    val argsAsStrs = args.map {
      case s: String => s""""$s""""
      case o: Object => o.toString
    }
    s"""{"op": "$op", "args": [${argsAsStrs.mkString(", ")}]}"""
  }

  def close(): Unit = endOfLivePromise.success(None)
}