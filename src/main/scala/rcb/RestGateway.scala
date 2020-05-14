package rcb

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{JsError, JsResult, JsSuccess}
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

/** Trait, for testing purposes */
trait IRestGateway {
  // async
  def placeMarketOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): String
  def placeLimitOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): String
  def amendOrderAsync(orderID: String, price: BigDecimal): String
  def cancelOrderAsync(orderID: String): String

  // sync
  def placeMarketOrderSync(qty: BigDecimal, side: OrderSide): Try[Order]
  def placeLimitOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide): Try[Order]
  def amendOrderSync(orderID: String, price: BigDecimal): Try[Order]
  def cancelOrderSync(orderID: String): Try[Orders]
}


class RestGateway(url: String, apiKey: String, apiSecret: String, maxRetries: Int, retryBackoffMs: Long, syncTimeoutSecs: Int)(implicit system: ActorSystem) extends IRestGateway {
  var restConsume: PartialFunction[Try[(String, RestModel)], Unit] = null  // FIXME: hack var for the wire up of asyncs

  def setup(restConsume: PartialFunction[Try[(String, RestModel)], Unit]): Unit = {
    this.restConsume = restConsume
  }

  val MARKUP_INDUCING_ERROR = "Order had execInst of ParticipateDoNotInitiate"

  // FIXME: consider timeouts!
  // FIXME: keep alive, in http and ws?
  // FIXME: reconnections, in http and ws?

  // based on: https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  private val log = Logger[RestGateway]
  implicit val executionContext = system.dispatcher

  def placeMarketOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): String = {
    val clientID = java.util.UUID.randomUUID().toString
    val resF = placeMarketOrder(qty, side).map(res => (clientID, res)).onComplete(restConsume)
    clientID
  }

  def placeLimitOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): String = {
    val clientID = java.util.UUID.randomUUID().toString
    val resF = placeLimitOrder(qty, price, side).map(res => (clientID, res)).onComplete(restConsume)
    clientID
  }

  def amendOrderAsync(orderID: String, price: BigDecimal): String = {
    val clientID = java.util.UUID.randomUUID().toString
    val resF = amendOrder(orderID, price).map(res => (clientID, res)).onComplete(restConsume)
    clientID
  }

  def cancelOrderAsync(orderID: String): String = {
    val clientID = java.util.UUID.randomUUID().toString
    val resF = cancelOrder(orderID).map(res => (clientID, res)).onComplete(restConsume)
    clientID
  }

  // sync
  def placeMarketOrderSync(qty: BigDecimal, side: OrderSide): Try[Order] =
    Await.ready(
      placeMarketOrder(qty, side),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  def placeLimitOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide): Try[Order] =
    Await.ready(
      placeLimitOrder(qty, price, side),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  def amendOrderSync(orderID: String, price: BigDecimal): Try[Order] =
    Await.ready(
      amendOrder(orderID, price),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  def cancelOrderSync(orderID: String): Try[Orders] =
    Await.ready(
      cancelOrder(orderID),
      Duration(syncTimeoutSecs, SECONDS)
    ).value.get

  private def placeMarketOrder(qty: BigDecimal, side: OrderSide): Future[Order] = ???

  private def placeLimitOrder(qty: BigDecimal, price: BigDecimal, side: OrderSide): Future[Order] =
    reqRetried(
      POST,
      "/api/v1/order",
      (retry: Int) => s"symbol=XBTUSD&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate&orderQty=$qty&side=$side&price=$price",
      // (retry: Int) => s"symbol=XBTUSD&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate&orderQty=$qty&side=$side&price=${price + markup * retry * (if (side == OrderSide.Buy) -1 else 1)}",
      ).map(_.asInstanceOf[Order])

  private def amendOrder(orderID: String, price: BigDecimal): Future[Order] =
    reqRetried(
      PUT,
      "/api/v1/order",
      (retry: Int) => s"orderID=$orderID&price=$price",
      ).map(_.asInstanceOf[Order])

  private def cancelOrder(orderID: String): Future[Orders] =
    reqRetried(
      DELETE,
      "/api/v1/order",
      (retry: Int) => s"orderID=$orderID",
      ).map(_.asInstanceOf[Orders])

  private def reqRetried(method: HttpMethod, urlPath: String, retriedData: (Int) => String): Future[RestModel] = {
    def sendMsg(retry: Int): Future[RestModel] = {
      val data = retriedData(retry)

      val expiry = (System.currentTimeMillis / 1000 + 100).toInt //should be 15
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
            // FIXME: Account for 503:
            //        2020-04-26 23:21:10.908  INFO 61619 --- [t-dispatcher-67] .s.LoggingHttpRequestResponseInterceptor : Status code  : 503 SERVICE_UNAVAILABLE
            //        2020-04-26 23:21:10.908  INFO 61619 --- [t-dispatcher-67] .s.LoggingHttpRequestResponseInterceptor : Headers      : [Date:"Sun, 26 Apr 2020 13:21:10 GMT", Content-Type:"application/json; charset=utf-8", Content-Length:"102", Connection:"keep-alive", Set-Cookie:"AWSALBTG=kdTm30cLPGFSnbZOmPyt4j8NjwPP2W/bWJpOMtH5YHeQ8C2NgIpIA9cT0od75ui6e0jTBAom5k2XUPgsjY3eqcO6Ft5aKCbW7dZyX/NoI84swgH3AfQwW+pch/vePpJVKQLG3bq118RKAWkZDyr8qqfCSi7ut7tKxzLe7MxMhk+Cy467UDI=; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/", "AWSALBTGCORS=kdTm30cLPGFSnbZOmPyt4j8NjwPP2W/bWJpOMtH5YHeQ8C2NgIpIA9cT0od75ui6e0jTBAom5k2XUPgsjY3eqcO6Ft5aKCbW7dZyX/NoI84swgH3AfQwW+pch/vePpJVKQLG3bq118RKAWkZDyr8qqfCSi7ut7tKxzLe7MxMhk+Cy467UDI=; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/; SameSite=None; Secure", "AWSALB=rqmeyZQOwEsI/tjLk6BdKq2+9xdxQVGuZ114iqjXh7i0c1JNZd423vfbfE+VYNYeSBm6tICBdF5IJHtBRY/1cNJV/gig/w0jmPMiQexwGOwwXDyt7kur/gDIbYye; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/", "AWSALBCORS=rqmeyZQOwEsI/tjLk6BdKq2+9xdxQVGuZ114iqjXh7i0c1JNZd423vfbfE+VYNYeSBm6tICBdF5IJHtBRY/1cNJV/gig/w0jmPMiQexwGOwwXDyt7kur/gDIbYye; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/; SameSite=None; Secure", X-RateLimit-Limit:"60", X-RateLimit-Remaining:"59", X-RateLimit-Reset:"1587907271", X-Powered-By:"Profit", ETag:"W/"66-qhi7rqXXvlVhEy8FfnYZtT4OszQ"", Strict-Transport-Security:"max-age=31536000; includeSubDomains"]
            //        2020-04-26 23:21:10.908  INFO 61619 --- [t-dispatcher-67] .s.LoggingHttpRequestResponseInterceptor : Response body: {"error":{"message":"The system is currently overloaded. Please try again later.","name":"HTTPError"}}
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

case class TemporarilyUnavailableError(msg: String) extends Exception(msg)
