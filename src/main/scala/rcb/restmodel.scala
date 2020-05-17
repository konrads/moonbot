package rcb

import play.api.libs.json._
import play.api.libs.json.Reads._

import scala.runtime.ScalaRunTime


sealed trait RestModel

case class Order(orderID: String, clOrdID: Option[String], symbol: String, ordType: String, side: String, price: Option[BigDecimal], stopPx: Option[BigDecimal], orderQty: BigDecimal, ordStatus: Option[String], workingIndicator: Option[Boolean], text: Option[String]) extends RestModel {
  lazy val lifecycle = (ordStatus, text) match {
    // case (Some("New"), Some(false), _) => OrderLifecycle.NewInactive // immaterial if active or inactive, might change to just New ...
    case (Some("New"),  _)     => OrderLifecycle.New
    case (Some("Canceled"), Some(cancelMsg)) if cancelMsg.contains("had execInst of ParticipateDoNotInitiate") => OrderLifecycle.PostOnlyFailure
    case (Some("Canceled"), _) => OrderLifecycle.Canceled
    case (Some("Filled"), _)   => OrderLifecycle.Filled
    case _                     => OrderLifecycle.Unknown
  }

  override def toString = s"${ScalaRunTime._toString(this)} { lifecycle = $lifecycle }"
}
object Order { implicit val aReads: Reads[Order] = Json.reads[Order] }

case class Orders(orders: Seq[Order]) extends RestModel
object Orders { implicit val aReads: Reads[Orders] = Json.reads[Orders] }

case class ErrorDetail(message: String, name: String) extends RestModel
object ErrorDetail { implicit val aReads: Reads[ErrorDetail] = Json.reads[ErrorDetail] }

case class Error(error: ErrorDetail) extends RestModel
object Error { implicit val aReads: Reads[Error] = Json.reads[Error] }


object RestModel {
  implicit val aReads: Reads[RestModel] = (json: JsValue) => {
    // println(s"#### rest json: $json")
    json match {
      case arr@JsArray(_) => ((arr(0) \ "orderID").asOpt[String]) match {
        case Some(_) => arr.validate[List[Order]].map(x => Orders(x))
        case _ => JsError(s"Unknown json array '$json'")
      }
      case _ => (json \ "orderID").asOpt[String] match {
        case Some(_) => json.validate[Order]
        case None    => (json \ "error").asOpt[JsValue] match {
          case Some(_) => json.validate[Error]
          case None    => JsError(s"Unknown json '$json'")
        }
      }
    }
  }

  def asModel(jsonStr: String): JsResult[RestModel] = {
    val parsedJsValue = Json.parse(jsonStr)
    Json.fromJson[RestModel](parsedJsValue)
  }
}
