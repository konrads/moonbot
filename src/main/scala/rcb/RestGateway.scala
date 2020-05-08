package rcb

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{JsError, JsSuccess}
import rcb.OrderSide.OrderSide

import scala.concurrent.{ExecutionContext, Future}


sealed trait HttpReply
case class CreateOrderIssued(orderID: String) extends HttpReply
case class CancelOrderIssued(orderID: String) extends HttpReply

class RestGateway(url: String, apiKey: String, apiSecret: String, maxRetries: Int)(implicit system: ActorSystem) {
  private val log = Logger[WsGateWay]
  implicit val executionContext = system.dispatcher

  // FIXME: consider timeouts!
  // FIXME: keep alive, in htttp and ws?
  // FIXME: reconnections, in here and ws?

  // https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  def placeOrder(qty: BigDecimal, price: BigDecimal, side: OrderSide, markupIncrease: BigDecimal): Future[RestModel] =
    sendMsgRetried(
      POST,
      "/api/v1/order",
      (retry: Int) => s"symbol=XBTUSD&ordType=Limit&timeInForce=GoodTillCancel&orderQty=$qty&side=$side&price=${price + markupIncrease * retry * (if (side == OrderSide.Buy) 1 else -1)}")

  def amendOrder(orderID: String, price: BigDecimal): Future[RestModel] =
    sendMsgRetried(
      PUT,
      "/api/v1/order",
      (retry: Int) => s"orderID=$orderID&price=$price")

  def cancelOrder(orderID: String): Future[RestModel] =    sendMsgRetried(
    DELETE,
    "/api/v1/order",
    (retry: Int) => s"orderID=$orderID")

  private def sendMsgRetried(method: HttpMethod, urlPath: String, retriedData: (Int) => String): Future[RestModel] = {
    def sendMsg(retry: Int): Future[RestModel] = {
      val data = retriedData(retry)

      val expiry = ((System.currentTimeMillis / 1000) + 100).toInt //should be 15
      val keyString = s"${method}${urlPath}${expiry}${data}"
      val apiSignature = getBitmexApiSignature(keyString, apiSecret)
      val request = HttpRequest(method = method, uri = url, entity = data).withEntity("")
        .withHeaders(RawHeader("api-expires", s"$expiry"), RawHeader("api-key", apiKey), RawHeader("api-signature", apiSignature) )

      Http().singleRequest(request)
        .flatMap {
          case HttpResponse(StatusCodes.OK, _headers, entity, _) =>
            entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
              b =>
                RestModel.asModel(b.utf8String) match {
                  case JsSuccess(value, _) => Future.successful(value)
                  case JsError(errors) => Future.failed(new Exception(s"Json parsing error: ${errors}"))
                }
            }
          case HttpResponse(s@StatusCodes.BadRequest, _headers, entity, _) =>
            entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
              b => Future.failed(RetryableError(s"BadRequest: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
            }
          case HttpResponse(status, _headers, entity, _) =>
            entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
              b => Future.failed(new Exception(s"Invalid status: $status, body: ${b.utf8String}"))
            }
        }
    }

    def retry[T](op: (Int) => Future[T], retries: Int)(implicit ec: ExecutionContext): Future[T] =
      op(retries).recoverWith {
        case RetryableError(msg) if retries < maxRetries =>
          log.warn(s"Retrying upon $msg")
          retry(op, retries + 1)
        case err =>
          Future.failed(err)
      }

    retry(sendMsg, 0)
  }
}

case class RetryableError(msg: String) extends Exception(msg)
