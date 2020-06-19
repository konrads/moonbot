package moon

import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.matchers.should._
import moon.Sentiment._


class StrategySpec extends FlatSpec with Matchers with Inside {
  val bbandsStrategy = new BBandsStrategy(ConfigFactory.parseString(
    """
      |window = 5 // 5 minutes
      |devUp = 3
      |devDown = 1
      |""".stripMargin))

  "Strategy" should "work :)" in {
    // looking for equivalent of TalibSpec:
    // bbands(Seq(na, na, na, 1, 2, 3, 4, 5), devUp=3, devDown=1) shouldBe Some((BigDecimal("7.2426406871192853"), 3, BigDecimal("1.5857864376269049")))
    val l = Ledger()
      .record(OrderBook("t", "a", Seq(OrderBookData("s", parseDateTime("2010-01-01T00:00:00.000Z"), Seq(Seq(1, 2)), Seq(Seq(3, 4))))))
      // ignore first 3
      .record(TradeData(side=null, size=1000, price=1000, tickDirection=null, timestamp=parseDateTime("2010-01-01T00:00:00.000Z")))
      .record(TradeData(side=null, size=1000, price=1000, tickDirection=null, timestamp=parseDateTime("2010-01-01T00:00:00.000Z")))
      .record(TradeData(side=null, size=1000, price=1000, tickDirection=null, timestamp=parseDateTime("2010-01-01T00:00:00.000Z")))
      // calculate on the last 5
      .record(TradeData(side=null, size=1, price=1, tickDirection=null, timestamp=parseDateTime("2010-01-02T00:01:01.000Z")))
      .record(TradeData(side=null, size=1, price=2, tickDirection=null, timestamp=parseDateTime("2010-01-02T00:02:02.000Z")))
      .record(TradeData(side=null, size=1, price=3, tickDirection=null, timestamp=parseDateTime("2010-01-02T00:03:03.000Z")))
      .record(TradeData(side=null, size=1, price=4, tickDirection=null, timestamp=parseDateTime("2010-01-02T00:04:04.000Z")))
      .record(TradeData(side=null, size=1, price=5, tickDirection=null, timestamp=parseDateTime("2010-01-02T00:05:05.000Z")))
    val res = bbandsStrategy.strategize(l)
    res.sentiment shouldBe Neutral
    res.metrics shouldBe Map(
      "data.bbands.score"  -> BigDecimal("-0.8535533905932737494008443621048495"),
      "data.bbands.upper"  -> BigDecimal("7.24264068711928530"),
      "data.bbands.middle" -> BigDecimal(3),
      "data.bbands.lower"  -> BigDecimal("1.58578643762690490"),
    )
  }
}
