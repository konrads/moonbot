package moon

object talib {
  object MA extends Enumeration {
    type MA = Value
    val SMA, EMA = Value
  }

  val MS_IN_MINUTE = 1000 * 60

  /**
   * Formula from:
   * https://origin2.cdn.componentsource.com/sites/default/files/resources/dundas/538216/Documentation/Bollinger.html#:~:text=The%20upper%20and%20lower%20Bollinger,the%20upper%20and%20lower%20bands.
   * MA = middle = sum(xs) / len(xs)
   * delta = sqrt( sum([(x - MA)**2 for x in xs]) / len(xs) )
   * lower = MA - dev_down * delta
   * upper = MA + dev_up * delta
   */
  def bbands(xs: Seq[Double], devUp: Double=2, devDown: Double=2, maType: MA.Value=MA.SMA): Option[(Double, Double, Double)] =
    if (xs.isEmpty)
      None
    else {
      val ma_ = ma(xs, xs.size, maType)
      val variance = xs.map(a => math.pow(a - ma_, 2)).sum / xs.size
      val stdev = math.sqrt(variance)  // FIXME: loosing accuracy by converting to a Double...?

      val lower = ma_ - devDown * stdev
      val middle = ma_
      val upper = ma_ + devUp * stdev
      Some((upper, middle, lower))
    }

  /**
   * For further strategies:
   * https://www.investopedia.com/terms/r/rsi.asp
   */
  def rsi(xs: Seq[Double], maType: MA.Value=MA.SMA): Option[Double] = {
    if (xs.size <= 1)
      None
    else {
      val xsShifted = (0.0 +: xs).take(xs.size)
      val deltas = (xs zip xsShifted).drop(1).map { case (x, y) => x - y }
      val wins = deltas.map(x => if (x > 0) x else 0.0)
      val losses = deltas.map(x => if (x < 0) -x else 0.0)
      val winsMa = ma(wins, wins.size, maType)
      val lossesMa = ma(losses, losses.size, maType)
      val rsi = if (lossesMa == 0.0)
        100.0
      else
        100 - (100 / (1 + winsMa / lossesMa))
      Some(rsi)
    }
  }

  // https://stackoverflow.com/questions/34427530/macd-function-returning-incorrect-values/34453997#34453997
  def macd(xs: Seq[Double], slow: Int=26, fast: Int=12, signal: Int=9, maType: MA.Value=MA.EMA, signalMaType: Option[MA.Value]=None): Option[(Double /* macd */, Double /* signal */, Double /* histogram */)] = {  // note - ignoring signal (default = 9)
    if (xs.size < (slow + signal) - 1)
      None
    else {
      val slowMas = for(i <- slow - fast to xs.size) yield ma(xs.take(i), slow, maType)
      val xs2 = xs.drop(slow - fast)
      val fastMas = for(i <- 0 to xs2.size) yield ma(xs2.take(i), fast, maType)
      val macds = (fastMas zip slowMas).map { case (f, s) => f - s }
      val macds2 = macds.drop(slow - fast + 1)
      val signal_ = ma(macds2, signal, signalMaType.getOrElse(maType))
      val histogram = macds.last - signal_
      Some((macds.last, signal_, histogram))
    }
  }

  // as per: https://en.wikipedia.org/wiki/Average_true_range#:~:text=The%20true%20range%20is%20the,low%20minus%20the%20previous%20close
  def atr(high: Seq[Double], low: Seq[Double], close: Seq[Double], timeperiod: Int): Option[Double] = {
    assert(high.size == low.size && low.size == close.size)
    if (high.size < timeperiod + 1)
      None
    else {
      val h = high.drop(1)
      val l = low.drop(1)
      val pc = close.dropRight(1)
      val trs = (h zip l zip pc) map { case ((h2:Double, l2:Double), pc2:Double) => Seq(h2 - l2, math.abs(h2 - pc2), math.abs(l2 - pc2)).max }
      val atr0 = trs.take(timeperiod).sum / timeperiod
      val res = trs.drop(timeperiod).foldLeft(atr0) { case (prevATR, tr) => (prevATR * (timeperiod-1) + tr) / timeperiod }
      Some(res)
    }
  }

  def capProportionalExtremes(): Double => Double = {
    var high: Double = 0
    var low: Double = 0
    def cap(x: Double): Double = {
      val (res, h, l) = capProportionalExtremes_stateless(x, low, high)
      high = h
      low = l
      res
    }
    cap
  }

  /**
   * Given a MACD histogram graph:
   *
   *      *+
   *     *  +
   *  0 -----------
   *          #  @
   *           #@
   *
   *  * - increasing above minHigh => 1
   *  + - decreasing above minHigh => proportional to latest peak
   *  # - decreasing below minLow => -1
   *  @ - increasing below minLow => proportional to lowest
   *
   *  Where upper and lower are some static boundaries.
   */
  def capProportionalExtremes_stateless(x: Double, low: Double, high: Double): (Double, Double, Double) = {
    if (x > 0 && x > high)
      (1, 0, x)
    else if (x > 0)
      (x/high, 0, high)
    else if (x < 0 && x < low)
      (-1, x, 0)
    else if (x < 0)
      (-x/low, low, 0)
    else
      (0, 0, 0)
  }

  def capPeakTrough(): Double => Double = {
    var high: Double = 0
    var low: Double = 0
    var prev: Double = 0
    def cap(x: Double): Double = {
      val res = if (x > 0 && x > prev) {
        low = 0
        high = x
        1.0
      } else if (x > 0) {
        low = 0
        x / high
      } else if (x < 0 && x < prev) {
        high = 0
        low = x
        -1.0
      } else if (x < 0) {
        high = 0
        -x / low
      } else {  // between minUpper and minLower
        low = 0
        high = 0
        0.0
      }
      prev = x
      res
    }
    cap
  }

  case class TradeTick(weightedPrice: Double, open: Double, close: Double, high: Double, low: Double, volume: Double)

  def resample(trades: Seq[TradeData], periodMs: Long = MS_IN_MINUTE): Seq[(Long, TradeTick)] =
    if (trades.isEmpty)
      Vector.empty
    else {
      val lastMillis = trades.map(_.timestamp.getMillis).view.max
      trades.groupMap(o => (o.timestamp.getMillis - lastMillis) / periodMs)(o => (o.timestamp, o.price, o.size)).view.mapValues {
        tsPriceAndQty =>
          val tsPriceAndQty2 = tsPriceAndQty.sortBy(_._1)
          val volume = tsPriceAndQty2.map(_._3).sum
          val prices = tsPriceAndQty2.map(_._2)
          val low = prices.min
          val high = prices.max
          val open = prices.head
          val close = prices.last
          val weightedPrice = tsPriceAndQty2.map { case (ts, price, qty) => price * qty }.sum / volume
          TradeTick(weightedPrice=weightedPrice, high=high, low=low, open=open, close=close, volume=volume)
      }.toVector.sortBy(_._1)
    }

  def ffill(minAndVals: Seq[(Long, TradeTick)]): Seq[(Long, TradeTick)] =
    if (minAndVals.isEmpty)
      Vector.empty
    else {
      val (res, _) = minAndVals.tail.foldLeft((Vector(minAndVals.head), minAndVals.head)) {
        case ((soFar, (prevTs, prevV)), (ts, v)) =>
          val soFar2 = soFar ++ (prevTs+1 to ts-1).map((_, prevV.copy(volume = 0))) :+ (ts, v)
          (soFar2, (ts, v))
      }
      res
    }

  def ma(xs: Seq[Double], period: Int, maType: MA.Value): Double = maType match {
    case MA.SMA => sma(xs, period)
    case MA.EMA => ema(xs, period)
  }

  def sma(xs: Seq[Double], period: Int): Double =
    if (xs.isEmpty)
      0
    else {
      val xs2 = xs.takeRight(period)
      xs2.sum / xs2.size
    }

  // https://www.investopedia.com/ask/answers/122314/what-exponential-moving-average-ema-formula-and-how-ema-calculated.asp
  // https://www.investopedia.com/articles/trading/10/simple-exponential-moving-averages-compare.asp
  // https://www.youtube.com/watch?v=ezcwBDsDviE
  def ema(xs: Seq[Double], period: Int, emaSmoothing: Double=2): Double = {
    if (xs.isEmpty)
      0
    else {
      val k = emaSmoothing / (period + 1)
      val ema0 = xs.take(period).sum / period
      xs.drop(period).foldLeft(ema0)(
        (ema, t1) => ema * (1 - k) + t1 * k
      )
    }
  }

  def ma_mom(xs: Seq[Double], period: Int, maType: MA.Value = MA.SMA): Double =
    if (xs.isEmpty)
      0
    else {
      val t0 = ma(xs.dropRight(1), period, maType)
      val t1 = ma(xs, period, maType)
      t1 - t0
    }


  /** Find increasing/decreasing slope of eg. 10, 5, 3 (e/s)ma's */
  def indecreasingSlope(xs: Seq[Double], maPeriods: Seq[Int]=Vector(10, 5, 3)): Option[Seq[Double]] = {
    if (xs.size < maPeriods.max)
      None
    else {
      val mas = for(p <- maPeriods) yield polyfit(xs.takeRight(p))._1
      val sortedMas = mas.sorted
      val isUnique = mas.toSet.size == mas.size  // ensure no repetition in the sequence, ie. true increasing/decreasing
      if (mas.forall(_ > 0) && isUnique && mas == sortedMas)
        Some(mas)
      else if (mas.forall(_ < 0) && isUnique && mas == sortedMas.reverse)
        Some(mas)
      else
        None
    }
  }

  // formula: https://www.varsitytutors.com/hotmath/hotmath_help/topics/line-of-best-fit
  // consider (but not following): https://github.com/hipjim/scala-linear-regression/blob/master/regression.scala
  def polyfit(ys: Seq[Double], xs: Option[Seq[Double]]=None): (Double, Double) = {
    val xs2 = xs.getOrElse(ys.indices.map(_.toDouble))
    val avgY = ys.sum / ys.size
    val avgX = xs2.sum / xs2.size
    val slopeDenominator = xs2.map(x => math.pow(x - avgX, 2)).sum
    val slope: Double = if (slopeDenominator == 0)
      0
    else
      (xs2 zip ys).map { case (x, y) => (x - avgX) * (y - avgY) }.sum / slopeDenominator
    val yIntercept = avgY - slope * avgX
    (slope, yIntercept)
  }
}
