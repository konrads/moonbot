import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import play.api.libs.json.{JsValue, Reads, __}

package object rcb {
  def getBitmexApiSignature(keyString: String, apiSecret: String): String = {
    val sha256HMAC = Mac.getInstance("HmacSHA256")
    val secretKey = new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA256")
    sha256HMAC.init(secretKey)
    val hash = DatatypeConverter.printHexBinary(sha256HMAC.doFinal(keyString.getBytes))
    hash
  }

  object OrderSide extends Enumeration {
    type OrderSide = Value
    val Buy, Sell = Value
    implicit val aReads: Reads[OrderSide.Value] = (json: JsValue) => json.validate[String].map(OrderSide.withName _)
  }

  object OrderStatus extends Enumeration {
    type OrderStatus = Value
    val New, Canceled, Filled, Rejected, PartiallyFilled, Expired, Stopped = Value
    implicit val aReads: Reads[OrderStatus.Value] = (json: JsValue) => json.validate[String].map(OrderStatus.withName _)
  }

  object Sentiment extends Enumeration {
    type Sentiment = Value
    val Bull, Bear, Neutral = Value
  }

  object OrderLifecycle extends Enumeration {
    type OrderLifecycle = Value
    val New, Canceled, PostOnlyFailure, Filled, Unknown = Value
  }
}
