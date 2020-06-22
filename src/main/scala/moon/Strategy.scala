package moon

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import moon.talib._
import moon.Sentiment._
import moon.TickDirection._
import moon.OrderSide._


case class StrategyResult(sentiment: Sentiment.Value, metrics: Map[String, BigDecimal], ledger: Ledger)

trait Strategy {
  val log = Logger[Strategy]
  def strategize(ledger: Ledger): StrategyResult
}

object Strategy {
  def apply(name: String, config: Config): Strategy = name.toLowerCase match {
    case "tickdirection" => new TickDirectionStrategy(config)
    case "bullbear" => new BullBearEmaStrategy(config)
    case "bbands" => new BBandsStrategy(config)
    case "rsi" => new RSIStrategy(config)
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
        pre.lastOption.toSeq ++ post
    }
  }
}

class TickDirectionStrategy(val config: Config) extends Strategy {
  import moon.RichConfig
  val periodMs = config.optInt("periodMs").getOrElse(4 * 60 * 1000)
  val upper = config.optDouble("upper").getOrElse(0.75)
  val lower = config.optDouble("lower").getOrElse(-0.75)
  log.info(s"Strategy ${this.getClass.getSimpleName}: periodMs: $periodMs, upper: $upper, lower: $lower")
  override def strategize(ledger: Ledger): StrategyResult = {
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
    StrategyResult(sentiment, Map("data.tickDir.score" -> tickDirScore), ledger.copy(tradeDatas = tradeDatas2))
  }
}

class BullBearEmaStrategy(val config: Config) extends Strategy {
  import moon.RichConfig
  val periodMs = config.optInt("periodMs").getOrElse(10 * 60 * 1000)
  val emaSmoothing = config.optDouble("emaSmoothing").getOrElse(2.0)
  val upper = config.optDouble("upper").getOrElse(0.25)
  val lower = config.optDouble("lower").getOrElse(-0.25)
  log.info(s"Strategy ${this.getClass.getSimpleName}: emaSmoothing: $emaSmoothing, periodMs: $periodMs, upper: $upper, lower: $lower")
  override def strategize(ledger: Ledger): StrategyResult = {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, periodMs)
    val volumeScore = if (tradeDatas2.isEmpty)
    // only gets here if no trades are done in a recent period
      BigDecimal(0)
    else {
      val (bullTrades, bearTrades) = tradeDatas2.partition(_.side == Buy)
      val bullVolume = ema(bullTrades.map(_.size), emaSmoothing)
      val bearVolume = ema(bearTrades.map(_.size), emaSmoothing)
      (bullVolume - bearVolume) / (bullVolume + bearVolume)
    }
    val sentiment = if (volumeScore > upper)
      Bull
    else if (volumeScore < lower)
      Bear
    else
      Neutral
    StrategyResult(sentiment, Map("data.volume.score" -> volumeScore), ledger.copy(tradeDatas = tradeDatas2))
  }
}

class BBandsStrategy(val config: Config) extends Strategy {
  import moon.RichConfig
  val window = config.optInt("window").getOrElse(4)
  val resamplePeriodMs = config.optInt("resamplePeriodMs").getOrElse(60 * 1000)
  val devUp = config.optDouble("devUp").getOrElse(2.0)
  val devDown = config.optDouble("devDown").getOrElse(2.0)
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, resamplePeriodMs: $resamplePeriodMs, devUp: $devUp, devDown: $devDown")
  override def strategize(ledger: Ledger): StrategyResult = {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, window * resamplePeriodMs, dropLast=false)
    val resampledTicks = resample(tradeDatas2, resamplePeriodMs)
    val ffilled = ffill(resampledTicks).takeRight(window)
    val prices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
    val (sentiment, bbandsScore, upper, middle, lower) = bbands(prices, devUp=devUp, devDown=devDown) match {
      case Some((upper, middle, lower)) if prices.size == window =>  // make sure we have a full window, otherwise go neutral
        val currPrice = (ledger.askPrice + ledger.bidPrice) / 2
        if (currPrice > upper)
          (Bull, BigDecimal(Bull.id), Some(upper), Some(middle), Some(lower))
        else if (currPrice < lower)
          (Bear, BigDecimal(Bear.id), Some(upper), Some(middle), Some(lower))
        else {
          val score = 2 * (currPrice - lower)/(upper - lower) - 1
          (Neutral, score, Some(upper), Some(middle), Some(lower))
        }
      case _ =>
        (Neutral, BigDecimal(Neutral.id), None, None, None)
    }
    StrategyResult(
      sentiment,
      (Seq("data.bbands.score" -> bbandsScore) ++ upper.map("data.bbands.upper" -> _).toSeq ++ middle.map("data.bbands.middle" -> _).toSeq ++ lower.map("data.bbands.lower" -> _).toSeq).toMap,
      ledger.copy(tradeDatas = tradeDatas2))
  }
}


// FIXME: implement if makes sense?
class RSIStrategy(val config: Config) extends Strategy {
  val window = config.optInt("window").getOrElse(10)
  val resamplePeriodMs = config.optInt("resamplePeriodMs").getOrElse(60 * 1000)
  val upper = config.optDouble("upper").getOrElse(55.0)
  val lower = config.optDouble("lower").getOrElse(45.0)
  log.info(s"Strategy ${this.getClass.getSimpleName}: window: $window, resamplePeriodMs: $resamplePeriodMs, upper: $upper, lower: $lower")
  override def strategize(ledger: Ledger): StrategyResult = {
    val tradeDatas2 = Strategy.latestTradesData(ledger.tradeDatas, (window+1) * resamplePeriodMs, dropLast=false)
    val resampledTicks = resample(tradeDatas2, resamplePeriodMs)
    val ffilled = ffill(resampledTicks).takeRight(window+1)
    val prices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
    val (sentiment, id, scoreVal) = rsi(prices) match {
      case Some(res) if prices.size > window =>  // make sure we have a full window (+1), otherwise go neutral
        if (res > upper)
          (Bull, BigDecimal(Bull.id), Some(res))
        else if (res < lower)
          (Bear, BigDecimal(Bear.id), Some(res))
        else
          (Neutral, BigDecimal(Neutral.id), Some(res))
      case _ =>
        (Neutral, BigDecimal(Neutral.id), None)
    }
    StrategyResult(
      sentiment,
      (scoreVal.map("data.rsi.score" -> _).toSeq :+ ("data.rsi.upper" -> BigDecimal(upper)) :+ ("data.rsi.lower" -> BigDecimal(lower))).toMap,
      ledger.copy(tradeDatas = tradeDatas2))
  }
}

class MACDStrategy(val config: Config) extends Strategy {
  override def strategize(ledger: Ledger): StrategyResult = ???
}
