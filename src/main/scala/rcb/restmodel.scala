package rcb

import play.api.libs.json._
import play.api.libs.json.Reads._

sealed trait RestModel

case class Order(orderID: String, symbol: String, ordType: String, side: String, price: BigDecimal, orderQty: BigDecimal) extends RestModel
object Order { implicit val aReads: Reads[Order] = Json.reads[Order] }

object RestModel {
  implicit val aReads: Reads[RestModel] = (json: JsValue) =>
    (json \ "orderID").asOpt[String] match {
      case Some(_) => json.validate[Order]
      case None => JsError(s"Unknown json '$json'")
    }

  def asModel(jsonStr: String): JsResult[RestModel] = {
    val parsedJsValue = Json.parse(jsonStr)
    Json.fromJson[RestModel](parsedJsValue)
  }
}
