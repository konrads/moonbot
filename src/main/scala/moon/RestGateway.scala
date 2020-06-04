package moon

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{JsError, JsSuccess, Json}
import moon.OrderSide.OrderSide

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
  def placeBulkOrdersAsync(orderReqs: OrderReqs): Future[Orders]
  def placeStopOrderAsync(qty: BigDecimal, side: OrderSide, price: BigDecimal, isClose: Boolean, execInst: String="LastPrice", clOrdID: Option[String]=None): Future[Order]
  def placeTrailingStopOrderAsync(qty: BigDecimal, side: OrderSide, pegOffsetValue: BigDecimal, isClose: Boolean, execInst: String="LastPrice", clOrdID: Option[String]=None): Future[Order]
  def placeMarketOrderAsync(qty: BigDecimal, side: OrderSide, clOrdID: Option[String]=None): Future[Order]
  def placeLimitOrderAsync(qty: BigDecimal, price: BigDecimal, reduceOnly: Boolean, side: OrderSide, clOrdID: Option[String]=None): Future[Order]
  def amendOrderAsync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Future[Order]
  def cancelOrderAsync(orderID: Option[Seq[String]], clOrdID: Option[Seq[String]]): Future[Orders]

  // sync
  def placeBulkOrdersSync(orderReqs: OrderReqs): Try[Orders]
  def placeStopOrderSync(qty: BigDecimal, price: BigDecimal, isClose: Boolean, side: OrderSide): Try[Order]
  def placeTrailingStopOrderSync(qty: BigDecimal, pegOffsetValue: BigDecimal, isClose: Boolean, side: OrderSide): Try[Order]
  def placeMarketOrderSync(qty: BigDecimal, side: OrderSide): Try[Order]
  def placeLimitOrderSync(qty: BigDecimal, price: BigDecimal, isReduceOnly: Boolean, side: OrderSide): Try[Order]
  def amendOrderSync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Try[Order]
  def cancelOrderSync(orderID: Option[Seq[String]], clOrdID: Option[Seq[String]]): Try[Orders]
}


class RestGateway(symbol: String = "XBTUSD", url: String, apiKey: String, apiSecret: String, syncTimeoutMs: Long)(implicit system: ActorSystem) extends IRestGateway {
  val MARKUP_INDUCING_ERROR = "Order had execInst of ParticipateDoNotInitiate"

  // FIXME: consider timeouts!
  // FIXME: keep alive, in ws?
  // FIXME: reconnections, in http and ws?

  // based on: https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  private val log = Logger[RestGateway]
  implicit val executionContext = system.dispatcher

  // sync
  def placeBulkOrdersSync(orderReqs: OrderReqs): Try[Orders] =
    Await.ready(
      placeBulkOrdersAsync(orderReqs),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get  // FIXME: not wrapping in recoverable error...

  def placeStopOrderSync(qty: BigDecimal, price: BigDecimal, isClose: Boolean, side: OrderSide): Try[Order] =
    Await.ready(
      placeStopOrderAsync(qty, side, price, isClose),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get  // FIXME: not wrapping in recoverable error...

  def placeTrailingStopOrderSync(qty: BigDecimal, pegOffsetValue: BigDecimal, isClose: Boolean, side: OrderSide): Try[Order] =
    Await.ready(
      placeTrailingStopOrderAsync(qty, side, pegOffsetValue, isClose),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get  // FIXME: not wrapping in recoverable error...

  def placeMarketOrderSync(qty: BigDecimal, side: OrderSide): Try[Order] =
    Await.ready(
      placeMarketOrderAsync(qty, side),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get  // FIXME: not wrapping in recoverable error...

  def placeLimitOrderSync(qty: BigDecimal, price: BigDecimal, isReduceOnly: Boolean, side: OrderSide): Try[Order] =
    Await.ready(
      placeLimitOrderAsync(qty, price, isReduceOnly, side),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get  // FIXME: not wrapping in recoverable error...

  def amendOrderSync(orderID: Option[String], clOrdID: Option[String], price: BigDecimal): Try[Order] =
    Await.ready(
      amendOrderAsync(orderID, clOrdID, price),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).recoverWith { case exc: TimeoutException => throw TimeoutError(s"Timeout on amendOrderSync orderID: $orderID, clOrdID: $clOrdID") }.value.get

  def cancelOrderSync(orderID: Option[Seq[String]], clOrdID: Option[Seq[String]]): Try[Orders] =
    Await.ready(
      cancelOrderAsync(orderID, clOrdID),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).recoverWith { case exc: TimeoutException => throw TimeoutError(s"Timeout on cancelOrderSync orderID: ${orderID.getOrElse(Nil).mkString(", ")}, clOrdID: ${clOrdID.getOrElse(Nil).mkString(", ")}") }.value.get

  // LastPrice trigger as described in: https://www.reddit.com/r/BitMEX/comments/8pi7j7/bitmex_api_how_to_switch_sl_trigger_to_last_price/
  def placeStopOrderAsync(qty: BigDecimal, side: OrderSide, price: BigDecimal, isClose: Boolean, execInst: String="LastPrice", clOrdID: Option[String]=None): Future[Order] = {
    val closeStr = if (isClose) ",Close" else ""
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Stop&timeInForce=GoodTillCancel&execInst=$execInst$closeStr&stopPx=$price&orderQty=$qty&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])
  }

  def placeTrailingStopOrderAsync(qty: BigDecimal, side: OrderSide, pegOffsetValue: BigDecimal, isClose: Boolean, execInst: String="LastPrice", clOrdID: Option[String]=None): Future[Order] = {
    val pegOffsetValue2 = if (side == OrderSide.Buy) pegOffsetValue.abs else -pegOffsetValue.abs
    val closeStr = if (isClose) ",Close" else ""
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Stop&timeInForce=GoodTillCancel&pegPriceType=TrailingStopPeg&pegOffsetValue=$pegOffsetValue2&execInst=$execInst$closeStr&orderQty=$qty&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])
  }

  def placeMarketOrderAsync(qty: BigDecimal, side: OrderSide, clOrdID: Option[String]=None): Future[Order] =
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Market&timeInForce=GoodTillCancel&orderQty=$qty&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  def placeLimitOrderAsync(qty: BigDecimal, price: BigDecimal, reduceOnly: Boolean, side: OrderSide, clOrdID: Option[String]=None): Future[Order] = {
    val reduceOnlyStr = if (reduceOnly) ",ReduceOnly" else ""
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate$reduceOnlyStr&orderQty=$qty&side=$side&price=$price" + clOrdID.map("&clOrdID=" + _).getOrElse(""),
    ).map(_.asInstanceOf[Order])
  }

  def placeBulkOrdersAsync(orderReqs: OrderReqs): Future[Orders] =
    // https://www.reddit.com/r/BitMEX/comments/gmultr/webserver_disconnects_after_placing_order/
    sendReq(
      POST,
      "/api/v1/order/bulk",
      Json.stringify(Json.toJson[OrderReqs](OrderReqs(orderReqs.orders.map(_.copy(symbol=Some(symbol)))))),
      contentType=ContentTypes.`application/json`,
    ).map(_.asInstanceOf[Orders])

  def amendOrderAsync(orderID: Option[String], cliOrdID: Option[String], price: BigDecimal): Future[Order] =
    sendReq(
      PUT,
      "/api/v1/order",
      s"price=$price" + orderID.map("&orderID=" + _).getOrElse("") + cliOrdID.map("&origClOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  def cancelOrderAsync(orderID: Option[Seq[String]], cliOrdID: Option[Seq[String]]): Future[Orders] =
    sendReq(
      DELETE,
      "/api/v1/order",
      orderID.map("&orderID=" + _.mkString(",")).getOrElse("") + cliOrdID.map("&clOrdID=" + _.mkString(",")).getOrElse("")
    ).map(_.asInstanceOf[Orders])

  private def sendReq(method: HttpMethod, urlPath: String, data: => String, contentType: ContentType.NonBinary=ContentTypes.`application/x-www-form-urlencoded`): Future[RestModel] = {
    val expiry = System.currentTimeMillis / 1000 + 100 //should be 15
    val keyString = s"${method.value}${urlPath}${expiry}${data}"
    val apiSignature = getBitmexApiSignature(keyString, apiSecret)

    val request = HttpRequest(method=method, uri=url + urlPath)
      .withEntity(contentType, data)
      .withHeaders(
        RawHeader("api-expires",   expiry.toString),
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
        case HttpResponse(s@StatusCodes.Forbidden, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
            b => Future.failed(BackoffRequiredError(s"Forbidden, assuming due to excessive requests: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
          }
        case HttpResponse(s@StatusCodes.BadRequest, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
            b =>
              val bStr = b.utf8String
              if (bStr.contains("Account has insufficient Available Balance"))
                Future.failed(AccountHasInsufficientBalanceError(s"BadRequest: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: $bStr"))
              else if (bStr.contains("Invalid ordStatus"))
                Future.failed(InvalidOrdStatusError(s"BadRequest: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: $bStr"))
              else
                Future.failed(new Exception(s"BadRequest: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
          }
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

sealed trait RecoverableError
case class TemporarilyUnavailableError(msg: String) extends Exception(msg) with RecoverableError
case class TimeoutError(msg: String) extends Exception(msg) with RecoverableError

case class BackoffRequiredError(msg: String) extends Exception(msg)

case class AccountHasInsufficientBalanceError(msg: String) extends Exception(msg)

sealed trait IgnorableError
case class InvalidOrdStatusError(msg: String) extends Exception(msg) with IgnorableError
