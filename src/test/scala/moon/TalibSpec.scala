package moon

import org.scalatest._
import org.scalatest.matchers.should._
import moon.talib._
import org.joda.time.DateTime

class TalibSpec extends FlatSpec with Matchers with Inside  {
  "TA-LIB" should "work with sma" in {
    sma(Seq(1, 2, 3, 10)) shouldBe BigDecimal(4)
  }

  it should "work with ema" in {
    ema(Seq(1, 2, 3, 10)) shouldBe BigDecimal(5.6128)
  }

  it should "work with ffill" in {
    ffill(Nil) shouldBe Nil
    ffill(Seq((1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)))) shouldBe Seq(
      (1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)))
    ffill(Seq((1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)), (2, TickData(2, 2.1, 2.2, 2.3, 2.4, 22)))) shouldBe Seq(
      (1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TickData(2, 2.1, 2.2, 2.3, 2.4, 22)))
    ffill(Seq((1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)), (3, TickData(3, 3.1, 3.2, 3.3, 3.4, 33)))) shouldBe Seq(
      (1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TickData(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (3, TickData(3, 3.1, 3.2, 3.3, 3.4, 33)))
    ffill(Seq((1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)), (4, TickData(4, 4.1, 4.2, 4.3, 4.4, 44)))) shouldBe Seq(
      (1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TickData(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (3, TickData(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (4, TickData(4, 4.1, 4.2, 4.3, 4.4, 44)))
    ffill(Seq((1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)), (4, TickData(4, 4.1, 4.2, 4.3, 4.4, 44)), (6, TickData(6, 6.1, 6.2, 6.3, 6.4, 66)))) shouldBe Seq(
      (1, TickData(1, 1.1, 1.2, 1.3, 1.4, 11)),
      (2, TickData(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (3, TickData(1, 1.1, 1.2, 1.3, 1.4, 0)),
      (4, TickData(4, 4.1, 4.2, 4.3, 4.4, 44)),
      (5, TickData(4, 4.1, 4.2, 4.3, 4.4, 0)),
      (6, TickData(6, 6.1, 6.2, 6.3, 6.4, 66)))
  }

  it should "work with resample1min" in {
    def minOrder(ts: DateTime, price: BigDecimal, qty: BigDecimal) = LedgerOrder(timestamp=ts, price=price, qty=qty, orderID=null, ordStatus=null, side=null, ordType=null)
    resample1min(Seq(
      minOrder(parseDateTime("2010-01-01T00:00:08.000Z"), 1, 10),
      minOrder(parseDateTime("2010-01-01T00:00:07.000Z"), 2, 20),
      minOrder(parseDateTime("2010-01-01T00:00:57.000Z"), 3, 30),
      minOrder(parseDateTime("2010-01-01T00:00:55.000Z"), 4, 40),
      minOrder(parseDateTime("2010-01-01T00:01:33.000Z"), 5, 50),
      minOrder(parseDateTime("2010-01-01T00:01:44.000Z"), 6, 60),
    )) shouldBe Seq(
      (21038400, TickData(3, 2, 3, 4, 1, 100)),
      (21038401, TickData(BigDecimal("5.545454545454545454545454545454545"), 5, 6, 6, 5, 110)),
    )
  }

  it should "work with bbands" in {
    bbands(Nil) shouldBe (0, 0, 0)
    bbands(Seq(1, 2, 3, 4, 5)) shouldBe (BigDecimal("5.8284271247461902"), 3, BigDecimal("0.1715728752538098"))
    bbands(Seq(1, 2, 3, 4, 5), devUp=3, devDown=1) shouldBe (BigDecimal("7.2426406871192853"), 3, BigDecimal("1.5857864376269049"))
    // FIXME: maType=EMA doesn't validate against python...  Keeping the check for regression
    bbands(Seq(1, 2, 3, 4, 5), devUp=3, devDown=1, maType=MA.EMA) shouldBe (BigDecimal("8.338376836591840247325102880658435"), BigDecimal("3.658436213991769547325102880658435"), BigDecimal("2.098456006458412647325102880658435"))
    // validated with python talib:
    // from talib import BBANDS
    // import numpy as np
    // [x[-1] for x in BBANDS(xs, timeperiod=len(xs), nbdevup=2, nbdevdn=2, matype=0)]  # or matype=1 for ema, note - seems to produce same results as 0 (ie. SMA)
  }
}
