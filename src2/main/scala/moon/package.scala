
import com.typesafe.config.Config
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

  // adding "value" as per:
  // https://stackoverflow.com/questions/42275983/scala-how-to-define-an-enum-with-extra-attributes
  object Sentiment extends Enumeration {
    type Sentiment = Value
    val Bull = Value(1, "Bull")
    val Bear = Value(-1, "Bear")
    val Neutral = Value(0, "Neutral")
    implicit val aFormat = Json.formatEnum(this)
  }

  val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(DateTimeZone.UTC)

  def parseDateTime(asStr: String) = DateTime.parse(asStr, dateFormat)

  implicit val jodaDateReads = Reads[DateTime](js => js.validate[String].map[DateTime](parseDateTime))

  // For optional values:
  // https://stackoverflow.com/questions/52144157/providing-default-value-on-typesafe-config-getters
  implicit class RichConfig(val config: Config) extends AnyVal {
    import scala.jdk.CollectionConverters._
    def optString(path: String): Option[String]     = if (config.hasPath(path)) Some(config.getString(path)) else None
    def optBoolean(path: String): Option[Boolean]   = if (config.hasPath(path)) Some(config.getBoolean(path)) else None
    def optInt(path: String): Option[Int]           = if (config.hasPath(path)) Some(config.getInt(path)) else None
    def optLong(path: String): Option[Long]         = if (config.hasPath(path)) Some(config.getLong(path)) else None
    def optDouble(path: String): Option[Double]     = if (config.hasPath(path)) Some(config.getDouble(path)) else None
    def optIntList(path: String): Option[List[Int]] = if (config.hasPath(path)) Some(config.getIntList(path).asScala.toList.map(_.intValue())) else None
    // ...etc, will add if needed
  }


  trait Clock {
    def now: Long
  }

  object WallClock extends Clock {
    def now = System.currentTimeMillis()
  }

  class DryRunClock(initTs: Long=0) extends Clock {
    var ts = initTs
    def setTime(newTs: Long) { ts = newTs }
    def now = ts
  }
}
