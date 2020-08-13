package moon

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import moon.DataFreq._
import moon.Sentiment._
import moon.talib._
import moon.talib.MA._


case class StrategyResult(sentiment: Sentiment.Value, metrics: Map[String, Double])

trait Strategy {
  val log = Logger[Strategy]
  val config: Config
  val MIN_EMA_WINDOW = 200  // from experiments, EMA is unlikely to change once window > 200

// eliminating caching till I resolve how to pass on the latest ledger
//  var cacheKey: Any = _
//  var cacheHit: Any = _
//  def cacheHitOrCalculate[T](key: Any)(f: => T): T = {
//    if (key != cacheKey) {
//      cacheKey = key
//      cacheHit = f
//    }
//    cacheHit.asInstanceOf[T]
//  }
  def strategize(ledger: Ledger): StrategyResult
}

object Strategy {
  def apply(name: String, config: Config, parentConfig: Config): Strategy = name match {
    case "indecreasing"  => new IndecreasingStrategy(config)
    case "bbands"        => new BBandsStrategy(config)
    case "rsi"           => new RSIStrategy(config)
    case "macd"          => new MACDStrategy(config)
    case "ma"            => new MAStrategy(config)
    case "alternating"   => new AlternatingStrategy(config)  // test strategy
    case "weighted"      => new WeightedStrategy(config, parentConfig)
  }
}

// Test strategy
class AlternatingStrategy(val config: Config) extends Strategy {
  val n = config.optInt("n").getOrElse(10)
  val sentiments = LazyList.continually(List.fill(n)(Bull) ++ List.fill(n)(Neutral) ++ List.fill(n)(Bear) ++ List.fill(n)(Neutral)).flatten.iterator
  log.info(s"Strategy ${this.getClass.getSimpleName}")
  override def strategize(ledger: Ledger): StrategyResult = {
    val s = sentiments.next
    StrategyResult(s, Map("data.alternating.sentiment" -> s.id))
  }
}


class BBandsStrategy(val config: Config) extends Strategy {
  val window = config.optInt("window").getOrElse(4)
  val dataFreq = config.optString("dataFreq").map(DataFreq.withName).getOrElse(`1m`)
  val devUp = config.optDouble("devUp").getOrElse(2.0)
  val devDown = config.optDouble("devDown").getOrElse(2.0)
  val minUpper = config.optDouble("minUpper").getOrElse(0.9)
  val minLower = config.optDouble("minLower").getOrElse(-0.9)
  val capFun = capProportionalExtremes()
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, dataFreq: $dataFreq, devUp: $devUp, devDown: $devDown, minUpper: $minUpper, minLower: $minLower")
  override def strategize(ledger: Ledger): StrategyResult = {
    val prices = ledger.tradeRollups.forBucket(dataFreq).vwap.takeRight(window)
    val (sentiment, bbandsScore, upper, middle, lower) = if (prices.size == window)
      bbands(prices, devUp = devUp, devDown = devDown) match {
        case Some((upper, middle, lower)) => // make sure we have a full window, otherwise go neutral
          val currPrice = (ledger.askPrice + ledger.bidPrice) / 2
          val score: Double = if (currPrice > upper)
            currPrice - upper
          else if (currPrice < lower)
            currPrice - lower
          else
            0
          val capScore = capFun(score)
          if (capScore > minUpper)
            (Bull, 1.0, Some(upper), Some(middle), Some(lower))
          else if (capScore < minLower)
            (Bear, -1.0, Some(upper), Some(middle), Some(lower))
          else {
            val score = 2 * (currPrice - lower) / (upper - lower) - 1
            (Neutral, score, Some(upper), Some(middle), Some(lower))
          }
        case _ =>
          (Neutral, Neutral.id.toDouble, None, None, None)
      } else
      (Neutral, Neutral.id.toDouble, None, None, None)

    StrategyResult(
      sentiment,
      (Vector[(String, Double)]("data.bbands.sentiment" -> sentiment.id, "data.bbands.score" -> bbandsScore) ++ upper.map("data.bbands.upper" -> _).toVector ++ middle.map("data.bbands.middle" -> _).toVector ++ lower.map("data.bbands.lower" -> _).toVector).toMap)
  }
}


class RSIStrategy(val config: Config) extends Strategy {
  val window = config.optInt("window").getOrElse(10)
  val dataFreq = config.optString("dataFreq").map(DataFreq.withName).getOrElse(`1m`)
  val upper = config.optDouble("upper").getOrElse(55.0)
  val lower = config.optDouble("lower").getOrElse(45.0)
  val minUpper = config.optDouble("minUpper").getOrElse(0.9)
  val minLower = config.optDouble("minLower").getOrElse(-0.9)
  val capFun = capProportionalExtremes()
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, dataFreq: $dataFreq, upper: $upper, lower: $lower, minUpper: $minUpper, minLower: $minLower")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val prices = ledger.tradeRollups.forBucket(dataFreq).vwap.takeRight(window+1)
    val (sentiment, scoreVal) = rsi(prices) match {
      case Some(res) if prices.size > window =>  // make sure we have a full window (+1), otherwise go neutral
        val score: Double = if (res > upper)
          res - upper
        else if (res < lower)
          res - lower
        else
          0
        val capScore = capFun(score)
        val sentiment = if (capScore > minUpper)
          Bull
        else if (capScore < minLower)
          Bear
        else
          Neutral
        (sentiment, Some(res))
      case _ =>
        (Neutral, None)
    }
    StrategyResult(
      sentiment,
      (Vector[(String, Double)]("data.rsi.sentiment" -> sentiment.id) ++ scoreVal.map("data.rsi.score" -> _).toVector :+ ("data.rsi.upper" -> upper) :+ ("data.rsi.lower" -> lower)).toMap)
  }
}

class MACDStrategy(val config: Config) extends Strategy {
  val slowWindow = config.optInt("slowWindow").getOrElse(26)
  val fastWindow = config.optInt("fastWindow").getOrElse(12)
  val signalWindow = config.optInt("signalWindow").getOrElse(9)
  val dataFreq = config.optString("dataFreq").map(DataFreq.withName).getOrElse(`1m`)
  val minUpper = config.optDouble("minUpper").getOrElse(0.9)
  val minLower = config.optDouble("minLower").getOrElse(-0.9)
  val capFun = capProportionalExtremes()
  assert(fastWindow < slowWindow)
  log.info(s"Strategy ${this.getClass.getSimpleName}: slowWindow: $slowWindow, fastWindow: $fastWindow, signalWindow: $signalWindow, dataFreq: $dataFreq, minUpper: $minUpper, minLower: $minLower")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val prices = ledger.tradeRollups.forBucket(dataFreq).vwap.takeRight(slowWindow + signalWindow + 2)
    val (sentiment, macdVal, macdSignal, macdHistogram, macdCapScore) = macd(prices, slowWindow, fastWindow, signalWindow) match {
      case Some((macd, signal, histogram)) =>
        val capScore = capFun(histogram)
        val sentiment = if (capScore > minUpper)
          Bull
        else if (capScore < minLower)
          Bear
        else
          Neutral
        (sentiment, Some(macd), Some(signal), Some(histogram), Some(capScore))
      case _ =>
        (Neutral, None, None, None, None)
    }
    StrategyResult(
      sentiment,
      // note: keeping macd's sentiment as a seperate metric to show indicator specific sentiment
      (Vector[(String, Double)]("data.macd.sentiment" -> sentiment.id) ++ macdVal.map("data.macd.macd" -> _).toVector ++ macdSignal.map("data.macd.signal" -> _).toVector ++ macdHistogram.map("data.macd.histogram" -> _).toVector ++ macdCapScore.map("data.macd.cap" -> _).toVector).toMap)
  }
}


class IndecreasingStrategy(val config: Config) extends Strategy {
  val periods = config.optIntList("periods").getOrElse(List(10, 5, 3))
  val dataFreq = config.optString("dataFreq").map(DataFreq.withName).getOrElse(`1m`)
  val minAbsSlope = config.optDouble("minAbsSlope").getOrElse(3.0).abs
  val maxAbsSlope = config.optDouble("maxAbsSlope").getOrElse(20.0).abs
  val maxPeriod = periods.max
  log.info(s"Strategy ${this.getClass.getSimpleName}: periods: ${periods.mkString(", ")}, minAbsSlope: $minAbsSlope, maxAbsSlope: $maxAbsSlope")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val prices = ledger.tradeRollups.forBucket(dataFreq).vwap.takeRight(maxPeriod+1)
    val (sentiment, avgSlope) = indecreasingSlope(prices, periods) match {
      case Some(slopes) =>
        val avgSlope = slopes.sum / slopes.size
        if (avgSlope > 0 && avgSlope.abs > minAbsSlope && avgSlope.abs < maxAbsSlope)
          (Bull, Some(avgSlope))
        else if (avgSlope < 0 && avgSlope.abs > minAbsSlope && avgSlope.abs < maxAbsSlope)
          (Bear, Some(avgSlope))
        else
          (Neutral, Some(avgSlope))
      case None =>
        (Neutral, None)
    }
    StrategyResult(
      sentiment,
      Map[String, Double]("data.indecreasing.sentiment" -> sentiment.id, "data.indecreasing.lower" -> -minAbsSlope, "data.indecreasing.upper" -> minAbsSlope) ++ avgSlope.map("data.indecreasing.slope" -> _).view.toMap)
  }
}


class MAStrategy(val config: Config) extends Strategy {
  val window = config.optInt("window").getOrElse(26)
  val maType = config.optString("maType").map(MA.withName).getOrElse(SMA)
  val upper = config.optDouble("upper").getOrElse(0.9)
  val lower = config.optDouble("lower").getOrElse(-0.9)
  val upperDelta = config.optDouble("upperDelta").getOrElse(10.0)
  val lowerDelta = config.optDouble("lowerDelta").getOrElse(-10.0)
  val dataFreq = config.optString("dataFreq").map(DataFreq.withName).getOrElse(`1m`)
  val capFun = capProportionalExtremes()
  assert (upper > 0 && lower < 0)
  assert (upperDelta > 0 && lowerDelta < 0)
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, dataFreq: $dataFreq")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val prices = ledger.tradeRollups.forBucket(dataFreq).vwap.takeRight(window+1)
    val currMa = ma(prices, window, maType)
    val currPrice = (ledger.askPrice + ledger.bidPrice) / 2
    val delta = currPrice - currMa
    val score = capFun(delta)
    val sentiment = if (score > upper && delta > upperDelta)
      Bull
    else if (score < lower && delta < lowerDelta)
      Bear
    else
      Neutral
    StrategyResult(
      sentiment,
      Map[String, Double]("data.ma.sentiment" -> sentiment.id, "data.ma.delta" -> delta, "data.ma.score" -> score, "data.ma.upperDelta" -> upperDelta, "data.ma.lowerDelta" -> lowerDelta))
  }
}


class HVFStrategy(val config: Config, val parentConfig: Config) extends Strategy {
  override def strategize(ledger: Ledger): StrategyResult = {
    ???
  }
}

class WeightedStrategy(val config: Config, val parentConfig: Config) extends Strategy {
  import scala.jdk.CollectionConverters._
  val weights = (for (k <- config.getObject("weights").keySet().asScala) yield k -> config.getDouble(s"weights.$k")).toMap
  val minUpper = config.optDouble("minUpper").getOrElse(0.7)
  val minLower = config.optDouble("minLower").getOrElse(-0.7)
  val weightSum = weights.values.sum
  log.info(s"Strategy ${this.getClass.getSimpleName}: weights: ${weights.map {case (n, w) => s"$n: $w"}.mkString(", ")}, minUpper: $minUpper, minLower: $minLower, children below...")
  val weightedStrategies = weights.map { case (name, weight) => (Strategy(name, parentConfig.getConfig(name), null /* only weighted uses parent */), weight) }
  override def strategize(ledger: Ledger): StrategyResult = {
    val individualReses = weightedStrategies.map { case (s, w) => (s.strategize(ledger), w) }
    val sentimentScoreAvg = individualReses.map { case (r, w) => r.sentiment.id * w }.sum / weightSum
    val sentiment = if (sentimentScoreAvg > minUpper)
      Bull
    else if (sentimentScoreAvg < minLower)
      Bear
    else
      Neutral
    StrategyResult(
      sentiment,
      individualReses.map(_._1.metrics).reduce(_ ++ _) + ("data.weighted.sentiment" -> sentiment.id))
  }
}
