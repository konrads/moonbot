package rcb

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import HttpProtocols._
import MediaTypes._
import HttpCharsets._
import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import play.api.libs.json.{JsError, JsSuccess}
import rcb.OrderSide.OrderSide

sealed trait HttpReply
case class CreateOrderIssued(orderID: String) extends HttpReply
case class CancelOrderIssued(orderID: String) extends HttpReply

class RestGateway(url: String, apiKey: String, apiSecret: String, restRetries: Int)(implicit system: ActorSystem) {
  implicit val executionContext = system.dispatcher

  // FIXME: consider backoff and retries!
  // FIXME: consider timeouts!
  // FIXME: keep alive, in htttp and ws?
  // FIXME: reconnections, in here and ws?

  // https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  def placeOrder(qty: BigDecimal, price: BigDecimal, side: OrderSide, markuper: (BigDecimal) => BigDecimal): Future[RestModel] = {
    val urlPath = "/api/v1/order"
    val data = s"symbol=XBTUSD&ordType=Limit&timeInForce=GoodTillCancel&orderQty=$qty&price=$price&side=$side"
    Http().singleRequest(createRequest(POST, urlPath, apiKey, data))
      .flatMap {
        case HttpResponse(StatusCodes.OK, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
            b =>
              RestModel.asModel(b.utf8String) match {
                case JsSuccess(value, _) => Future.successful(value)
                case JsError(errors) => Future.failed(new Exception(s"Json parsing error: ${errors}"))
              }
          }
        case HttpResponse(status, _headers, entity, _) => Future.failed(new Exception(s"Invalid status: $status, entity: $entity"))
      }
  }

  private def createRequest(httpMethod: HttpMethod, urlPath: String, apiKey: String, data: String): HttpRequest = {
    val expiry = ((System.currentTimeMillis / 1000) + 100).toInt //should be 15
    val keyString = s"${httpMethod}${urlPath}${expiry}${data}"
    val apiSignature = getBitmexApiSignature(keyString, apiSecret)
    HttpRequest(method = PUT, uri = url, entity = data).withEntity("")
      .withHeaders(RawHeader("api-expires", s"$expiry"), RawHeader("api-key", apiKey), RawHeader("api-signature", apiSignature) )
  }
}
