package moon

import moon.Dir._
import org.scalatest._
import org.scalatest.matchers.should._

class TierCalcSpec extends FlatSpec with Matchers with Inside {
  def createTierCalc = TierCalcImpl(tradePoolQty=5000, dir=LongDir)

  "TierCalc" should "calculate tiers" in {
    // tiers: (high, low, amount)
    //        (10.0,9.5,0.14873869585911467),
    //        (9.5,9.025,0.11899095668729175),
    //        (9.025,8.57375,0.09519276534983341),
    //        (8.573749999999999,8.145062499999998,0.07615421227986673),
    //        (8.145062499999998,7.737809374999998,0.06092336982389338)
    createTierCalc.canOpenWithQty(10.0,  Nil) shouldBe Some(1487.0)
    createTierCalc.canOpenWithQty(10.0,  Seq(10.0)) shouldBe None
    createTierCalc.canOpenWithQty(9.6,   Seq(10.0)) shouldBe None

    createTierCalc.canOpenWithQty(9.5,   Seq(10.0, 9.1)) shouldBe None
    createTierCalc.canOpenWithQty(9.5,   Seq(10.0)) shouldBe Some(1190.0)
    createTierCalc.canOpenWithQty(9.026, Seq(10.0)) shouldBe Some(1190.0)

    createTierCalc.canOpenWithQty(9.024, Seq(10.0, 9.3, 9.0)) shouldBe None
    createTierCalc.canOpenWithQty(9.024, Seq(10.0, 9.3)) shouldBe Some(952.0)
    createTierCalc.canOpenWithQty(8.574, Seq(10.0, 9.3)) shouldBe Some(952.0)

    createTierCalc.canOpenWithQty(8.4,   Seq(10.0, 9.3, 9.0, 8.3)) shouldBe None
    createTierCalc.canOpenWithQty(8.573, Seq(10.0, 9.3, 9.0)) shouldBe Some(762.0)
    createTierCalc.canOpenWithQty(8.146, Seq(10.0, 9.3, 9.0)) shouldBe Some(762.0)

    createTierCalc.canOpenWithQty(8.0,   Seq(10.0, 9.3, 9.0, 8.3, 7.9)) shouldBe None
    createTierCalc.canOpenWithQty(8.145, Seq(10.0, 9.3, 9.0, 8.3)) shouldBe Some(609.0)
    createTierCalc.canOpenWithQty(7.738, Seq(10.0, 9.3, 9.0, 8.3)) shouldBe Some(609.0)

    createTierCalc.canOpenWithQty(7.3,   Seq(10.0, 9.3, 9.0, 8.3, 7.9)) shouldBe None  // no price below 7.737 results in a trade
    createTierCalc.canOpenWithQty(6.9,   Seq(10.0)) shouldBe None  // no price below 7.737 results in a trade
    createTierCalc.canOpenWithQty(11.0,  Seq(10.0, 9.3, 9.0, 8.3, 7.9)) shouldBe None  // no price over 10 results in a trade
    createTierCalc.canOpenWithQty(12.0,  Seq(10.0)) shouldBe None  // no price over 10 results in a trade
  }
}
