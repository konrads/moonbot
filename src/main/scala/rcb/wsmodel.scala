package rcb

import play.api.libs.json._
import play.api.libs.json.Reads._


sealed trait WsModel

case class Info(info: String, version: String, timestamp: String, docs: String) extends WsModel
object Info { implicit val aReads: Reads[Info] = Json.reads[Info] }

case class Success(success: Boolean, subscribe: Option[String]) extends WsModel
object Success { implicit val aFmt: Format[Success] = Json.format[Success] }

case class OrderBookData(symbol: String, asks: Seq[Seq[BigDecimal]], bids: Seq[Seq[BigDecimal]]) extends WsModel
object OrderBookData { implicit val aFmt: Format[OrderBookData] = Json.format[OrderBookData] }

case class OrderBook(table: String, action: String, data: Seq[OrderBookData]) extends WsModel
object OrderBook { implicit val aFmt: Format[OrderBook] = Json.format[OrderBook] }

case class OrderData(orderID: String, price: BigDecimal, ordStatus: String, leavesQty: BigDecimal, cumQty: BigDecimal) extends WsModel
object OrderData { implicit val aFmt: Format[OrderData] = Json.format[OrderData] }

case class UpdatedOrder(data: Seq[OrderData]) extends WsModel
object UpdatedOrder { implicit val aFmt: Format[UpdatedOrder] = Json.format[UpdatedOrder] }

case class WsError(status: Int, error: String) extends WsModel
object WsError { implicit val aFmt: Format[WsError] = Json.format[WsError] }

case class Ignorable(jsVal: JsValue) extends WsModel
object Ignorable { implicit val aFmt: Format[Ignorable] = Json.format[Ignorable] }

// case class Funding() extends WsModel

object WsModel {
  implicit val aReads: Reads[WsModel] = (json: JsValue) => {
    ((json \ "table").asOpt[String], (json \ "action").asOpt[String]) match {
      case (Some(table), _@Some(_)) if table.startsWith("orderBook") => json.validate[OrderBook]
      case (Some("order"), Some("update")) => json.validate[UpdatedOrder]
      case (Some(table), Some("partial")) if Seq("order", "funding").contains(table) => JsSuccess(Ignorable(json)) // JsResult.fromTry(Try(Ignorable(json)))  // FIXME: gotta be a better way for success
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
