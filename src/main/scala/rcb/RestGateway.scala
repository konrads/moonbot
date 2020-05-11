package rcb

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{JsError, JsSuccess}
import rcb.OrderSide.OrderSide

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try


sealed trait HttpReply
case class CreateOrderIssued(orderID: String) extends HttpReply
case class CancelOrderIssued(orderID: String) extends HttpReply


// FIXME: Unmarshall via akka http spray json
// https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
// case HttpResponse(StatusCodes.OK, _, entity, _) =>
//        Unmarshal(entity).to[Response]

class RestGateway(url: String, apiKey: String, apiSecret: String, maxRetries: Int, retryBackoffMs: Long, syncTimeoutSecs: Int)(implicit system: ActorSystem) {
  val MARKUP_INDUCING_ERROR = "Order had execInst of ParticipateDoNotInitiate"

  // FIXME: consider timeouts!
  // FIXME: keep alive, in http and ws?
  // FIXME: reconnections, in http and ws?

  // based on: https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  private val log = Logger[RestGateway]
  implicit val executionContext = system.dispatcher

  def placeOrder(qty: BigDecimal, price: BigDecimal, side: OrderSide, expiryOpt: Option[Int] = None): Future[Order] =
    reqRetried(
      POST,
      "/api/v1/order",
      (retry: Int) => s"symbol=XBTUSD&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate&orderQty=$qty&side=$side&price=$price",
      // (retry: Int) => s"symbol=XBTUSD&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate&orderQty=$qty&side=$side&price=${price + markup * retry * (if (side == OrderSide.Buy) -1 else 1)}",
      expiryOpt).map(_.asInstanceOf[Order])

  def amendOrder(orderID: String, price: BigDecimal, expiryOpt: Option[Int] = None): Future[Order] =
    reqRetried(
      PUT,
      "/api/v1/order",
      (retry: Int) => s"orderID=$orderID&price=$price",
      expiryOpt).map(_.asInstanceOf[Order])

  def cancelOrder(orderID: String, expiryOpt: Option[Int] = None): Future[Orders] = reqRetried(
    DELETE,
    "/api/v1/order",
    (retry: Int) => s"orderID=$orderID",
    expiryOpt).map(_.asInstanceOf[Orders])

  // sync
  def placeOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide, expiryOpt: Option[Int] = None): Try[Order] =
    Await.ready(
      placeOrder(qty, price, side, expiryOpt),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  def amendOrderSync(orderID: String, price: BigDecimal, expiryOpt: Option[Int] = None): Try[Order] =
    Await.ready(
      amendOrder(orderID, price, expiryOpt),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  def cancelOrderSync(orderID: String, expiryOpt: Option[Int] = None): Try[Orders] =
    Await.ready(
      cancelOrder(orderID, expiryOpt),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  private def reqRetried(method: HttpMethod, urlPath: String, retriedData: (Int) => String, expiryOpt: Option[Int] = None): Future[RestModel] = {
    def sendMsg(retry: Int): Future[RestModel] = {
      val data = retriedData(retry)

      val expiry = expiryOpt.getOrElse((System.currentTimeMillis / 1000 + 100).toInt) //should be 15
      val keyString = s"${method.value}${urlPath}${expiry}${data}"
      val apiSignature = getBitmexApiSignature(keyString, apiSecret)
      val request = HttpRequest(method = method, uri = url + urlPath)
        .withEntity(ContentTypes.`application/x-www-form-urlencoded`, data)
        .withHeaders(
          RawHeader("api-expires",   s"$expiry"),
          RawHeader("api-key",       apiKey),
          RawHeader("api-signature", apiSignature))

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
              b => Future.failed(new Exception(s"BadRequet: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
            }
          case HttpResponse(status, _headers, entity, _) =>
            entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
              b => Future.failed(new Exception(s"Invalid status: $status, body: ${b.utf8String}"))
            }
        }
    }

    def retry[T](op: (Int) => Future[T], retries: Int)(implicit ec: ExecutionContext): Future[T] =
      op(retries).recoverWith {
        case exc:akka.stream.StreamTcpException if retries < maxRetries =>
          log.warn(s"$retries: retrying upon $exc: ${exc.getCause}")
          Thread.sleep(retryBackoffMs)
          retry(op, retries + 1)
        case err =>
          log.warn(s"###### NOT Retrying upon ${err.getClass} ${err.getMessage} ${err.getCause}")
          Future.failed(err)
      }

    retry(sendMsg, 0)
  }
}

case class RetryableError(msg: String) extends Exception(msg)
