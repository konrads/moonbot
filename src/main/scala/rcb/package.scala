import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import play.api.libs.json.Json

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
    implicit val aFormat = Json.formatEnum(this)
  }

  object OrderStatus extends Enumeration {
    type OrderStatus = Value
    val New, Canceled, Filled, Rejected, PartiallyFilled, Expired, Stopped = Value
    implicit val aFormat = Json.formatEnum(this)
  }

  object Sentiment extends Enumeration {
    type Sentiment = Value
    val Bull, Bear, Neutral = Value
    implicit val aFormat = Json.formatEnum(this)
  }

  object OrderLifecycle extends Enumeration {
    type OrderLifecycle = Value
    val New, Canceled, PostOnlyFailure, Filled, Unknown = Value
    implicit val aFormat = Json.formatEnum(this)
  }
}
