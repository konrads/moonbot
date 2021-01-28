package moon

import com.typesafe.scalalogging.Logger
import moon.OrderSide.OrderSide
import moon.OrderStatus._
import org.joda.time.DateTime
import play.api.libs.json.Reads._
import play.api.libs.json._
import moon.jodaDateReads


sealed trait RestModel

case class Position(account: Long, symbol: String, currency: String, leverage: Option[Double], crossMargin: Option[Boolean], rebalancedPnl: Double, currentQty: Double, avgCostPrice: Option[Double], breakEvenPrice: Option[Double], marginCallPrice: Option[Double], liquidationPrice: Option[Double], bankruptPrice: Option[Double]) extends RestModel
object Position { implicit val aReads: Reads[Position] = Json.reads[Position] }

case class Order(orderID: String, clOrdID: Option[String]=None, symbol: String, timestamp: DateTime, ordType: OrderType.Value, side: OrderSide.Value, price: Option[Double]=None, stopPx: Option[Double]=None, avgPx: Option[Double]=None, orderQty: Double, ordStatus: Option[OrderStatus.Value]=None, workingIndicator: Option[Boolean]=None, ordRejReason: Option[String]=None, text: Option[String]=None, amended: Option[Boolean]=None, relatedClOrdID: Option[String]=None /*synthetic*/, tier: Option[Int]=None /*synthetic*/) extends RestModel
object Order { implicit val aReads: Reads[Order] = Json.reads[Order] }

case class Positions(positions: Seq[Position]) extends RestModel
object Positions { implicit val aReads: Reads[Positions] = Json.reads[Positions] }

case class HealthCheckOrders(orders: Seq[Order]) extends RestModel {
  def containsOrderIDs(orderIDs: String*): Boolean = orders.exists(o => orderIDs.contains(o.orderID))
  def containsClOrdIDs(clOrdIDs: String*): Boolean = orders.exists(o => o.clOrdID.exists(clOrdIDs.contains))
}

case class Orders(orders: Seq[Order]) extends RestModel {
  def containsOrderIDs(orderIDs: String*): Boolean = orders.exists(o => orderIDs.contains(o.orderID))
  def containsClOrdIDs(clOrdIDs: String*): Boolean = orders.exists(o => o.clOrdID.exists(clOrdIDs.contains))
}
object Orders { implicit val aReads: Reads[Orders] = Json.reads[Orders] }

case class ErrorDetail(message: String, name: String) extends RestModel
object ErrorDetail { implicit val aReads: Reads[ErrorDetail] = Json.reads[ErrorDetail] }

case class Error(error: ErrorDetail) extends RestModel
object Error { implicit val aReads: Reads[Error] = Json.reads[Error] }


case class OrderReq(orderQty: Double, side: OrderSide, ordType: OrderType.Value, symbol: Option[String]=None,
                    execInst: Option[String]=None, price: Option[Double]=None, stopPx: Option[Double]=None,
                    pegOffsetValue: Option[Double]=None, pegPriceType: Option[String]=None,
                    clOrdID: Option[String]=None, timeInForce: String="GoodTillCancel")
object OrderReq {
  implicit val aWrites: Writes[OrderReq] = Json.writes[OrderReq]

  def asStopOrder(symbol: String, side: OrderSide, orderQty: Double, price: Double, isClose:Boolean, clOrdID: Option[String]) = {
    val closeStr = if (isClose) ",Close" else ""
    OrderReq(symbol=Some(symbol), ordType=OrderType.Stop, side=side, orderQty=orderQty, execInst=Some("LastPrice" + closeStr), stopPx=Some(price), clOrdID=clOrdID)
  }

  def asTrailingStopOrder(symbol: String, side: OrderSide, orderQty: Double, pegOffsetValue: Double, isClose:Boolean, clOrdID: Option[String]) = {
    val pegOffsetValue2 = if (side == OrderSide.Buy) pegOffsetValue.abs else -pegOffsetValue.abs
    val closeStr = if (isClose) ",Close" else ""
    OrderReq(ordType=OrderType.Stop, side=side, orderQty=orderQty, execInst=Some("LastPrice" + closeStr), pegPriceType=Some("TrailingStopPeg"), pegOffsetValue=Some(pegOffsetValue2), clOrdID=clOrdID)
  }

  def asMarketOrder(symbol: String, side: OrderSide, orderQty: Double, clOrdID: Option[String]) =
    OrderReq(symbol=Some(symbol), ordType=OrderType.Market, side=side, orderQty=orderQty, clOrdID=clOrdID)

  def asLimitOrder(symbol: String, side: OrderSide, orderQty: Double, price: Double, isReduceOnly: Boolean, clOrdID: Option[String]) = {
    val isReduceOnlyStr = if (isReduceOnly) ",ReduceOnly" else ""
    OrderReq(symbol=Some(symbol), ordType=OrderType.Limit, side=side, orderQty=orderQty, execInst=Some("ParticipateDoNotInitiate" + isReduceOnlyStr), price=Some(price), clOrdID=clOrdID)
  }
}

case class OrderReqs(orders: Seq[OrderReq])
object OrderReqs {
  implicit val aWrites: Writes[OrderReqs] = Json.writes[OrderReqs]
}

object RestModel {
  private val log = Logger("RestModel")
  def reclassifyOrdStatus(o: Order): Order = {
    o.copy(
      ordStatus =
        if (o.ordStatus.contains(Canceled) && o.text.exists(_.contains("had execInst of ParticipateDoNotInitiate")))
          Some(PostOnlyFailure)
        else
          o.ordStatus,
      amended = o.text.map(_.startsWith("Amended"))
    )
  }
  implicit val aReads: Reads[RestModel] = (json: JsValue) => {
    // log.debug(s"#### rest json: $json")
    val res = json match {
      case arr@JsArray(_) if arr.value.nonEmpty =>
        ((arr(0) \ "orderID").asOpt[String], (arr(0) \ "account").asOpt[Long]) match {
          case (Some(_), _) => arr.validate[List[Order]].map(x => Orders(x.map(reclassifyOrdStatus)))
          case (_, Some(_)) => arr.validate[List[Position]].map(x => Positions(x))
          case _ => JsError(s"Unknown json array '$json'")
        }
      case arr@JsArray(_) => arr.validate[List[Order]].map(x => Orders(x.map(reclassifyOrdStatus)))  // FIXME: default to list of Orders, assuming position will always be there...
      case _ => ((json \ "orderID").asOpt[String], (json \ "account").asOpt[Long], (json \ "error").asOpt[String]) match {
        case (Some(_), _, _) => json.validate[Order].map(reclassifyOrdStatus)
        case (_, Some(_), _) => json.validate[Position]
        case (_, _, Some(_)) => json.validate[Error]
        case _ => JsError(s"Unknown json '$json'")
      }
    }
    res match {
      case s:JsSuccess[_] => s
      case e:JsError => println(s".....Got RestModel unmarshal error:\njson: $json\nerror: $e"); e
    }
  }

  def asModel(jsonStr: String): JsResult[RestModel] = {
    val parsedJsValue = Json.parse(jsonStr)
    Json.fromJson[RestModel](parsedJsValue)
  }
}
