package rcb

import akka.actor.ActorSystem
import akka.Done
import akka.http.scaladsl.Http
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import play.api.libs.json.JsResult

import scala.concurrent.{Future, Promise}
import com.typesafe.scalalogging.Logger

// thanks to: https://doc.akka.io/docs/akka-http/10.0.2/scala/http/client-side/websocket-support.html#websocketclientlayer
class WsGateWay(val wsUrl: String, val apiKey: String, val apiSecret: String, minSleepInSecs: Int = 5)(implicit val system: ActorSystem) {
  private val log = Logger[WsGateWay]

  private var endOfLivePromise: Promise[Option[Message]] = null // for the purpose of killing the WS connection

  def run(wsConsume: (JsResult[WsModel]) => Unit): Unit = {
    import system.dispatcher

    val incoming: Sink[Message, Future[Done]] =
      Sink.foreach[Message] {
        case message: TextMessage.Strict => wsConsume(WsModel.asModel(message.text))
        case other                       => log.error(s"Unexpected non-text message: $other")
      }

    val outgoing = {
      val nonce = System.currentTimeMillis()
      val authMessage = TextMessage(buildOpJson("authKey", apiKey, nonce, getApiSignature(apiSecret, nonce)))
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

    log.info(s"Sleeping for $minSleepInSecs secs...")
    // in a real application you would not side effect here
    connected.onComplete(status => log.info(s"WebSocket connection completed, status: $status"))
    Thread.sleep(minSleepInSecs * 1000)  // to capture error messages prior to closing

  }

  def getApiSignature(apiSecret: String, nonce: Long): String = {
    val keyString = "GET/realtime" + nonce
    val sha256HMAC = Mac.getInstance("HmacSHA256")
    val secretKey = new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA256")
    sha256HMAC.init(secretKey)
    val hash = DatatypeConverter.printHexBinary(sha256HMAC.doFinal(keyString.getBytes))
    hash
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


