
import java.io.{File, PrintWriter}

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{Json, Reads}

import scala.Console.{GREEN, RED, RESET}


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

  object RunType extends Enumeration {
    type RunType = Value
    val Live, Dry, Backtest = Value
  }

  object Dir extends Enumeration {
    type Dir = Value
    val LongDir = Value("Long")
    val ShortDir = Value("Short")
  }

  object DataFreq extends Enumeration {
    type DataFreq = Value
    val `10s`, `1m`, `1h`, `4h` = Value
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

  object TradeLifecycle extends Enumeration {
    type TradeLifecycle = Value
    val Waiting = Value                   // issued new order, awaiting confirmation of fill/postOnlyFailure(if in open)/(cancel if in close)
    val IssuingNew = Value                // awaiting order creation confirmation
    val IssuingOpenAmend = Value          // awaiting amend confirmation
    val IssuingOpenCancel = Value         // awaiting cancellation confirmation
    val IssuingTakeProfitCancel = Value   // awaiting takProfit cancel confirmation
    val IssuingStoplossCancel = Value     // awaiting stoploss cancel confirmation
  }

  val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(DateTimeZone.UTC)

  def parseDateTime(asStr: String) = DateTime.parse(asStr, dateFormat)

  def formatDateTime(asDt: DateTime) = dateFormat.print(asDt)

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

  def timeit[T](f: => T, runs: Int=10): (Double, T) = {
    val start = System.currentTimeMillis
    val res = for (i <- 0 to runs) yield f
    val end = System.currentTimeMillis
    val avgRuntime = (end - start).toDouble / runs
    (avgRuntime, res.head)
  }

  def round(x: Double): Double =
    BigDecimal(x).setScale(10, BigDecimal.RoundingMode.HALF_UP).toDouble


  // var uuidCnt = 0;  def uuid: String = synchronized { uuidCnt += 1; "%05d".format(uuidCnt) }
  def uuid: String =
    java.util.UUID.randomUUID().toString

  case class PersistentState(pandl: Double, restarts: Int) {
    import scala.jdk.CollectionConverters._
    def persist(filename: String="state.properties"): Unit =
      new PrintWriter(filename) {
        val contents = ConfigFactory.parseMap(Map("pandl" -> pandl, "restarts" -> restarts).asJava).root.render(ConfigRenderOptions.concise())
        write(contents)
        close
      }
  }
  object PersistentState {
    def load(filename: String="state.properties"): PersistentState = {
      val f = new File(filename)
      if (f.exists) {
        val conf = ConfigFactory.parseFile(f)
        PersistentState(pandl = conf.getDouble("pandl"), restarts = conf.getInt("restarts"))
      } else
        PersistentState(pandl = 0, restarts = 0)
    }
  }

  class OptimizedIter(backedBy: Iterator[WsModel], issueSynthetic: Boolean=false) extends Iterator[WsModel] {
    var lastTs: DateTime = null
    var lastOrderBookSummary: OrderBookSummary = null
    var cache: Vector[WsModel] = Vector.empty

    override def hasNext: Boolean =
      if (cache.nonEmpty)
        true
      else if (backedBy.hasNext) {
        val nextVal = backedBy.next
        nextVal match {
          case x:Trade            => lastTs = x.data.head.timestamp
          case x:OrderBookSummary => lastTs = x.timestamp
          case x:OrderBook        => lastTs = x.data.head.timestamp
          case x:Info             => lastTs = x.timestamp
          case x:Funding          => lastTs = x.data.head.timestamp
          case x:UpsertOrder      => lastTs = x.data.head.timestamp
          case _                  => ()
        }

        nextVal match {
          case _:OrderBookSummary => ???  // unexpected synthetic in the stream!!! if needed, repeat logic from OrderBook
          case x:OrderBook =>
            val summary = x.summary
            if (summary.isEquivalent(lastOrderBookSummary))
              hasNext
            else {
              lastOrderBookSummary = summary
              cache = Vector(summary)
              true
            }
          case x:Trade if issueSynthetic =>
            val firstTrade = x.data.head
            val (ask, bid) = if (firstTrade.side == OrderSide.Buy)
              (firstTrade.price, firstTrade.price - 0.5)
            else
              (firstTrade.price + 0.5, firstTrade.price)
            val synthetic = OrderBookSummary("orderBook10", lastTs, ask, bid)
            if (synthetic.isEquivalent(lastOrderBookSummary))
              cache = Vector(x)
            else
              cache = Vector(x, synthetic)
            true
          case other =>
            cache = Vector(other)
            true
        }
      } else
        false

    override def next(): WsModel = {
      val head +: tail = cache
      cache = tail
      head
    }
  }

  def pretty(s: => String, sentiment: Sentiment.Value=Sentiment.Neutral, shouldColour: Boolean=false): String = (sentiment, shouldColour) match {
    case (_, false) | (Sentiment.Neutral, _) => s
    case (Sentiment.Bull, true)              => s"$GREEN$s$RESET"
    case (Sentiment.Bear, true)              => s"$RED$s$RESET"
    case _                                   => ???  // to avoid warning: It would fail on the following input: (_, true)
  }
}
