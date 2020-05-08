package rcb

import play.api.libs.json._
import play.api.libs.json.Reads._


sealed trait RestModel

case class Order(orderID: String, symbol: String, ordType: String, side: String, price: BigDecimal, orderQty: BigDecimal) extends RestModel
object Order { implicit val aReads: Reads[Order] = Json.reads[Order] }

case class ErrorDetail(message: String, name: String) extends RestModel
object ErrorDetail { implicit val aReads: Reads[ErrorDetail] = Json.reads[ErrorDetail] }

case class Error(error: ErrorDetail) extends RestModel
object Error { implicit val aReads: Reads[Error] = Json.reads[Error] }


object RestModel {
  implicit val aReads: Reads[RestModel] = (json: JsValue) =>
    (json \ "orderID").asOpt[String] match {
      case Some(_) => json.validate[Order]
      case None    => (json \ "error").asOpt[JsValue] match {
        case Some(_) => json.validate[Error]
        case None    => JsError(s"Unknown json '$json'")
      }
    }

  def asModel(jsonStr: String): JsResult[RestModel] = {
    val parsedJsValue = Json.parse(jsonStr)
    Json.fromJson[RestModel](parsedJsValue)
  }
}
