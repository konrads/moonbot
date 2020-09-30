package moon

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl._

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object WsPlayground extends App {
  println("\n\nRUN IN CONJUNCTION WITH: bin/wss_server.py !!!\n\n")
  val wsUrl = "ws://localhost:8765"

  implicit val system: akka.actor.ActorSystem = akka.actor.ActorSystem()

  private var endOfLivePromise: Promise[Option[Message]] = null // for the purpose of killing the WS connection

  run()

  def run(): Unit = {
    println(s"Starting connection $wsUrl")
    import system.dispatcher

    val incoming: Sink[Message, Future[Done]] =
      Sink.foreach[Message] {
        case TextMessage.Strict(text) =>
          println(s"--> $text")
        case TextMessage.Streamed(stream) =>
          stream
            .limit(100)
            .completionTimeout(50.seconds)
            .runFold("")(_ + _)
            .onComplete {
              case Success(text) =>
                println(s"-> $text")
              case Failure(exc) =>
                println(s"Unexpected error on ws message fetch: $exc")
            }
        case other  =>
          println(s"Unexpected non-text message: $other")
      }

    val outgoing = Source(Seq(TextMessage("AUTH message!")))

    // determining when server closed connection:
    // https://stackoverflow.com/questions/37727410/how-to-listen-websocket-server-close-events-using-akka-http-websocket-client
    val flow = Flow.fromSinkAndSourceMat(incoming, outgoing.concatMat(Source.maybe[Message])(Keep.right))(Keep.both)

    val (upgradeResponse, (sinkClose, sourceClose)) = Http().singleWebSocketRequest(WebSocketRequest(wsUrl), flow)
    endOfLivePromise = sourceClose

    sinkClose.onComplete {
      case Success(_) =>
        println(s"Server Connection closed gracefully $wsUrl, stackTrade: ${new Exception().getStackTrace.length}")
        run()
      case Failure(e) =>
        println(s"Server Connection closed with an error $wsUrl, stackTrade: ${new Exception().getStackTrace.length}: $e")
        run()
    }

    val connected = upgradeResponse.flatMap { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols)
        Future.successful(Done)
      else
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
    }

    connected.onComplete(status => println(s"WebSocket connection completed, status: $status"))
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
