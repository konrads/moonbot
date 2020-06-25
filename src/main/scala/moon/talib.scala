package moon

import scala.collection.Seq

object talib {
  object MA extends Enumeration {
    type MA = Value
    val SMA, EMA, EMA_2 = Value
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
  def bbands(xs: Seq[BigDecimal], devUp: BigDecimal=2, devDown: BigDecimal=2, maType: MA.Value=MA.SMA): Option[(BigDecimal, BigDecimal, BigDecimal)] =
    if (xs.isEmpty)
      None
    else {
      val ma_ = ma(xs, xs.size, maType)
      val variance = xs.map(a => (a - ma_).pow(2)).sum / xs.size
      val stdev = math.sqrt(variance.doubleValue)  // FIXME: loosing accuracy by converting to a Double...?

      val lower = ma_ - devDown * stdev
      val middle = ma_
      val upper = ma_ + devUp * stdev
      Some((upper, middle, lower))
    }

  /**
   * For further strategies:
   * https://www.investopedia.com/terms/r/rsi.asp
   */
  def rsi(xs: Seq[BigDecimal], maType: MA.Value=MA.SMA): Option[BigDecimal] = {
    if (xs.size <= 1)
      None
    else {
      val xsShifted = (BigDecimal(0) +: xs).take(xs.size)
      val deltas = (xs zip xsShifted).drop(1).map { case (x, y) => x - y }
      val wins = deltas.map(x => if (x > 0) x else BigDecimal(0))
      val losses = deltas.map(x => if (x < 0) -x else BigDecimal(0))
      val winsMa = ma(wins, wins.size, maType)
      val lossesMa = ma(losses, losses.size, maType)
      val rsi = if (lossesMa == BigDecimal(0))
        BigDecimal(100)
      else
        100 - (100 / (1 + winsMa / lossesMa))
      Some(rsi)
    }
  }

  // https://stackoverflow.com/questions/34427530/macd-function-returning-incorrect-values/34453997#34453997
  def macd(xs: Seq[BigDecimal], slow: Int=26, fast: Int=12, signal: Int=9, maType: MA.Value=MA.EMA): Option[(BigDecimal /* macd */, BigDecimal /* signal */, BigDecimal /* histogram */)] = {  // note - ignoring signal (default = 9)
    if (xs.size < (slow + signal) - 1)
      None
    else {
      val slowMas = for(i <- slow - fast to xs.size) yield ma(xs.take(i), slow, maType)
      val xs2 = xs.drop(slow - fast)
      val fastMas = for(i <- 0 to xs2.size) yield ma(xs2.take(i), fast, maType)
      val macds = (fastMas zip slowMas).map { case (f, s) => f - s }
      val macds2 = macds.drop(slow - fast + 1)
      val signal_ = ma(macds2, signal, maType)
      val histogram = macds.last - signal_
      Some((macds.last, signal_, histogram))
    }
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
  def capProportionalExtremes(): BigDecimal => BigDecimal = {
    var high: BigDecimal = 0
    var low: BigDecimal = 0
    def cap(x: BigDecimal): BigDecimal =
      if (x > 0 && x > high) {
        low = 0
        high = x
        BigDecimal(1)
      } else if (x > 0) {
        low = 0
        x / high
      } else if (x < 0 && x < low) {
        high = 0
        low = x
        BigDecimal(-1)
      } else if (x < 0) {
        high = 0
        -x / low
      } else {  // between minUpper and minLower
        low = 0
        high = 0
        0
      }
    cap
  }

  def capPeakTrough(): BigDecimal => BigDecimal = {
    var high: BigDecimal = 0
    var low: BigDecimal = 0
    var prev: BigDecimal = 0
    def cap(x: BigDecimal): BigDecimal = {
      val res = if (x > 0 && x > prev) {
        low = 0
        high = x
        BigDecimal(1)
      } else if (x > 0) {
        low = 0
        x / high
      } else if (x < 0 && x < prev) {
        high = 0
        low = x
        BigDecimal(-1)
      } else if (x < 0) {
        high = 0
        -x / low
      } else {  // between minUpper and minLower
        low = 0
        high = 0
        BigDecimal(0)
      }
      prev = x
      res
    }
    cap
  }

  case class TradeTick(weightedPrice: BigDecimal, open: BigDecimal, close: BigDecimal, high: BigDecimal, low: BigDecimal, volume: BigDecimal)

  def resample(trades: Seq[TradeData], periodMs: Long = MS_IN_MINUTE): Seq[(Long, TradeTick)] =
    if (trades.isEmpty)
      Nil
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
      }.toSeq.sortBy(_._1)
    }

  def ffill(minAndVals: Seq[(Long, TradeTick)]): Seq[(Long, TradeTick)] =
    if (minAndVals.isEmpty)
      Nil
    else {
      val (res, _) = minAndVals.tail.foldLeft((Seq(minAndVals.head), minAndVals.head)) {
        case ((soFar, (prevTs, prevV)), (ts, v)) =>
          val soFar2 = soFar ++ (prevTs+1 to ts-1).map((_, prevV.copy(volume = 0))) :+ (ts, v)
          (soFar2, (ts, v))
      }
      res
    }

  def ma(xs: Seq[BigDecimal], period: Int, maType: MA.Value): BigDecimal = maType match {
    case MA.SMA => sma(xs, period)
    case MA.EMA => ema(xs, period)
  }

  def sma(xs: Seq[BigDecimal], period: Int): BigDecimal =
    if (xs.isEmpty)
      0
    else
      xs.takeRight(period).sum / period

  // https://www.investopedia.com/ask/answers/122314/what-exponential-moving-average-ema-formula-and-how-ema-calculated.asp
  // https://www.investopedia.com/articles/trading/10/simple-exponential-moving-averages-compare.asp
  // https://www.youtube.com/watch?v=ezcwBDsDviE
  def ema(xs: Seq[BigDecimal], period: Int, emaSmoothing: BigDecimal=2): BigDecimal = {
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
}
