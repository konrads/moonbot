package moon

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import moon.Sentiment._
import moon.DataFreq._
import moon.talib.{macd, _}
import moon.talib.MA._


case class StrategyResult(sentiment: Sentiment.Value, metrics: Map[String, Double])

trait Strategy {
  val log = Logger[Strategy]
  val config: Config
  def strategize(ledger: Ledger): StrategyResult
}

object Strategy {
  def apply(name: String, config: Config): Strategy = name match {
    case "permabull"    => new PermaBullStrategy(config)
    case "indecreasing" => new IndecreasingStrategy(config)
  }
}

// Test strategy
class PermaBullStrategy(val config: Config) extends Strategy {
  override def strategize(ledger: Ledger): StrategyResult = {
    StrategyResult(Bull, Map.empty)
  }
}

class IndecreasingStrategy(val config: Config) extends Strategy {
  val periods = config.optIntList("periods").getOrElse(List(10, 5, 3))
  val dataFreq = config.optString("dataFreq").map(DataFreq.withName).getOrElse(`1m`)
  val minAbsSlope = config.optDouble("minAbsSlope").getOrElse(0.0001).abs
  val maxAbsSlope = config.optDouble("maxAbsSlope").getOrElse(0.001).abs
  val maxPeriod = periods.max
  assert(!periods.contains(1), s"Cannot run averages on period of 1, periods: $periods")
  log.info(s"Strategy ${this.getClass.getSimpleName}: periods: ${periods.mkString(", ")}, minAbsSlope: $minAbsSlope, maxAbsSlope: $maxAbsSlope, dataFreq: $dataFreq")
  override def strategize(ledger: Ledger): StrategyResult = { //cacheHitOrCalculate[StrategyResult](ledger.tradeDatas.lastOption) {
    val prices = ledger.tradeRollups.withForecast(dataFreq).vwap.takeRight(maxPeriod+1)
    val (sentiment, overallSlope) = indecreasingSlope(prices, periods) match {
      case Some(slopes) =>
        val overallSlope = slopes.head / slopes.size
        if (overallSlope > 0 && overallSlope.abs > minAbsSlope && overallSlope.abs < maxAbsSlope)
          (Bull, Some(overallSlope))
        else if (overallSlope < 0 && overallSlope.abs > minAbsSlope && overallSlope.abs < maxAbsSlope)
          (Bear, Some(overallSlope))
        else
          (Neutral, Some(overallSlope))
      case None =>
        (Neutral, None)
    }
    StrategyResult(
      sentiment,
      Map[String, Double]("data.indecreasing.sentiment" -> sentiment.id, "data.indecreasing.lower" -> -minAbsSlope, "data.indecreasing.upper" -> minAbsSlope) ++ overallSlope.map("data.indecreasing.slope" -> _).view.toMap)
  }
}