package moon


import org.scalatest._
import org.scalatest.matchers.should._
import moon.pattern._
import moon.Dir._


class PatternSpec extends FlatSpec with Matchers with Inside {
  "hsAndLs" should "work" in {
    hsAndLs(Vector(
      asCandle(1.0, 1.1),
      asCandle(2.0, 2.1),
      asCandle(3.0, 3.1),
      asCandle(4.0, 4.1),
      asCandle(5.0, 5.1),
      asCandle(4.1, 4.2),
      asCandle(3.1, 3.2),
      asCandle(2.1, 2.2),
      asCandle(3.2, 3.3),
      asCandle(4.0, 4.1),
      asCandle(3.3, 3.4)
    ), 3) shouldBe Vector((true, asCandle(5.0, 5.1)), (false, asCandle(2.1, 2.2)), (true, asCandle(4.0, 4.1)))
    hsAndLs(Vector(
      asCandle(1.0, 1.1),
      asCandle(2.0, 2.1),
      asCandle(3.0, 3.1),
      asCandle(4.0, 4.1),
      asCandle(5.0, 5.1),
      asCandle(4.1, 4.2),
      asCandle(3.1, 3.2),
      asCandle(2.1, 2.2),
      asCandle(3.2, 3.3),
      asCandle(3.3, 3.4),
      asCandle(4.2, 4.3)
    ), 3) shouldBe Vector((true, asCandle(5.0, 5.1)), (false, asCandle(2.1, 2.2)), (true, asCandle(4.2, 4.3)))  // second high last
    hsAndLs(Vector(
      asCandle(1.0, 1.1),
      asCandle(2.0, 2.1),
      asCandle(3.0, 3.1),
      asCandle(4.0, 4.1),
      asCandle(5.0, 5.1),
      asCandle(4.1, 4.2),
      asCandle(3.1, 3.2),
      asCandle(2.1, 2.2),
      asCandle(3.2, 3.3),
      asCandle(3.3, 3.4),
      asCandle(1.2, 1.3)), 3) shouldBe Vector((true, asCandle(5.0, 5.1)), (false, asCandle(1.2, 1.3)))  // first low last, didn't make 3 legs
  }

  it should "work with hvf.matches1()" in {
    val bullHVF = new HVF(dir = Dir.LongDir, minAmplitudeRatio2_1 = 0.618, minAmplitudeRatio3_2 = 0.5, maxRectRatio1_0 = 0.5)
    val hvfCand = bullHVF.matches1(Vector(
      Candle(high=2.1, low=2.0, open=0, close=0, vwap=0, volume=1, period=0),
      Candle(high=1.1, low=1.0, open=0, close=0, vwap=0, volume=1, period=0),  // l
      Candle(high=9.1, low=9.0, open=0, close=0, vwap=0, volume=1, period=1),  // h
      Candle(high=3.1, low=3.0, open=0, close=0, vwap=0, volume=1, period=2),
      Candle(high=2.1, low=2.0, open=0, close=0, vwap=0, volume=1, period=3),  // rl1
      Candle(high=4.1, low=4.0, open=0, close=0, vwap=0, volume=1, period=4),
      Candle(high=7.1, low=7.0, open=0, close=0, vwap=0, volume=1, period=5),  // rh2
      Candle(high=4.1, low=4.0, open=0, close=0, vwap=0, volume=1, period=6),
      Candle(high=3.1, low=3.0, open=0, close=0, vwap=0, volume=1, period=7),  // rl2
      Candle(high=4.1, low=4.0, open=0, close=0, vwap=0, volume=1, period=8),
      Candle(high=5.1, low=5.0, open=0, close=0, vwap=0, volume=1, period=9),  // rh3
      Candle(high=4.2, low=4.1, open=0, close=0, vwap=0, volume=1, period=10), // rl3
      Candle(high=4.5, low=4.6, open=0, close=0, vwap=0, volume=1, period=11),
    ))
    hvfCand.isRight shouldBe true
    hvfCand.asInstanceOf[Right[String, HVFCandidate]].value.dir shouldBe LongDir
    hvfCand.asInstanceOf[Right[String, HVFCandidate]].value.boundary
  }

  def asCandle(low: Double, high: Double) = Candle(high=high, low=low, open=.0, close=.0, vwap=.0, volume=.0, period=0)
}
