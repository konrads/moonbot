package moon

import scala.collection.Seq

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
  def bbands(xs: Seq[BigDecimal], devUp: BigDecimal=2, devDown: BigDecimal=2, maType: MA.Value=MA.SMA): Option[(BigDecimal, BigDecimal, BigDecimal)] =
    if (xs.isEmpty)
      None
    else {
      val ma_ = ma(xs, maType)
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
      val winsMa = ma(wins, maType)
      val lossesMa = ma(losses, maType)
      val rsi = if (lossesMa == BigDecimal(0))
        BigDecimal(100)
      else
        100 - (100 / (1 + winsMa / lossesMa))
      Some(rsi)
    }
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

  def sma(xs: Seq[BigDecimal]): BigDecimal =
    if (xs.isEmpty)
      0
    else
      xs.sum / xs.size

  def ma(xs: Seq[BigDecimal], maType: MA.Value): BigDecimal = maType match {
    case MA.SMA => sma(xs)
    case MA.EMA => ema(xs)
  }

  // http://stackoverflow.com/questions/24705011/how-to-optimise-a-exponential-moving-average-algorithm-in-php
  def ema(xs: Seq[BigDecimal], emaSmoothing: BigDecimal=2): BigDecimal = {
    if (xs.isEmpty)
      0
    else {
      val k = emaSmoothing / (xs.length + 1)
      val mean = xs.sum / xs.length
      xs.foldLeft(mean)(
        (last, s) => (1 - k) * last + k * s
      )
    }
  }
}
