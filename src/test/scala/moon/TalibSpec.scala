package moon

import moon.talib._
import org.joda.time.DateTime
import org.scalatest._
import org.scalatest.matchers.should._

class TalibSpec extends FlatSpec with Matchers with Inside {
  "TA-LIB" should "work with sma" in {
    sma(Vector(1, 2, 3, 10), 4) shouldBe 4.0
    sma(Vector(3, 4, 2, 5, 7), 5) shouldBe 4.2
    sma(Vector(1, 2, 3, 4, 2, 5, 7), 5) shouldBe 4.2
  }

  it should "work with ema" in {
    ema(Vector(1, 2, 3, 10), 4) shouldBe 4.0
    ema(Vector(3, 4, 2, 5, 7), 5) shouldBe 4.2
    ema(Vector(1, 2, 3, 4, 2, 5, 7), 5) shouldBe 4.511111111111111111111111111111110
  }

  it should "work with ffill" in {
    ffill(Nil) shouldBe Nil
    ffill(Vector((1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)))) shouldBe Vector(
      (1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)))
    ffill(Vector((1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)), (2, TradeTick(2, 2.1, 2.2, 2.3, 2.4, 22)))) shouldBe Vector(
      (1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TradeTick(2, 2.1, 2.2, 2.3, 2.4, 22)))
    ffill(Vector((1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)), (3, TradeTick(3, 3.1, 3.2, 3.3, 3.4, 33)))) shouldBe Vector(
      (1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (3, TradeTick(3, 3.1, 3.2, 3.3, 3.4, 33)))
    ffill(Vector((1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)), (4, TradeTick(4, 4.1, 4.2, 4.3, 4.4, 44)))) shouldBe Vector(
      (1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (3, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (4, TradeTick(4, 4.1, 4.2, 4.3, 4.4, 44)))
    ffill(Vector((1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)), (4, TradeTick(4, 4.1, 4.2, 4.3, 4.4, 44)), (6, TradeTick(6, 6.1, 6.2, 6.3, 6.4, 66)))) shouldBe Vector(
      (1, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (3, TradeTick(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (4, TradeTick(4, 4.1, 4.2, 4.3, 4.4, 44)),
      (5, TradeTick(4, 4.1, 4.2, 4.3, 4.4, 0)),
      (6, TradeTick(6, 6.1, 6.2, 6.3, 6.4, 66)))
  }

  it should "work with resample" in {
    def minTradeData(ts: DateTime, price: Double, size: Double) = TradeData(timestamp = ts, price = price, size = size, side = null, tickDirection = null)

    resample(Vector(
      minTradeData(parseDateTime("2010-01-01T00:00:08.000Z"), 1, 10),
      minTradeData(parseDateTime("2010-01-01T00:00:07.000Z"), 2, 20),
      minTradeData(parseDateTime("2010-01-01T00:00:57.000Z"), 3, 30),
      minTradeData(parseDateTime("2010-01-01T00:00:55.000Z"), 4, 40),
      minTradeData(parseDateTime("2010-01-01T00:01:33.000Z"), 5, 50),
      minTradeData(parseDateTime("2010-01-01T00:01:59.000Z"), 6, 60),
    )) shouldBe Vector(
      (-1, TradeTick(3, 2, 3, 4, 1, 100)),
      (0, TradeTick(5.545454545454545454545454545454545, 5, 6, 6, 5, 110)),
    )
  }

  it should "work with bbands" in {
    bbands(Nil) shouldBe None
    bbands(Vector(1, 2, 3, 4, 5)) shouldBe Some((5.8284271247461902, 3.0, 0.1715728752538097))
    bbands(Vector(1, 2, 3, 4, 5), devUp = 3, devDown = 1) shouldBe Some((7.2426406871192853, 3.0, 1.5857864376269049))
    bbands(Vector(1, 2, 3, 4, 5), devUp = 3, devDown = 1, maType = MA.EMA) shouldBe Some((7.2426406871192853, 3.0, 1.5857864376269049))
    bbands(Vector(9357.498906886614329722746695816357, 9357.234543433969253565475087979255)) shouldBe Some((9357.631088612938, 9357.366725160293, 9357.102361707648))
    // validated with python talib:
    // from talib import BBANDS
    // import numpy as np
    // [x[-1] for x in BBANDS(xs, timeperiod=len(xs), nbdevup=2, nbdevdn=2, matype=0)]  # or matype=1 for ema, note - seems to produce same results as 0 (ie. SMA)
  }

  it should "work with rsi" in {
    rsi(Nil) shouldBe None
    rsi(Vector(1)) shouldBe None // need at least 2
    rsi(Vector(1, 2)) shouldBe Some(100)
    rsi(Vector(1, 2, 3, 4, 5)) shouldBe Some(100)
    rsi(Vector(2, 1)) shouldBe Some(0)
    rsi(Vector(5, 4, 3, 2, 1)) shouldBe Some(0)
    rsi(Vector(1, 2, 3, 10, 6, 7)) shouldBe Some(71.42857142857142857142857142857143)
    // validated with python talib:
    // from talib import RSI
    // import numpy as np
    // RSI(xs, timeperiod=len(xs)-1)[-1]
  }

  it should "work with macd" in {
    macd(Nil) shouldBe None
    macd(Vector(1, 2, 3, 4), slow = 5, fast = 3, signal = 1) shouldBe None // need at least 5
    macd(Vector(1, 2, 3, 4, 5, 6), slow = 5, fast = 3, signal = 2) shouldBe Some((1, 1, 0))
    macd(Vector(10, 2, 30, 4, 50, 6), slow = 5, fast = 3, signal = 2) shouldBe Some((2.1999999999999993, 5.5, -3.3000000000000007))
    macd(Vector(10, 2, 30, 4, 50, 6, 70, 8, 90), slow = 5, fast = 3, signal = 2) shouldBe Some((11.341666666666661, 8.738888888888884, 2.6027777777777778))
    macd(Vector(10, 2, 30, 4, 50, 6, 70, 8, 90), slow = 5, fast = 3, signal = 4) shouldBe Some((11.341666666666661, 7.874166666666664, 3.4674999999999976))
    macd(Vector(10, 2, 30, 4, 50, 6, 70, 8, 90), slow = 5, fast = 3, signal = 5) shouldBe Some((11.341666666666661, 6.718333333333331, 4.62333333333333))
    macd(Vector(10, 2, 30, 4, 50, 6, 70, 8, 90), slow = 5, fast = 3, signal = 6) shouldBe None
    // validated with python talib:
    // from talib import MACD
    // import numpy as np
    // [x[-1] for x in MACD(xs, fastperiod=fast, slowperiod=slow, signalperiod=1)]
  }

//  it should "work with capProportionalExtremes" in {
//    val capFun = capProportionalExtremes()
//    // going up > 0
//    capFun(0) shouldBe 0
//    capFun(0.05) shouldBe 1
//    capFun(0.6) shouldBe 1
//    capFun(1) shouldBe 1
//    capFun(.8) shouldBe 0.8
//    capFun(.85) shouldBe 0.85
//    capFun(1.5) shouldBe 1
//    capFun(1) shouldBe 2/3.0
//    // going down > 0
//    capFun(0.6) shouldBe 0.39999999999999997
//    capFun(0.5) shouldBe 1/3.0
//    capFun(0.1) shouldBe 1/15.0
//    // going down < 0
//    capFun(-0.3) shouldBe -1
//    capFun(-0.6) shouldBe -1
//    capFun(-1) shouldBe -1
//    capFun(-0.8) shouldBe -0.8
//    capFun(-0.9) shouldBe -0.9
//    capFun(-2.5) shouldBe -1
//    capFun(-1.5) shouldBe -0.6
//    // going up < 0
//    capFun(-1) shouldBe -0.4
//    capFun(-.5) shouldBe -0.2
//    capFun(-.4) shouldBe -0.16
//    // peek-a-boo above 0
//    capFun(0.4) shouldBe 1
//    capFun(0.5) shouldBe 1
//    capFun(0.4) shouldBe 0.8
//  }

  it should "work with capPeakTrough" in {
    val capFun = capPeakTrough()
    // going up > 0
    capFun(0) shouldBe 0
    capFun(0.05) shouldBe 1
    capFun(0.6) shouldBe 1
    capFun(1) shouldBe 1
    capFun(.8) shouldBe 0.8
    capFun(.85) shouldBe 1
    capFun(1.5) shouldBe 1
    capFun(1) shouldBe 2/3.0
    // going down > 0
    capFun(0.6) shouldBe 0.39999999999999997
    capFun(0.5) shouldBe 1/3.0
    capFun(0.1) shouldBe 1/15.0
    // going down < 0
    capFun(-0.3) shouldBe -1
    capFun(-0.6) shouldBe -1
    capFun(-1) shouldBe -1
    capFun(-0.8) shouldBe -0.8
    capFun(-0.9) shouldBe -1
    capFun(-2.5) shouldBe -1
    capFun(-1.5) shouldBe -0.6
    // going up < 0
    capFun(-1) shouldBe -0.4
    capFun(-.5) shouldBe -0.2
    capFun(-.4) shouldBe -0.16
    // peek-a-boo above 0
    capFun(0.4) shouldBe 1
    capFun(0.5) shouldBe 1
    capFun(0.4) shouldBe 0.8
  }

  it should "work with polyfit" in {
    polyfit(Vector(1, 1, 1, 1, 1)) shouldBe(0, 1)
    polyfit(Vector(1, 2, 3, 4, 5)) shouldBe(1, 1)
    polyfit(Vector(2, 4, 6, 8, 10)) shouldBe(2, 2)
    // validated with np:
    // xs = np.array([2., 4., 0., 6., -2., 4., 0., 2.]); np.polyfit(range(len(xs)), xs, 1)
    polyfit(Vector(2, 4, 0, 6, -2, 4, 0, 2)) shouldBe(-0.1904761904761904761904761904761905, 2.666666666666666666666666666666667)
    polyfit(Vector(10, 4, 50, -66, 1.1111, -3.333, 0, 200.3)) shouldBe(14.514429761904761, -26.290741666666662)
    polyfit(Vector(1, 11)) shouldBe(10, 1)  // testing min of 2 points
  }

  it should "work with indecreasing" in {
    indecreasingSlope(Vector(1, 1.01, 1.02, 1.04, 1.06, 1.1, 1.15, 1.2)) shouldBe None
    indecreasingSlope(Vector(1, 1.01, 1.02, 1.04, 1.06, 1.1, 1.15, 1.2, 1.35, 1.5)) shouldBe Some(Vector(0.04939393939393939393939393939393939, 0.1, 0.15000000000000002))
    indecreasingSlope(Vector(100, 1.01, 1.02, 1.04, 1.06, 1.1, 1.15, 1.2, 1.35, 1.5)) shouldBe None  // not increasing!
    indecreasingSlope(Vector(1, 1.01, 1.02, 1.04, 1.06, 1.1, 1.15, 1.2, 1.35, 1.5, 1.7, 2.0, 2.5)) shouldBe Some(Vector(0.1449696969696969696969696969696970, 0.27999999999999997, 0.4))
    // few negatives
    indecreasingSlope(Vector(-1, -1.01, -1.02, -1.04, -1.06, -1.1, -1.15, -1.2, -1.35, -1.5, -1.7, -2.0, -2.5)) shouldBe Some(Vector(-0.1449696969696969696969696969696970, -0.27999999999999997, -0.4))
    indecreasingSlope(Vector(-1, -1.01, -1.02, -1.04, -1.06, -1.1, -1.15, -1.2, -1.35, -1.5)) shouldBe Some(Vector(-0.04939393939393939393939393939393939, -0.1, -0.15000000000000002))
    indecreasingSlope(Vector(-100, -1.01, -1.02, -1.04, -1.06, -1.1, -1.15, -1.2, -1.35, -1.5)) shouldBe None  // not decreasing!
    // combos of positive and negative
    indecreasingSlope(Vector(1.04, 1.06, -1.1, -1.15, -1.2, -1.35, -1.5, -1.7, -2.0, -2.5)) shouldBe None
  }

  it should "work with atr" in {
    val high  = Seq(10.0,100.0,20.0,30.0,50.0,60.0)
    val low   = Seq(8.0,80.0,18.0,28.0,38.0,48.0)
    val close = Seq(9.0,90.0,19.0,29.0,39.0,49.0)

    atr(high=high, low=low, close=close, timeperiod=1) shouldBe Some(21.0)
    atr(high=high, low=low, close=close, timeperiod=2) shouldBe Some(27.3125)
    atr(high=high, low=low, close=close, timeperiod=3) shouldBe Some(37.44444444444444)
    atr(high=high, low=low, close=close, timeperiod=4) shouldBe Some(41.8125)
    atr(high=high, low=low, close=close, timeperiod=5) shouldBe Some(43.2)
  }
}
