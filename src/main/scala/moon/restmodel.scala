package moon

import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import play.api.libs.json.Reads._
import moon.OrderSide.OrderSide
import org.joda.time.DateTime
import moon.jodaDateReads


sealed trait RestModel

case class Order(orderID: String, clOrdID: Option[String]=None, symbol: String, timestamp: DateTime, ordType: OrderType.Value, side: OrderSide.Value, price: Option[BigDecimal]=None, stopPx: Option[BigDecimal]=None, orderQty: BigDecimal, ordStatus: Option[OrderStatus.Value]=None, workingIndicator: Option[Boolean]=None, ordRejReason: Option[String]=None, text: Option[String]=None) extends RestModel
object Order { implicit val aReads: Reads[Order] = Json.reads[Order] }

case class Orders(orders: Seq[Order]) extends RestModel
object Orders { implicit val aReads: Reads[Orders] = Json.reads[Orders] }

case class ErrorDetail(message: String, name: String) extends RestModel
object ErrorDetail { implicit val aReads: Reads[ErrorDetail] = Json.reads[ErrorDetail] }

case class Error(error: ErrorDetail) extends RestModel
object Error { implicit val aReads: Reads[Error] = Json.reads[Error] }


case class OrderReq(orderQty: BigDecimal, side: OrderSide, ordType: OrderType.Value, symbol: Option[String]=None,
                    execInst: Option[String]=None, price: Option[BigDecimal]=None, stopPx: Option[BigDecimal]=None,
                    clOrdID: Option[String]=None, timeInForce: String="GoodTillCancel")
object OrderReq {
  implicit val aWrites: Writes[OrderReq] = Json.writes[OrderReq]

  def asStopMarketOrder(side: OrderSide, orderQty: BigDecimal, price: BigDecimal, clOrdID: Option[String]=None) =
    OrderReq(ordType=OrderType.Stop, side=side, orderQty=orderQty, execInst=Some("LastPrice"), stopPx=Some(price), clOrdID=clOrdID)

  def asMarketOrder(side: OrderSide, orderQty: BigDecimal, clOrdID: Option[String]=None) =
    OrderReq(ordType=OrderType.Market, side=side, orderQty=orderQty, clOrdID=clOrdID)

  def asLimitOrder(side: OrderSide, orderQty: BigDecimal, price: BigDecimal, clOrdID: Option[String]=None) =
    OrderReq(ordType=OrderType.Limit, side=side, orderQty=orderQty, execInst=Some("ParticipateDoNotInitiate"), price=Some(price), clOrdID=clOrdID)
}

case class OrderReqs(orders: Seq[OrderReq])
object OrderReqs {
  implicit val aWrites: Writes[OrderReqs] = Json.writes[OrderReqs]
}

object RestModel {
  private val log = Logger("RestModel")
  implicit val aReads: Reads[RestModel] = (json: JsValue) => {
    log.debug(s"#### rest json: $json")
    val res = json match {
      case arr@JsArray(_) => (arr(0) \ "orderID").asOpt[String] match {
        case Some(_) => arr.validate[List[Order]].map(x => Orders(x))
        case _ => JsError(s"Unknown json array '$json'")
      }
      case _ => (json \ "orderID").asOpt[String] match {
        case Some(_) => json.validate[Order]
          .map(o => o.copy(ordStatus =
            if (o.ordStatus.contains(OrderStatus.Canceled) && o.text.exists(_.contains("had execInst of ParticipateDoNotInitiate")))
              Some(OrderStatus.PostOnlyFailure)
            else
              o.ordStatus))
        case None    => (json \ "error").asOpt[JsValue] match {
          case Some(_) => json.validate[Error]
          case None    => JsError(s"Unknown json '$json'")
        }
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
