package moon

import com.typesafe.scalalogging.Logger
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.json.Reads._
import moon.jodaDateReads


sealed trait WsModel

case class Info(info: String, version: String, timestamp: DateTime, docs: String) extends WsModel
object Info { implicit val aReads: Reads[Info] = Json.reads[Info] }

case class SuccessConfirmation(success: Boolean, subscribe: Option[String]) extends WsModel
object SuccessConfirmation { implicit val aReads: Reads[SuccessConfirmation] = Json.reads[SuccessConfirmation] }

case class OrderBookData(symbol: String, timestamp: DateTime, asks: Seq[Seq[BigDecimal]], bids: Seq[Seq[BigDecimal]]) extends WsModel
object OrderBookData { implicit val aReads: Reads[OrderBookData] = Json.reads[OrderBookData] }

case class OrderBook(table: String, action: String, data: Seq[OrderBookData]) extends WsModel
object OrderBook { implicit val aReads: Reads[OrderBook] = Json.reads[OrderBook] }

case class OrderData(orderID: String, clOrdID: Option[String]=None, price: Option[BigDecimal]=None, stopPx: Option[BigDecimal]=None, avgPx: Option[BigDecimal]=None, orderQty: Option[BigDecimal], ordType: Option[OrderType.Value]=None, ordStatus: Option[OrderStatus.Value]=None, timestamp: DateTime, leavesQty: Option[BigDecimal]=None, cumQty: Option[BigDecimal]=None, side: Option[OrderSide.Value], workingIndicator: Option[Boolean]=None, ordRejReason: Option[String]=None, text: Option[String]=None, amended: Option[Boolean]=None) extends WsModel
object OrderData { implicit val aReads: Reads[OrderData] = Json.reads[OrderData] }

case class Instrument(data: Seq[InstrumentData]) extends WsModel
object Instrument { implicit val aReads: Reads[Instrument] = Json.reads[Instrument] }

case class InstrumentData(symbol: String, lastPrice: Option[BigDecimal], lastChangePcnt: Option[BigDecimal], markPrice: Option[BigDecimal], prevPrice24h: Option[BigDecimal], timestamp: DateTime) extends WsModel
object InstrumentData { implicit val aReads: Reads[InstrumentData] = Json.reads[InstrumentData] }

case class Funding(data: Seq[FundingData]) extends WsModel
object Funding { implicit val aReads: Reads[Funding] = Json.reads[Funding] }

case class FundingData(symbol: String, fundingInterval: String, fundingRate: BigDecimal, fundingRateDaily: Option[BigDecimal], timestamp: DateTime) extends WsModel
object FundingData { implicit val aReads: Reads[FundingData] = Json.reads[FundingData] }

case class UpsertOrder(action: Option[String], data: Seq[OrderData]) extends WsModel {
  def containsOrderIDs(orderIDs: String*): Boolean = data.exists(o => orderIDs.contains(o.orderID))
  def containsAmendedOrderIDs(orderIDs: String*): Boolean = data.exists(o => orderIDs.contains(o.orderID) && o.amended.contains(true))
}
object UpsertOrder { implicit val aReads: Reads[UpsertOrder] = Json.reads[UpsertOrder] }

case class TradeData(side: OrderSide.Value, size: Int, price: BigDecimal, tickDirection: TickDirection.Value, timestamp: DateTime) extends WsModel
object TradeData { implicit val aReads: Reads[TradeData] = Json.reads[TradeData] }

case class Trade(data: Seq[TradeData]) extends WsModel
object Trade { implicit val aReads: Reads[Trade] = Json.reads[Trade] }

case class WsError(status: Int, error: String) extends WsModel
object WsError { implicit val aReads: Reads[WsError] = Json.reads[WsError] }

case class Ignorable(jsVal: JsValue) extends WsModel
object Ignorable { implicit val aReads: Reads[Ignorable] = Json.reads[Ignorable] }

object WsModel {
  private val log = Logger("WsModel")
  implicit val aReads: Reads[WsModel] = (json: JsValue) => {
    log.debug(s"### ws json: $json")
    val res = ((json \ "table").asOpt[String], (json \ "action").asOpt[String]) match {
      case (Some(table), Some("partial")) if Seq("order", "trade", "instrument", "funding").contains(table) => JsSuccess(Ignorable(json))
      case (Some(table), _@Some(_)) if table.startsWith("orderBook") => json.validate[OrderBook]
      case (Some(table), _@Some(_)) if table == "instrument" => json.validate[Instrument]
      case (Some(table), _@Some(_)) if table == "funding" => json.validate[Funding]
      case (Some("order"), _) => json.validate[UpsertOrder]
        .map(o => o.copy(data = o.data.map(od => od.copy(
          ordStatus =
            if (od.ordStatus.contains(OrderStatus.Canceled) && od.text.exists(_.contains("had execInst of ParticipateDoNotInitiate")))
              Some(OrderStatus.PostOnlyFailure)
            else
              od.ordStatus,
          amended = od.text.map(_.startsWith("Amended"))))))
      case (Some("trade"), Some("insert")) => json.validate[Trade]
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
    res match {
      case s:JsSuccess[_] => s
      case e:JsError => println(s".....Got WSModel unmarshal error:\njson: $json\nerror: $e"); e
    }
  }

  def asModel(jsonStr: String): JsResult[WsModel] = {
    val parsedJsValue = Json.parse(jsonStr)
    Json.fromJson[WsModel](parsedJsValue)
  }
}
