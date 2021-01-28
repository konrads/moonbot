package moon

import com.typesafe.scalalogging.Logger
import moon.OrderStatus._
import org.joda.time.DateTime
import play.api.libs.json.Reads._
import play.api.libs.json._
import moon.jodaDateReads


sealed trait WsModel

case class Info(info: String, version: String, timestamp: DateTime, docs: String) extends WsModel
object Info { implicit val aReads: Reads[Info] = Json.reads[Info] }

case class SuccessConfirmation(success: Boolean, subscribe: Option[String]) extends WsModel
object SuccessConfirmation { implicit val aReads: Reads[SuccessConfirmation] = Json.reads[SuccessConfirmation] }

case class OrderBookData(symbol: String, timestamp: DateTime, asks: Seq[Seq[Double]], bids: Seq[Seq[Double]]) extends WsModel
object OrderBookData { implicit val aReads: Reads[OrderBookData] = Json.reads[OrderBookData] }

case class OrderBook(table: String, action: String, data: Seq[OrderBookData]) extends WsModel {
  def summary = OrderBookSummary(table = table, timestamp = data.head.timestamp, ask = data.head.asks.map(_.head).min, bid = data.head.bids.map(_.head).max)
}
object OrderBook { implicit val aReads: Reads[OrderBook] = Json.reads[OrderBook] }

case class OrderBookSummary(table: String, timestamp: DateTime, ask: Double, bid: Double) extends WsModel {
  def isEquivalent(that: OrderBookSummary): Boolean = that != null && that.ask == ask && that.bid == bid
}

case class OrderData(symbol: String, orderID: String, clOrdID: Option[String]=None, price: Option[Double]=None, stopPx: Option[Double]=None, avgPx: Option[Double]=None, orderQty: Option[Double], ordType: Option[OrderType.Value]=None, ordStatus: Option[OrderStatus.Value]=None, timestamp: DateTime, leavesQty: Option[Double]=None, cumQty: Option[Double]=None, side: Option[OrderSide.Value], workingIndicator: Option[Boolean]=None, ordRejReason: Option[String]=None, text: Option[String]=None, amended: Option[Boolean]=None, relatedClOrdID: Option[String]=None /*synthetic*/, tier: Option[Int]=None /*synthetic*/) extends WsModel
object OrderData { implicit val aReads: Reads[OrderData] = Json.reads[OrderData] }

case class Instrument(data: Seq[InstrumentData]) extends WsModel
object Instrument { implicit val aReads: Reads[Instrument] = Json.reads[Instrument] }

case class InstrumentData(symbol: String, lastPrice: Option[Double], lastChangePcnt: Option[Double], markPrice: Option[Double], prevPrice24h: Option[Double], timestamp: DateTime) extends WsModel
object InstrumentData { implicit val aReads: Reads[InstrumentData] = Json.reads[InstrumentData] }

case class Funding(data: Seq[FundingData]) extends WsModel
object Funding { implicit val aReads: Reads[Funding] = Json.reads[Funding] }

case class FundingData(symbol: String, fundingInterval: String, fundingRate: Double, fundingRateDaily: Option[Double], timestamp: DateTime) extends WsModel
object FundingData { implicit val aReads: Reads[FundingData] = Json.reads[FundingData] }

case class UpsertOrder(action: Option[String], data: Seq[OrderData]) extends WsModel {
  def containsTexts(texts: String*): Boolean = data.exists(o => o.text.exists(texts.contains))
  def containsClOrdIDs(clOrdIDs: String*): Boolean = data.exists(o => o.clOrdID.exists(clOrdIDs.contains))
  def containsOrderIDs(orderIDs: String*): Boolean = data.exists(o => orderIDs.contains(o.orderID))
  def containsAmendedOrderIDs(orderIDs: String*): Boolean = data.exists(o => orderIDs.contains(o.orderID) && o.amended.contains(true))
}
object UpsertOrder { implicit val aReads: Reads[UpsertOrder] = Json.reads[UpsertOrder] }

case class TradeData(symbol: String, side: OrderSide.Value, size: Double, price: Double, tickDirection: TickDirection.Value, timestamp: DateTime) extends WsModel
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
    // if (! (json \ "table").asOpt[String].exists(_.startsWith("orderBook")))
    //   log.debug(s"### ws json: $json")
    // println(s"### ws json: $json")
    val res = ((json \ "table").asOpt[String], (json \ "action").asOpt[String]) match {
      case (Some(table), Some("partial")) if Vector("order", "trade", "instrument", "funding").contains(table) => JsSuccess(Ignorable(json))
      case (Some(table), _@Some(_)) if table.startsWith("orderBook") => json.validate[OrderBook]
      case (Some(table), _@Some(_)) if table == "instrument" => json.validate[Instrument]
      case (Some(table), _@Some(_)) if table == "funding" => json.validate[Funding]
      case (Some("order"), _) => json.validate[UpsertOrder]
        .map(o => o.copy(data = o.data.map(od => od.copy(
          ordStatus =
            if (od.ordStatus.contains(Canceled) && od.text.exists(_.contains("had execInst of ParticipateDoNotInitiate")))
              Some(PostOnlyFailure)
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

  def bySymbol[T <: WsModel](model: T): Map[String, T] = {
    def rejig[U](s: Seq[Map[String, U]]): Map[String, Seq[U]] = {
      s.foldLeft(Map.empty[String, Seq[U]]) {
        case (soFar, x) =>
          x.foldLeft(soFar) { case (soFar2, (k, v)) => soFar2.get(k) match {
            case Some(xs) => soFar2 + (k -> (xs :+ v))
            case None     => soFar2 + (k -> Vector(v))
          }}
      }
    }
    model match {
      case x:OrderBook      => rejig(x.data.map(bySymbol)).mapValues(ds => x.copy(data = ds).asInstanceOf[T]).toMap
      case x:OrderBookData  => Map(x.symbol -> x.asInstanceOf[T])
      case x:UpsertOrder    => rejig(x.data.map(bySymbol)).mapValues(ds => x.copy(data = ds).asInstanceOf[T]).toMap
      case x:OrderData      => Map(x.symbol -> x.asInstanceOf[T])
      case x:Instrument     => rejig(x.data.map(bySymbol)).mapValues(ds => x.copy(data = ds).asInstanceOf[T]).toMap
      case x:InstrumentData => Map(x.symbol -> x.asInstanceOf[T])
      case x:Funding        => rejig(x.data.map(bySymbol)).mapValues(ds => x.copy(data = ds).asInstanceOf[T]).toMap
      case x:FundingData    => Map(x.symbol -> x.asInstanceOf[T])
      case x:TradeData      => Map(x.symbol -> x.asInstanceOf[T])
      case x:Trade          => rejig(x.data.map(bySymbol)).mapValues(ds => x.copy(data = ds).asInstanceOf[T]).toMap
      case other            => Map("other" -> other)
    }
  }
}
