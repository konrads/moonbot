package rcb

import play.api.libs.json._
import play.api.libs.json.Reads._

import scala.runtime.ScalaRunTime

sealed trait WsModel

case class Info(info: String, version: String, timestamp: String, docs: String) extends WsModel
object Info { implicit val aReads: Reads[Info] = Json.reads[Info] }

case class SuccessConfirmation(success: Boolean, subscribe: Option[String]) extends WsModel
object SuccessConfirmation { implicit val aFmt: Reads[SuccessConfirmation] = Json.reads[SuccessConfirmation] }

case class OrderBookData(symbol: String, timestamp: String, asks: Seq[Seq[BigDecimal]], bids: Seq[Seq[BigDecimal]]) extends WsModel
object OrderBookData { implicit val aFmt: Reads[OrderBookData] = Json.reads[OrderBookData] }

case class OrderBook(table: String, action: String, data: Seq[OrderBookData]) extends WsModel
object OrderBook { implicit val aFmt: Reads[OrderBook] = Json.reads[OrderBook] }

case class OrderData(orderID: String, clOrdID: Option[String]=None, price: Option[BigDecimal]=None, orderQty: BigDecimal, ordStatus: Option[OrderStatus.Value]=None, timestamp: String, leavesQty: Option[BigDecimal]=None, cumQty: Option[BigDecimal]=None, side: OrderSide.Value, workingIndicator: Option[Boolean]=None, text: Option[String]=None) extends WsModel {
  lazy val lifecycle = (ordStatus, text) match {
    case (Some(OrderStatus.New), _)      => OrderLifecycle.New
    case (Some(OrderStatus.Canceled), Some(cancelMsg)) if cancelMsg.contains("had execInst of ParticipateDoNotInitiate") => OrderLifecycle.PostOnlyFailure
    case (Some(OrderStatus.Canceled), _) => OrderLifecycle.Canceled
    case (Some(OrderStatus.Filled), _)   => OrderLifecycle.Filled
    case _                     => OrderLifecycle.Unknown
  }

  override def toString = s"${ScalaRunTime._toString(this)} { lifecycle = $lifecycle }"
}
object OrderData { implicit val aFmt: Reads[OrderData] = Json.reads[OrderData] }

case class UpsertOrder(action: Option[String], data: Seq[OrderData]) extends WsModel
object UpsertOrder { implicit val aFmt: Reads[UpsertOrder] = Json.reads[UpsertOrder] }

case class TradeData(side: OrderSide.Value, size: Int, price: BigDecimal, tickDirection:String, timestamp: String) extends WsModel
object TradeData { implicit val aFmt: Reads[TradeData] = Json.reads[TradeData] }

case class Trade(data: Seq[TradeData]) extends WsModel
object Trade { implicit val aFmt: Reads[Trade] = Json.reads[Trade] }

case class WsError(status: Int, error: String) extends WsModel
object WsError { implicit val aFmt: Reads[WsError] = Json.reads[WsError] }

case class Ignorable(jsVal: JsValue) extends WsModel
object Ignorable { implicit val aFmt: Reads[Ignorable] = Json.reads[Ignorable] }

object WsModel {
  implicit val aReads: Reads[WsModel] = (json: JsValue) => {
    // println(s"#### ws json: $json")
    ((json \ "table").asOpt[String], (json \ "action").asOpt[String]) match {
      case (Some(table), _@Some(_)) if table.startsWith("orderBook") => json.validate[OrderBook]
      case (Some("order"), _) => json.validate[UpsertOrder]
      case (Some("trade"), Some("insert")) => json.validate[Trade]
      case (Some(table), Some("partial")) if Seq("order", "trade").contains(table) => JsSuccess(Ignorable(json))
      case _ => (json \ "success").asOpt[Boolean] match {
        case Some(_) => json.validate[SuccessConfirmation]
        case None => ((json \ "status").asOpt[Int], (json \ "error").asOpt[String]) match {
          case (Some(_), Some(_)) => json.validate[WsError]
          case _ => (json \ "info").asOpt[String] match {
            case Some(_) => json.validate[Info]
            case None => JsError(s"Unknown json '$json'")
          }
        }
      }
    }
  }

  def asModel(jsonStr: String): JsResult[WsModel] = {
    val parsedJsValue = Json.parse(jsonStr)
    Json.fromJson[WsModel](parsedJsValue)
  }
}
