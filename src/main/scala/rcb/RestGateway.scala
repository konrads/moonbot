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

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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
  def placeStopMarketOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): (String, Future[Order])
  def placeMarketOrderAsync(qty: BigDecimal, side: OrderSide): (String, Future[Order])
  def placeLimitOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): (String, Future[Order])
  def amendOrderAsync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Future[Order]
  def cancelOrderAsync(orderID: Option[String], clOrdID: Option[String]): Future[Orders]

  // sync
  def placeStopMarketOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide): Try[Order]
  def placeMarketOrderSync(qty: BigDecimal, side: OrderSide): Try[Order]
  def placeLimitOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide): Try[Order]
  def amendOrderSync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Try[Order]
  def cancelOrderSync(orderID: Option[String], clOrdID: Option[String]): Try[Orders]
}


class RestGateway(symbol: String = "XBTUSD", url: String, apiKey: String, apiSecret: String, syncTimeoutMs: Long)(implicit system: ActorSystem) extends IRestGateway {
  val MARKUP_INDUCING_ERROR = "Order had execInst of ParticipateDoNotInitiate"

  // FIXME: consider timeouts!
  // FIXME: keep alive, in ws?
  // FIXME: reconnections, in http and ws?

  // based on: https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  private val log = Logger[RestGateway]
  implicit val executionContext = system.dispatcher

  def placeStopMarketOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): (String, Future[Order]) = {
    val clOrdID = java.util.UUID.randomUUID().toString
    val resF = placeStopMarketOrder(qty, side, price, clOrdID=Some(clOrdID))
    (clOrdID, resF)
  }

  def placeMarketOrderAsync(qty: BigDecimal, side: OrderSide): (String, Future[Order]) = {
    val clOrdID = java.util.UUID.randomUUID().toString
    val resF = placeMarketOrder(qty, side, Some(clOrdID))
    (clOrdID, resF)
  }

  def placeLimitOrderAsync(qty: BigDecimal, price: BigDecimal, side: OrderSide): (String, Future[Order]) = {
    val clOrdID = java.util.UUID.randomUUID().toString
    val resF = placeLimitOrder(qty, price, side, Some(clOrdID))
    (clOrdID, resF)
  }

  def amendOrderAsync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Future[Order] =
    amendOrder(orderID, clOrdID, price)

  def cancelOrderAsync(orderID: Option[String], clOrdID: Option[String]): Future[Orders] =
    cancelOrder(orderID, clOrdID)

  // sync
  def placeStopMarketOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide): Try[Order] =
    Await.ready(
      placeStopMarketOrder(qty, side, price),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get

  def placeMarketOrderSync(qty: BigDecimal, side: OrderSide): Try[Order] =
    Await.ready(
      placeMarketOrder(qty, side),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get

  def placeLimitOrderSync(qty: BigDecimal, price: BigDecimal, side: OrderSide): Try[Order] =
    Await.ready(
      placeLimitOrder(qty, price, side),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get

  def amendOrderSync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Try[Order] =
    Await.ready(
      amendOrder(orderID, clOrdID, price),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get

  def cancelOrderSync(orderID: Option[String], clOrdID: Option[String]): Try[Orders] =
    Await.ready(
      cancelOrder(orderID, clOrdID),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get

  private def placeStopMarketOrder(qty: BigDecimal, side: OrderSide, price: BigDecimal, execInst: String="LastPrice", clOrdID: Option[String]=None): Future[Order] =
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Stop&timeInForce=GoodTillCancel&execInst=$execInst&stopPx=$price&orderQty=$qty&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  private def placeMarketOrder(qty: BigDecimal, side: OrderSide, clOrdID: Option[String]=None): Future[Order] =
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Market&timeInForce=GoodTillCancel&orderQty=$qty&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  private def placeLimitOrder(qty: BigDecimal, price: BigDecimal, side: OrderSide, clOrdID: Option[String]=None): Future[Order] =
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate&orderQty=$qty&side=$side&price=$price" + clOrdID.map("&clOrdID=" + _).getOrElse(""),
    ).map(_.asInstanceOf[Order])

  private def amendOrder(orderID: Option[String], cliOrdID: Option[String], price: BigDecimal): Future[Order] =
    sendReq(
      PUT,
      "/api/v1/order",
      s"price=$price" + orderID.map("&orderID=" + _).getOrElse("") + cliOrdID.map("&origClOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  private def cancelOrder(orderID: Option[String], cliOrdID: Option[String]): Future[Orders] =
    sendReq(
      DELETE,
      "/api/v1/order",
      orderID.map("&orderID=" + _).getOrElse("") + cliOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Orders])

  private def sendReq(method: HttpMethod, urlPath: String, data: => String): Future[RestModel] = {
    val expiry = System.currentTimeMillis / 1000 + 100 //should be 15
    val keyString = s"${method.value}${urlPath}${expiry}${data}"
    val apiSignature = getBitmexApiSignature(keyString, apiSecret)

    val request = HttpRequest(method = method, uri = url + urlPath)
      .withEntity(ContentTypes.`application/x-www-form-urlencoded`, data)
      .withHeaders(
        RawHeader("api-expires",   expiry.toString),
        RawHeader("api-key",       apiKey),
        RawHeader("api-signature", apiSignature))

//    println(s"### expiry       = $expiry")
//    println(s"### keyString    = $keyString")
//    println(s"### apiSignature = $apiSignature")
//    println(s"### apiSecret    = $apiSecret")
//    println(s"### apiKey       = $apiKey")
//    println(s"### data         = $data")
//    println(s"\n### request         = $request\n${request.entity}")

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
            b => Future.failed(new Exception(s"BadRequest: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
          }
        // FIXME: Account for 503:
        //        2020-04-26 23:21:10.908  INFO 61619 --- [t-dispatcher-67] .s.LoggingHttpRequestResponseInterceptor : Status code  : 503 SERVICE_UNAVAILABLE
        //        2020-04-26 23:21:10.908  INFO 61619 --- [t-dispatcher-67] .s.LoggingHttpRequestResponseInterceptor : Headers      : [Date:"Sun, 26 Apr 2020 13:21:10 GMT", Content-Type:"application/json; charset=utf-8", Content-Length:"102", Connection:"keep-alive", Set-Cookie:"AWSALBTG=kdTm30cLPGFSnbZOmPyt4j8NjwPP2W/bWJpOMtH5YHeQ8C2NgIpIA9cT0od75ui6e0jTBAom5k2XUPgsjY3eqcO6Ft5aKCbW7dZyX/NoI84swgH3AfQwW+pch/vePpJVKQLG3bq118RKAWkZDyr8qqfCSi7ut7tKxzLe7MxMhk+Cy467UDI=; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/", "AWSALBTGCORS=kdTm30cLPGFSnbZOmPyt4j8NjwPP2W/bWJpOMtH5YHeQ8C2NgIpIA9cT0od75ui6e0jTBAom5k2XUPgsjY3eqcO6Ft5aKCbW7dZyX/NoI84swgH3AfQwW+pch/vePpJVKQLG3bq118RKAWkZDyr8qqfCSi7ut7tKxzLe7MxMhk+Cy467UDI=; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/; SameSite=None; Secure", "AWSALB=rqmeyZQOwEsI/tjLk6BdKq2+9xdxQVGuZ114iqjXh7i0c1JNZd423vfbfE+VYNYeSBm6tICBdF5IJHtBRY/1cNJV/gig/w0jmPMiQexwGOwwXDyt7kur/gDIbYye; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/", "AWSALBCORS=rqmeyZQOwEsI/tjLk6BdKq2+9xdxQVGuZ114iqjXh7i0c1JNZd423vfbfE+VYNYeSBm6tICBdF5IJHtBRY/1cNJV/gig/w0jmPMiQexwGOwwXDyt7kur/gDIbYye; Expires=Sun, 03 May 2020 13:21:10 GMT; Path=/; SameSite=None; Secure", X-RateLimit-Limit:"60", X-RateLimit-Remaining:"59", X-RateLimit-Reset:"1587907271", X-Powered-By:"Profit", ETag:"W/"66-qhi7rqXXvlVhEy8FfnYZtT4OszQ"", Strict-Transport-Security:"max-age=31536000; includeSubDomains"]
        //        2020-04-26 23:21:10.908  INFO 61619 --- [t-dispatcher-67] .s.LoggingHttpRequestResponseInterceptor : Response body: {"error":{"message":"The system is currently overloaded. Please try again later.","name":"HTTPError"}}
        case HttpResponse(s@StatusCodes.ServiceUnavailable, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
            b => Future.failed(TemporarilyUnavailableError(s"ServiceUnavailable: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
          }
        case HttpResponse(status, _headers, entity, _) =>
          val contentsF = entity.dataBytes.runFold(ByteString(""))(_ ++ _)
          contentsF.flatMap(bs => Future.failed(new Exception(s"Invalid status: $status, body: ${bs.utf8String}")))
      }
  }
}

case class RetryableError(msg: String) extends Exception(msg)

case class TemporarilyUnavailableError(msg: String) extends Exception(msg)
