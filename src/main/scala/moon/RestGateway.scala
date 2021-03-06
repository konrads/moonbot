package moon

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import moon.OrderSide.OrderSide
import play.api.libs.json.{JsError, JsSuccess, Json}

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
  def placeStopOrderAsync(qty: Double, price: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Future[Order]
  def placeTrailingStopOrderAsync(qty: Double, pegOffsetValue: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Future[Order]
  def placeMarketOrderAsync(qty: Double, side: OrderSide, clOrdID: Option[String]): Future[Order]
  def placeLimitOrderAsync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide, clOrdID: Option[String]): Future[Order]
  def amendOrderAsync(orderID: Option[String]=None, origClOrdID: Option[String]=None, price: Double): Future[Order]
  def cancelOrderAsync(orderIDs: Seq[String]=Vector.empty, clOrdIDs: Seq[String]=Vector.empty): Future[Orders]
  def cancelAllOrdersAsync(): Future[Orders]
  def closePositionAsync(): Future[String]

  // sync
  def placeBulkOrdersSync(orderReqs: OrderReqs): Try[Orders]
  def placeStopOrderSync(qty: Double, price: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Try[Order]
  def placeTrailingStopOrderSync(qty: Double, pegOffsetValue: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Try[Order]
  def placeMarketOrderSync(qty: Double, side: OrderSide, clOrdID: Option[String]): Try[Order]
  def placeLimitOrderSync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide, clOrdID: Option[String]): Try[Order]
  def amendOrderSync(orderID: Option[String]=None, clOrdID: Option[String]=None, price: Double): Try[Order]
  def cancelOrderSync(orderIDs: Seq[String]=Vector.empty, origClOrdIDs: Seq[String]=Vector.empty): Try[Orders]
  def cancelAllOrdersSync(): Try[Orders]
  def closePositionSync(): Try[String]
}


class RestGateway(symbol: String = "XBTUSD", url: String, apiKey: String, apiSecret: String, syncTimeoutMs: Long)(implicit system: ActorSystem) extends IRestGateway {
  val MARKUP_INDUCING_ERROR = "Order had execInst of ParticipateDoNotInitiate"

  // FIXME: consider timeouts!
  // FIXME: keep alive, in ws?
  // FIXME: reconnections, in http and ws?

  // based on: https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  private val log = Logger[RestGateway]
  implicit val executionContext = system.dispatcher

  def placeBulkOrdersAsync(orderReqs: OrderReqs): Future[Orders] = placeBulkOrders(orderReqs)
  def placeStopOrderAsync(qty: Double, price: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Future[Order] = placeStopOrder(qty, side, price, isClose, clOrdID=clOrdID)
  def placeTrailingStopOrderAsync(qty: Double, pegOffsetValue: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Future[Order] = placeTrailingStopOrder(qty, side, pegOffsetValue, isClose, clOrdID=clOrdID)
  def placeMarketOrderAsync(qty: Double, side: OrderSide, clOrdID: Option[String]): Future[Order] = placeMarketOrder(qty, side, clOrdID=clOrdID)
  def placeLimitOrderAsync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide, clOrdID: Option[String]): Future[Order] = placeLimitOrder(qty, price, isReduceOnly, side, clOrdID=clOrdID)
  def amendOrderAsync(orderID: Option[String] = None, origClOrdID: Option[String] = None, price: Double): Future[Order] = amendOrder(orderID, origClOrdID, price)
  def cancelOrderAsync(orderIDs: Seq[String] = Vector.empty, clOrdIDs: Seq[String] = Vector.empty): Future[Orders] = cancelOrder(orderIDs, clOrdIDs)
  def cancelAllOrdersAsync(): Future[Orders] = cancelAllOrders()
  def closePositionAsync(): Future[String] = closePosition()

  // sync
  def placeBulkOrdersSync(orderReqs: OrderReqs): Try[Orders] =
    Await.ready(
      placeBulkOrders(orderReqs),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get // FIXME: not wrapping in recoverable error...

  def placeStopOrderSync(qty: Double, price: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Try[Order] =
    Await.ready(
      placeStopOrder(qty, side, price, isClose, clOrdID=clOrdID),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get // FIXME: not wrapping in recoverable error...

  def placeTrailingStopOrderSync(qty: Double, pegOffsetValue: Double, isClose: Boolean, side: OrderSide, clOrdID: Option[String]): Try[Order] =
    Await.ready(
      placeTrailingStopOrder(qty, side, pegOffsetValue, isClose, clOrdID=clOrdID),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get // FIXME: not wrapping in recoverable error...

  def placeMarketOrderSync(qty: Double, side: OrderSide, clOrdID: Option[String]): Try[Order] =
    Await.ready(
      placeMarketOrder(qty, side, clOrdID),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get // FIXME: not wrapping in recoverable error...

  def placeLimitOrderSync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide, clOrdID: Option[String]): Try[Order] =
    Await.ready(
      placeLimitOrder(qty, price, isReduceOnly, side, clOrdID=clOrdID),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).value.get // FIXME: not wrapping in recoverable error...

  def amendOrderSync(orderID: Option[String] = None, origClOrdID: Option[String] = None, price: Double): Try[Order] =
    Await.ready(
      amendOrder(orderID, origClOrdID, price),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).recoverWith { case exc: TimeoutException => throw TimeoutError(s"Timeout on amendOrderSync orderID: $orderID, origClOrdID: $origClOrdID") }.value.get

  def cancelOrderSync(orderIDs: Seq[String] = Vector.empty, clOrdIDs: Seq[String] = Vector.empty): Try[Orders] =
    Await.ready(
      cancelOrder(orderIDs, clOrdIDs),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).recoverWith { case exc: TimeoutException => throw TimeoutError(s"Timeout on cancelOrderSync orderID: ${orderIDs.mkString(", ")}, clOrdID: ${clOrdIDs.mkString(", ")}") }.value.get

  def cancelAllOrdersSync(): Try[Orders] =
    Await.ready(
      cancelAllOrders(),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).recoverWith { case exc: TimeoutException => throw TimeoutError(s"Timeout on cancelAllOrdersSync") }.value.get

  def closePositionSync(): Try[String] =
    Await.ready(
      closePosition(),
      Duration(syncTimeoutMs, MILLISECONDS)
    ).recoverWith { case exc: TimeoutException => throw TimeoutError(s"Timeout on closePosition") }.value.get

  // LastPrice trigger as described in: https://www.reddit.com/r/BitMEX/comments/8pi7j7/bitmex_api_how_to_switch_sl_trigger_to_last_price/
  private def placeStopOrder(qty: Double, side: OrderSide, price: Double, isClose: Boolean, execInst: String = "LastPrice", clOrdID: Option[String] = None): Future[Order] = {
    val closeStr = if (isClose) ",Close" else ""
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Stop&timeInForce=GoodTillCancel&execInst=$execInst$closeStr&stopPx=${round(price)}&orderQty=${round(qty)}&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])
  }

  private def placeTrailingStopOrder(qty: Double, side: OrderSide, pegOffsetValue: Double, isClose: Boolean, execInst: String = "LastPrice", clOrdID: Option[String] = None): Future[Order] = {
    val pegOffsetValue2 = if (side == OrderSide.Buy) pegOffsetValue.abs else -pegOffsetValue.abs
    val closeStr = if (isClose) ",Close" else ""
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Stop&timeInForce=GoodTillCancel&pegPriceType=TrailingStopPeg&pegOffsetValue=$pegOffsetValue2&execInst=$execInst$closeStr&orderQty=${round(qty)}&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])
  }

  private def placeMarketOrder(qty: Double, side: OrderSide, clOrdID: Option[String] = None): Future[Order] =
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Market&timeInForce=GoodTillCancel&orderQty=${round(qty)}&side=$side" + clOrdID.map("&clOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  private def placeLimitOrder(qty: Double, price: Double, reduceOnly: Boolean, side: OrderSide, clOrdID: Option[String] = None): Future[Order] = {
    val reduceOnlyStr = if (reduceOnly) ",ReduceOnly" else ""
    sendReq(
      POST,
      "/api/v1/order",
      s"symbol=$symbol&ordType=Limit&timeInForce=GoodTillCancel&execInst=ParticipateDoNotInitiate$reduceOnlyStr&orderQty=${round(qty)}&side=$side&price=${round(price)}" + clOrdID.map("&clOrdID=" + _).getOrElse(""),
    ).map(_.asInstanceOf[Order])
  }

  private def placeBulkOrders(orderReqs: OrderReqs): Future[Orders] =
  // https://www.reddit.com/r/BitMEX/comments/gmultr/webserver_disconnects_after_placing_order/
    sendReq(
      POST,
      "/api/v1/order/bulk",
      Json.stringify(Json.toJson[OrderReqs](OrderReqs(orderReqs.orders.map(_.copy(symbol = Some(symbol)))))),
      contentType = ContentTypes.`application/json`,
    ).map(_.asInstanceOf[Orders])

  private def amendOrder(orderID: Option[String], orgClOrdID: Option[String], price: Double): Future[Order] =
    sendReq(
      PUT,
      "/api/v1/order",
      s"price=${round(price)}" + orderID.map("&orderID=" + _).getOrElse("") + orgClOrdID.map("&origClOrdID=" + _).getOrElse("")
    ).map(_.asInstanceOf[Order])

  private def cancelOrder(orderIDs: Seq[String], clOrdIDs: Seq[String]): Future[Orders] = {
    assert(orderIDs.nonEmpty || clOrdIDs.nonEmpty)
    val orderIDsStr = if (orderIDs.nonEmpty) Vector("orderID=" + orderIDs.mkString(",")) else Vector.empty
    val clOrdIDsStr = if (clOrdIDs.nonEmpty) Vector("clOrdID=" + clOrdIDs.mkString(",")) else Vector.empty
    sendReq(
      DELETE,
      "/api/v1/order",
      (orderIDsStr ++ clOrdIDsStr).mkString("&")
    ).map(_.asInstanceOf[Orders])
  }

  private def cancelAllOrders(): Future[Orders] = {
    sendReq(
      DELETE,
      "/api/v1/order/all",
      ""
    ).map(_.asInstanceOf[Orders])
  }

  private def closePosition(): Future[String] =
    sendReqSimple(
      POST,
      "/api/v1/order/closePosition",
      s"symbol=$symbol"
    )

  private def sendReq(method: HttpMethod, urlPath: String, data: => String, contentType: ContentType.NonBinary = ContentTypes.`application/x-www-form-urlencoded`): Future[RestModel] =
    sendReqSimple(method, urlPath, data, contentType) flatMap {
      s =>
        RestModel.asModel(s) match {
          case JsSuccess(value, _) => Future.successful(value)
          case JsError(errors) => Future.failed(new Exception(s"Json parsing error: $errors"))
        }
    }
  private def sendReqSimple(method: HttpMethod, urlPath: String, data: => String, contentType: ContentType.NonBinary = ContentTypes.`application/x-www-form-urlencoded`): Future[String] = {
    val expiry = System.currentTimeMillis / 1000 + 100 //should be 15
    val keyString = s"${method.value}${urlPath}${expiry}${data}"
    val apiSignature = getBitmexApiSignature(keyString, apiSecret)

    val request = HttpRequest(method = method, uri = url + urlPath)
      .withEntity(contentType, data)
      .withHeaders(
        RawHeader("api-expires", expiry.toString),
        RawHeader("api-key", apiKey),
        RawHeader("api-signature", apiSignature))

    Http().singleRequest(request)
      .flatMap {
        case HttpResponse(StatusCodes.OK, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
        case HttpResponse(s@StatusCodes.Forbidden, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
            b => Future.failed(TemporarilyUnavailableError(s"Forbidden, assuming due to excessive requests: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
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
        case HttpResponse(s@StatusCodes.BadGateway, _headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
            b =>
              if (method == POST)
                Future.failed(TemporarilyUnavailableOnPostError(s"BadGateway(on POST): urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
              else
                Future.failed(TemporarilyUnavailableError(s"BadGateway: urlPath: $urlPath, reqData: $data, responseStatus: $s responseBody: ${b.utf8String}"))
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

sealed trait BackoffRequiredError
sealed trait RecoverableError
case class TemporarilyUnavailableError(msg: String) extends Exception(msg) with RecoverableError with BackoffRequiredError
case class TimeoutError(msg: String) extends Exception(msg) with RecoverableError

case class AccountHasInsufficientBalanceError(msg: String) extends Exception(msg)

sealed trait IgnorableError
case class InvalidOrdStatusError(msg: String) extends Exception(msg) with IgnorableError

case class TemporarilyUnavailableOnPostError(msg: String) extends Exception(msg)
