package rcb

import play.api.libs.json._
import play.api.libs.json.Reads._

import scala.runtime.ScalaRunTime

object OrderLifecycle extends Enumeration {
  type OrderLifecycle = Value
  val NewInactive, NewActive, Canceled, PostOnlyFailure, Filled, Open = Value
}

sealed trait WsModel

case class Info(info: String, version: String, timestamp: String, docs: String) extends WsModel
object Info { implicit val aReads: Reads[Info] = Json.reads[Info] }

case class Success(success: Boolean, subscribe: Option[String]) extends WsModel
object Success { implicit val aFmt: Reads[Success] = Json.reads[Success] }

case class OrderBookData(symbol: String, asks: Seq[Seq[BigDecimal]], bids: Seq[Seq[BigDecimal]]) extends WsModel
object OrderBookData { implicit val aFmt: Reads[OrderBookData] = Json.reads[OrderBookData] }

case class OrderBook(table: String, action: String, data: Seq[OrderBookData]) extends WsModel
object OrderBook { implicit val aFmt: Reads[OrderBook] = Json.reads[OrderBook] }

case class OrderData(orderID: String, price: Option[BigDecimal], ordStatus: Option[String], leavesQty: Option[BigDecimal], cumQty: Option[BigDecimal], workingIndicator: Option[Boolean], text: Option[String]) extends WsModel {
  lazy val lifecycle = (ordStatus, workingIndicator, text) match {
    case (Some("New"), Some(false), _) => OrderLifecycle.NewInactive
    case (None, Some(true), _)         => OrderLifecycle.NewActive
    case (Some("Canceled"), _, Some(cancelMsg)) if cancelMsg.contains("had execInst of ParticipateDoNotInitiate") => OrderLifecycle.PostOnlyFailure
    case (Some("Canceled"), _, _)      => OrderLifecycle.Canceled
    case (Some("Filled"), _, _)        => OrderLifecycle.Filled
    case _ => OrderLifecycle.Open
  }

  override def toString = s"${ScalaRunTime._toString(this)} { lifecycle = $lifecycle }"
}
object OrderData { implicit val aFmt: Reads[OrderData] = Json.reads[OrderData] }

case class UpdatedOrder(action: Option[String], data: Seq[OrderData]) extends WsModel
object UpdatedOrder { implicit val aFmt: Reads[UpdatedOrder] = Json.reads[UpdatedOrder] }

case class InsertOrder(action: Option[String], data: Seq[OrderData]) extends WsModel
object InsertOrder { implicit val aFmt: Reads[InsertOrder] = Json.reads[InsertOrder] }

case class WsError(status: Int, error: String) extends WsModel
object WsError { implicit val aFmt: Reads[WsError] = Json.reads[WsError] }

case class Ignorable(jsVal: JsValue) extends WsModel
object Ignorable { implicit val aFmt: Reads[Ignorable] = Json.reads[Ignorable] }

object WsModel {
  implicit val aReads: Reads[WsModel] = (json: JsValue) => {
    // println(s"#### ws json: $json")
    ((json \ "table").asOpt[String], (json \ "action").asOpt[String]) match {
      case (Some(table), _@Some(_)) if table.startsWith("orderBook") => json.validate[OrderBook]
      case (Some("order"), Some("insert")) => json.validate[InsertOrder]
      case (Some("order"), Some("update")) => json.validate[UpdatedOrder]
      case (Some(table), Some("partial")) if Seq("order", "funding").contains(table) => JsSuccess(Ignorable(json))
      case _ => (json \ "success").asOpt[Boolean] match {
        case Some(_) => json.validate[Success]
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
