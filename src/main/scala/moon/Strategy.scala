package moon

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import moon.OrderSide._
import moon.Sentiment._
import moon.TickDirection._
import moon.talib._


case class StrategyResult(sentiment: Sentiment.Value, metrics: Map[String, Double], ledger: Ledger)

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
    case "tickDirection" => new TickDirectionStrategy(config)
    case "bullbear"      => new BullBearEmaStrategy(config)
    case "bbands"        => new BBandsStrategy(config)
    case "rsi"           => new RSIStrategy(config)
    case "macd"          => new MACDStrategy(config)
    case "alternating"   => new AlternatingStrategy(config)  // test strategy
    case "weighted"      => new WeightedStrategy(config, parentConfig)
  }

  def latestTradesData(tds: Seq[TradeData], periodMs: Int, dropLast: Boolean = true): Seq[TradeData] = {
    if (tds.isEmpty)
      tds
    else {
      val maxMs = tds.map(_.timestamp.getMillis).max
      val minMs = maxMs - periodMs
      val (pre, post) = tds.partition(_.timestamp.getMillis < minMs)
      if (dropLast)
        post
      else
        pre.lastOption.toVector ++ post
    }
  }
}

// Test strategy
class AlternatingStrategy(val config: Config) extends Strategy {
  val n = config.optInt("n").getOrElse(10)
  val sentiments = LazyList.continually(List.fill(n)(Bull) ++ List.fill(n)(Neutral) ++ List.fill(n)(Bear) ++ List.fill(n)(Neutral)).flatten.iterator
  log.info(s"Strategy ${this.getClass.getSimpleName}")
  override def strategize(ledger: Ledger): StrategyResult = {
    val s = sentiments.next
    StrategyResult(s, Map("data.alternating.sentiment" -> s.id), ledger.copy(tradeDatas = ledger.tradeDatas.takeRight(500)))
  }
}


class TickDirectionStrategy(val config: Config) extends Strategy {
  val periodMs = config.optInt("periodMs").getOrElse(4 * 60 * 1000)
  val upper = config.optDouble("upper").getOrElse(0.75)
  val lower = config.optDouble("lower").getOrElse(-0.75)
  log.info(s"Strategy ${this.getClass.getSimpleName}: periodMs: $periodMs, upper: $upper, lower: $lower")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, periodMs)
    val tickDirs = tradeDatas2.map(_.tickDirection)
    val tickDirScore = if (tickDirs.isEmpty) .0 else tickDirs.map {
      case MinusTick     => -1
      case ZeroMinusTick => -.5
      case ZeroPlusTick  =>  .5
      case PlusTick      =>  1
    }.sum / tickDirs.length
    val sentiment = if (tickDirScore > upper)
      Bull
    else if (tickDirScore < lower)
      Bear
    else
      Neutral
    StrategyResult(sentiment, Map("data.tickDir.sentiment" -> sentiment.id, "data.tickDir.score" -> tickDirScore), ledger.copy(tradeDatas = tradeDatas2))
  }
}

class BullBearEmaStrategy(val config: Config) extends Strategy {
  val periodMs = config.optInt("periodMs").getOrElse(10 * 60 * 1000)
  val emaSmoothing = config.optDouble("emaSmoothing").getOrElse(2.0)
  val upper = config.optDouble("upper").getOrElse(0.25)
  val lower = config.optDouble("lower").getOrElse(-0.25)
  log.info(s"Strategy ${this.getClass.getSimpleName}: emaSmoothing: $emaSmoothing, periodMs: $periodMs, upper: $upper, lower: $lower")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, periodMs)
    val volumeScore: Double = if (tradeDatas2.isEmpty)
    // only gets here if no trades are done in a recent period
      0
    else {
      val (bullTrades, bearTrades) = tradeDatas2.partition(_.side == Buy)
      val bullVolume = ema(bullTrades.map(_.size), bullTrades.size, emaSmoothing)
      val bearVolume = ema(bearTrades.map(_.size), bearTrades.size, emaSmoothing)
      (bullVolume - bearVolume) / (bullVolume + bearVolume)
    }
    val sentiment = if (volumeScore > upper)
      Bull
    else if (volumeScore < lower)
      Bear
    else
      Neutral
    StrategyResult(sentiment, Map("data.bullbear.sentiment" -> sentiment.id, "data.bullbear.score" -> volumeScore), ledger.copy(tradeDatas = tradeDatas2))
  }
}

class BBandsStrategy(val config: Config) extends Strategy {
  val window = config.optInt("window").getOrElse(4)
  val resamplePeriodMs = config.optInt("resamplePeriodMs").getOrElse(60 * 1000)
  val devUp = config.optDouble("devUp").getOrElse(2.0)
  val devDown = config.optDouble("devDown").getOrElse(2.0)
  val minUpper = config.optDouble("minUpper").getOrElse(0.9)
  val minLower = config.optDouble("minLower").getOrElse(-0.9)
  val capFun = capProportionalExtremes()
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, resamplePeriodMs: $resamplePeriodMs, devUp: $devUp, devDown: $devDown, minUpper: $minUpper, minLower: $minLower")
  override def strategize(ledger: Ledger): StrategyResult = {
    //val cacheHit = cacheHitOrCalculate[(Option[(Double, Double, Double)], Seq[TradeData])](ledger.tradeDatas.lastOption) {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, window * resamplePeriodMs, dropLast=false)
    val resampledTicks = resample(tradeDatas2, resamplePeriodMs)
    val ffilled = ffill(resampledTicks).takeRight(window)  // in case change from SMA => EMA
    val prices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
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
      (Vector[(String, Double)]("data.bbands.sentiment" -> sentiment.id, "data.bbands.score" -> bbandsScore) ++ upper.map("data.bbands.upper" -> _).toVector ++ middle.map("data.bbands.middle" -> _).toVector ++ lower.map("data.bbands.lower" -> _).toVector).toMap,
      ledger.copy(tradeDatas = tradeDatas2))
  }
}


class RSIStrategy(val config: Config) extends Strategy {
  val window = config.optInt("window").getOrElse(10)
  val resamplePeriodMs = config.optInt("resamplePeriodMs").getOrElse(60 * 1000)
  val upper = config.optDouble("upper").getOrElse(55.0)
  val lower = config.optDouble("lower").getOrElse(45.0)
  val minUpper = config.optDouble("minUpper").getOrElse(0.9)
  val minLower = config.optDouble("minLower").getOrElse(-0.9)
  val capFun = capProportionalExtremes()
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, resamplePeriodMs: $resamplePeriodMs, upper: $upper, lower: $lower, minUpper: $minUpper, minLower: $minLower")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, (window+1) * resamplePeriodMs, dropLast=false)
    val resampledTicks = resample(tradeDatas2, resamplePeriodMs)
    val ffilled = ffill(resampledTicks).takeRight(MIN_EMA_WINDOW)  // in case change from SMA => EMA
    val prices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
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
      (Vector[(String, Double)]("data.rsi.sentiment" -> sentiment.id) ++ scoreVal.map("data.rsi.score" -> _).toVector :+ ("data.rsi.upper" -> upper) :+ ("data.rsi.lower" -> lower)).toMap,
      ledger.copy(tradeDatas = tradeDatas2))
  }
}

class MACDStrategy(val config: Config) extends Strategy {
  val slowWindow = config.optInt("slowWindow").getOrElse(26)
  val fastWindow = config.optInt("fastWindow").getOrElse(12)
  val signalWindow = config.optInt("signalWindow").getOrElse(9)
  val resamplePeriodMs = config.optInt("resamplePeriodMs").getOrElse(60 * 1000)
  val minUpper = config.optDouble("minUpper").getOrElse(0.9)
  val minLower = config.optDouble("minLower").getOrElse(-0.9)
  val capFun = capProportionalExtremes()
  assert(fastWindow < slowWindow)
  log.info(s"Strategy ${this.getClass.getSimpleName}: slowWindow: $slowWindow, fastWindow: $fastWindow, signalWindow: $signalWindow, resamplePeriodMs: $resamplePeriodMs, minUpper: $minUpper, minLower: $minLower")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, (slowWindow + signalWindow) * resamplePeriodMs, dropLast=false)
    val resampledTicks = resample(tradeDatas2, resamplePeriodMs)
    val ffilled = ffill(resampledTicks).takeRight(MIN_EMA_WINDOW)
    val prices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
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
      (Vector[(String, Double)]("data.macd.sentiment" -> sentiment.id) ++ macdVal.map("data.macd.macd" -> _).toVector ++ macdSignal.map("data.macd.signal" -> _).toVector ++ macdHistogram.map("data.macd.histogram" -> _).toVector ++ macdCapScore.map("data.macd.cap" -> _).toVector).toMap,
      ledger.copy(tradeDatas = tradeDatas2))
  }
}


class IndecreasingStrategy(val config: Config) extends Strategy {
  val periods = config.optIntList("periods").getOrElse(List(10, 5, 3))
  val resamplePeriodMs = config.optInt("resamplePeriodMs").getOrElse(60 * 1000)
  val minAbsSlope = config.optDouble("minAbsSlope").getOrElse(3.0).abs
  val maxAbsSlope = config.optDouble("maxAbsSlope").getOrElse(20.0).abs
  val maxPeriod = periods.max
  log.info(s"Strategy ${this.getClass.getSimpleName}: periods: ${periods.mkString(", ")}, minAbsSlope: $minAbsSlope, maxAbsSlope: $maxAbsSlope")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, maxPeriod * resamplePeriodMs, dropLast=false)
    val resampledTicks = resample(tradeDatas2, resamplePeriodMs)
    val ffilled = ffill(resampledTicks).takeRight(MIN_EMA_WINDOW)
    val prices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
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
      Map[String, Double]("data.indecreasing.sentiment" -> sentiment.id, "data.indecreasing.lower" -> -minAbsSlope, "data.indecreasing.upper" -> minAbsSlope) ++ avgSlope.map("data.indecreasing.slope" -> _).view.toMap,
      ledger.copy(tradeDatas = tradeDatas2))
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
    val ledgerWithMostData = individualReses.toVector.maxBy(_._1.ledger.tradeDatas.length)._1.ledger
    StrategyResult(
      sentiment,
      individualReses.map(_._1.metrics).reduce(_ ++ _) + ("data.weighted.sentiment" -> sentiment.id),
      ledgerWithMostData)
  }
}
