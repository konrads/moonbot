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
    val closePrices = ffilled.map(_._2.weightedPrice)  // Note: textbook TA suggests close not weightedPrice, also calendar minutes, not minutes since now...
//    if (closePrices.size != window) {
//      val tradeDatas2Millis = tradeDatas2.map(_.timestamp.getMillis)
//      val tradeDatas2Delta = tradeDatas2Millis.maxOption.map(x => x - tradeDatas2Millis.minOption.getOrElse(0l)).getOrElse(0l)
//
//      val resampledTicksMillis = resampledTicks.map(_._1)
//      val resampledTicksDelta = resampledTicksMillis.maxOption.map(x => x - resampledTicksMillis.minOption.getOrElse(0l)).getOrElse(0l)
//
//      val ffilledMillis = ffilled.map(_._1)
//      val ffilledDelta = ffilledMillis.maxOption.map(x => x - ffilledMillis.minOption.getOrElse(0l)).getOrElse(0l)
//
//      log.info(
//        s"""#### bbands: ${closePrices.size} != ${window} != ${resampledTicks.size} != ${ffilled.size} != ${tradeDatas2.size}
//           |tradeDatas2:         delta: ${tradeDatas2Delta}ms - ${tradeDatas2Delta / 60000}m min: ${tradeDatas2Millis.minOption} max: ${tradeDatas2Millis.maxOption}
//           |resampledTicksDelta: delta: ${resampledTicksDelta} min ${resampledTicksMillis.minOption} max: ${resampledTicksMillis.maxOption}
//           |ffilledDelta:        delta: ${ffilledDelta} min: ${ffilledMillis.minOption} max: ${ffilledMillis.maxOption}
//          ####""".stripMargin)
//    }
    val (sentiment, bbandsScore, upper, middle, lower) = bbands(closePrices, devUp=devUp, devDown=devDown) match {
      case Some((upper, middle, lower)) if closePrices.size == window =>  // make sure we have a full window, otherwise go neutral
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
  override def strategize(ledger: Ledger): StrategyResult = ???
}

class MACDStrategy(val config: Config) extends Strategy {
  override def strategize(ledger: Ledger): StrategyResult = ???
}
