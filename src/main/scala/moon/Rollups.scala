package moon

object Rollups {
  def apply(maxHours: Int): Rollups =
    Rollups(
      m=RollupBuckets(window = 60*1000, maxBuckets = 60*(maxHours+1)),
      h=RollupBuckets(window = 60*60*1000, maxBuckets = maxHours),
    )
}


case class Rollups(m: RollupBuckets, h: RollupBuckets) {
  def add(tsMs: Long, price: Double, vol: Double): Rollups =
    copy(
      m=m.add(tsMs, price, vol),
      h=h.add(tsMs, price, vol)
    )
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
    weightedPrice: Vector[Double] = Vector.empty,
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

  private def promote(useLatestVol: Boolean): RollupBuckets =
    if (currentPeriod < 0)
      this
    else
      copy(
        high          = high :+ currentHigh,
        low           = low :+ currentLow,
        open          = open :+ currentOpen,
        close         = close :+ currentClose,
        weightedPrice = weightedPrice :+ currentWeightedTotals / currentVolume,
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
            high          = high ++ Vector.fill(fillCnt)(high.last),
            low           = low ++ Vector.fill(fillCnt)(low.last),
            open          = open ++ Vector.fill(fillCnt)(open.last),
            close         = close ++ Vector.fill(fillCnt)(close.last),
            weightedPrice = weightedPrice ++ Vector.fill(fillCnt)(weightedPrice.last),
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
        weightedPrice = weightedPrice.takeRight(maxBuckets),
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
    else if (newPeriod > currentPeriod + 1) {
      // wrap up bucket, ffill, add to new bucket
      val x1 = promote(true)
      val x2 = x1.ffill(newPeriod)
      val x3 = x2.prune
      val x4 = x3.copy(currentHigh = price, currentLow = price, currentOpen = price, currentClose = price, currentVolume = vol, currentWeightedTotals = price * vol, currentPeriod = newPeriod)
      promote(true).ffill(newPeriod).prune.copy(currentHigh = price, currentLow = price, currentOpen = price, currentClose = price, currentVolume = vol, currentWeightedTotals = price * vol, currentPeriod = newPeriod)
    } else
      // bucket out of order, noop
      this
  }
}
