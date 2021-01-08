package moon

import moon.Dir._
import org.scalatest._
import org.scalatest.matchers.should._

class TierCalcSpec extends FlatSpec with Matchers with Inside {
  def createTierCalc = TierCalcImpl(dir=LongDir, tiers=Seq((0.95, 1487), (0.9025, 1190.0), (0.857, 952.0), (0.815, 762.0), (0.774, 609.0)))

  "TierCalc" should "calculate tiers" in {
    // tiers: (high, low, amount)
    //        (10.0,9.5,0.14873869585911467),
    //        (9.5,9.025,0.11899095668729175),
    //        (9.025,8.57375,0.09519276534983341),
    //        (8.573749999999999,8.145062499999998,0.07615421227986673),
    //        (8.145062499999998,7.737809374999998,0.06092336982389338)
    createTierCalc.canOpenWithQty(10.0,  Nil) shouldBe Right(Tier(0, 10, 9.5, 1487.0))
    createTierCalc.canOpenWithQty(10.0,  Seq(10.0)) shouldBe Left("Tier Tier(0,10.0,9.5,1487.0) already holds order with price 10.0 (9.5, 10.0], no room for currPrice: 10.0")
    createTierCalc.canOpenWithQty(9.6,   Seq(10.0)) shouldBe Left("Tier Tier(0,10.0,9.5,1487.0) already holds order with price 10.0 (9.5, 10.0], no room for currPrice: 9.6")

    createTierCalc.canOpenWithQty(9.5,   Seq(10.0, 9.1)) shouldBe Left("Tier Tier(1,9.5,9.025,1190.0) already holds order with price 9.1 (9.025, 9.5], no room for currPrice: 9.5")
    createTierCalc.canOpenWithQty(9.5,   Seq(10.0)) shouldBe Right(Tier(1, 9.5, 9.025, 1190.0))
    createTierCalc.canOpenWithQty(9.026, Seq(10.0)) shouldBe Right(Tier(1, 9.5, 9.025, 1190.0))

    createTierCalc.canOpenWithQty(9.024, Seq(10.0, 9.3, 9.0)) shouldBe Left("Tier Tier(2,9.025,8.57,952.0) already holds order with price 9.0 (8.57, 9.025], no room for currPrice: 9.024")
    createTierCalc.canOpenWithQty(9.024, Seq(10.0, 9.3)) shouldBe Right(Tier(2, 9.025, 8.57, 952.0))
    createTierCalc.canOpenWithQty(8.574, Seq(10.0, 9.3)) shouldBe Right(Tier(2, 9.025, 8.57, 952.0))

    createTierCalc.canOpenWithQty(8.4,   Seq(10.0, 9.3, 9.0, 8.3)).left.get should include("already holds order with price")  // Not checking the message as double has .999... issues
    createTierCalc.canOpenWithQty(8.56,  Seq(10.0, 9.3, 9.0)) shouldBe Right(Tier(3, 8.57, 8.149999999999999, 762.0))
    createTierCalc.canOpenWithQty(8.16,  Seq(10.0, 9.3, 9.0)) shouldBe Right(Tier(3, 8.57, 8.149999999999999, 762.0))

    createTierCalc.canOpenWithQty(8.0,   Seq(10.0, 9.3, 9.0, 8.3, 7.9)) shouldBe Left("Number of open orders 5 exceeds tier count 5 for currPrice: 8.0")
    createTierCalc.canOpenWithQty(8.145, Seq(10.0, 9.3, 9.0, 8.3)) shouldBe Right(Tier(4, 8.149999999999999, 7.74, 609.0))
    createTierCalc.canOpenWithQty(7.75, Seq(10.0, 9.3, 9.0, 8.3)) shouldBe Right(Tier(4, 8.149999999999999, 7.74, 609.0))

    createTierCalc.canOpenWithQty(7.3,   Seq(10.0, 9.3, 9.0, 8.3)).left.get should include("No matching tier for")  // no price below 7.737 results in a trade
    createTierCalc.canOpenWithQty(6.9,   Seq(10.0)).left.get should include("No matching tier for")  // no price below 7.737 results in a trade
    createTierCalc.canOpenWithQty(11.0,  Seq(10.0, 9.3, 9.0, 8.3)).left.get should include("No matching tier for")  // no price over 10 results in a trade
    createTierCalc.canOpenWithQty(12.0,  Seq(10.0)).left.get should include("No matching tier for")  // no price over 10 results in a trade
  }
}
