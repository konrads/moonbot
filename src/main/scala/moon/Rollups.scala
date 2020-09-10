package moon

import moon.DataFreq._
import play.api.libs.json.Json

object Rollups {
  def apply(maxHours: Int): Rollups =
    Rollups(Map(
      `10s` -> RollupBuckets(window = 10*1000, maxBuckets = 60*6*(maxHours+1)),
      `1m`  -> RollupBuckets(window = 60*1000, maxBuckets = 60*(maxHours+1)),
      `1h`  -> RollupBuckets(window = 60*60*1000, maxBuckets = maxHours),
    ))
}


case class Rollups(_buckets: Map[DataFreq.Value, RollupBuckets]) {
  def add(tsMs: Long, price: Double, vol: Double): Rollups = copy(_buckets.map { case (k, v) => k -> v.add(tsMs, price, vol) })

  def forBucket(id: DataFreq.Value): RollupBuckets = _buckets(id).forecast

  def isEmpty: Boolean =  {
    val b = _buckets(`1m`)
    b.high.isEmpty && b.currentPeriod <= 0
  }

  def nonEmpty: Boolean = ! isEmpty
}


/**
 * Buckets of price/volume ticks, with all needed paradigms.
 * Build for speed.
 */
case class RollupBuckets(
    window: Long,
    maxBuckets: Int,
    // buckets
    high: Vector[Double] = Vector.empty,
    low: Vector[Double] = Vector.empty,
    open: Vector[Double] = Vector.empty,
    close: Vector[Double] = Vector.empty,
    vwap: Vector[Double] = Vector.empty,
    volume: Vector[Double] = Vector.empty,
    period: Vector[Long] = Vector.empty,
    // current bucket so far
    currentHigh: Double = 0,
    currentLow: Double = 0,
    currentOpen: Double = 0,
    currentClose: Double = 0,
    currentWeightedTotals: Double = 0,
    currentVolume: Double = 0,
    currentPeriod: Long = -1,
  ) {

  lazy val forecast: RollupBuckets = promote(false).prune

  lazy val asCandles: Vector[Candle] = period.zipWithIndex.map {
    case (p, i) => Candle(high=high(i), low=low(i), open=open(i), close=close(i), vwap=vwap(i), volume=volume(i), period=p)
  }

  private def promote(useLatestVol: Boolean): RollupBuckets =
    if (currentVolume <= 0)  // FIXME: hack, suggests there's an entry present
      this
    else
      copy(
        high          = high   :+ currentHigh,
        low           = low    :+ currentLow,
        open          = open   :+ currentOpen,
        close         = close  :+ currentClose,
        vwap          = vwap   :+ currentWeightedTotals / currentVolume,
        volume        = volume :+ (if (useLatestVol) currentVolume else volume.lastOption.getOrElse(currentVolume)),
        period        = period :+ currentPeriod,
        currentHigh   = 0,
        currentLow    = 0,
        currentOpen   = 0,
        currentClose  = 0,
        currentWeightedTotals = 0,
        currentVolume = 0,
        currentPeriod = currentPeriod + 1,
      )

  private def ffill(toPeriod: Long): RollupBuckets =
    period.lastOption match {
      case None => this
      case Some(lastPeriod) =>
        val fillCnt = (toPeriod - 1 - lastPeriod).toInt
        if (fillCnt <= 0)
          this
        else
          copy(
            high          = high   ++ Vector.fill(fillCnt)(high.last),
            low           = low    ++ Vector.fill(fillCnt)(low.last),
            open          = open   ++ Vector.fill(fillCnt)(open.last),
            close         = close  ++ Vector.fill(fillCnt)(close.last),
            vwap          = vwap   ++ Vector.fill(fillCnt)(vwap.last),
            volume        = volume ++ Vector.fill(fillCnt)(0.0),
            period        = period ++ (lastPeriod+1 to toPeriod-1),
            currentHigh   = 0,
            currentLow    = 0,
            currentOpen   = 0,
            currentClose  = 0,
            currentWeightedTotals = 0,
            currentVolume = 0,
            currentPeriod = toPeriod,
          )
    }

  private def prune: RollupBuckets =
    if (high.size <= maxBuckets)
      this
    else
      copy(
        high          = high.takeRight(maxBuckets),
        low           = low.takeRight(maxBuckets),
        open          = open.takeRight(maxBuckets),
        close         = close.takeRight(maxBuckets),
        vwap          = vwap.takeRight(maxBuckets),
        volume        = volume.takeRight(maxBuckets),
        period        = period.takeRight(maxBuckets),
      )

  def add(millis: Long, price: Double, vol: Double): RollupBuckets = {
    val newPeriod = millis / window
    if (currentPeriod < 0)  // adding to empty
      copy(currentHigh = price, currentLow = price, currentOpen = price, currentClose = price, currentVolume = vol, currentWeightedTotals = price * vol, currentPeriod = newPeriod)
    else if (newPeriod == currentPeriod)
      // keep adding to current bucket
      copy(currentHigh = math.max(currentHigh, price), currentLow = math.min(currentLow, price), currentClose = price, currentVolume = currentVolume + vol, currentWeightedTotals = currentWeightedTotals + price * vol)
    else if (newPeriod == currentPeriod + 1)
      // wrap up bucket, add to new bucket
      promote(true).prune.copy(currentHigh = price, currentLow = price, currentOpen = price, currentClose = price, currentVolume = vol, currentWeightedTotals = price * vol, currentPeriod = newPeriod)
    else if (newPeriod > currentPeriod + 1)
      promote(true).ffill(newPeriod).prune.copy(currentHigh = price, currentLow = price, currentOpen = price, currentClose = price, currentVolume = vol, currentWeightedTotals = price * vol, currentPeriod = newPeriod)
    else
      // bucket out of order, noop
      this
  }
}


case class Candle(high: Double, low: Double, open: Double, close: Double, vwap: Double, volume: Double, period: Long) {
  lazy val sentiment: Sentiment.Value = if (open < close) Sentiment.Bull else Sentiment.Bear
}
