
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{Json, Reads}


package object moon {
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

  object OrderType extends Enumeration {
    type OrderType = Value
    val Limit, Stop, Market, StopLimit, MarketIfTouched, LimitIfTouched = Value
    implicit val aFormat = Json.formatEnum(this)
  }

  object OrderStatus extends Enumeration {
    type OrderStatus = Value
    val New, Canceled, Filled, Rejected, PartiallyFilled, Expired, Stopped, PostOnlyFailure /* synthetic status! */ = Value
    implicit val aFormat = Json.formatEnum(this)
  }

  object TickDirection extends Enumeration {
    type TickDirection = Value
    val MinusTick, ZeroMinusTick, PlusTick, ZeroPlusTick = Value
    implicit val aFormat = Json.formatEnum(this)
  }

  object Sentiment extends Enumeration {
    type Sentiment = Value
    val Bull, Bear, Neutral = Value
    implicit val aFormat = Json.formatEnum(this)
  }

  val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(DateTimeZone.UTC)

  def parseDateTime(asStr: String) = DateTime.parse(asStr, dateFormat)

  implicit val jodaDateReads = Reads[DateTime](js => js.validate[String].map[DateTime](parseDateTime))
}
