package moon

import com.typesafe.scalalogging.Logger
import moon.Dir._

trait TierCalc {
  /**
   * If open:     find the tier where currPrice is higher then lowPrice, but lower than previous tier,
   * In not open: find the tier just below
   *
   * If no orders exist exist - return Right(first new tier)
   * If no tier found         - return Left
   */
  def canOpenWithQty(currPrice: Double, openOrders: Seq[(Double /*price*/, Int /*tier*/)], inOpen: Boolean): Either[String, Tier]
}

case class TierCalcImpl(dir: Dir.Value, openOrdersWithTiers: Seq[(Double /* price limit as % */, Double /* qty */)]) extends TierCalc {
  val log = Logger[TierCalc]
  assert(dir == LongDir, "*NOT* catering for shorts!")
  assert(openOrdersWithTiers.map(_._1).toSet.size == openOrdersWithTiers.size, s"openOrdersWithTiers not unique!!!\n${openOrdersWithTiers.mkString("\n")}")
  assert(! openOrdersWithTiers.map(_._1).exists(_ > 1.0), s"tier prices need to be <= 1, tiers: $openOrdersWithTiers")
  val openOrdersWithTiers_sorted = openOrdersWithTiers.sortBy(-_._1)
  val tiersAsPct = (Double.PositiveInfinity +: openOrdersWithTiers_sorted.map(_._1))
    .zip(openOrdersWithTiers_sorted).zipWithIndex
    .map { case ((high, (low, qty)), tier) => Tier(tier=tier, priceLow=low, priceHigh=high, qty=qty)}.sortBy(- _.priceHigh)
  assert(! tiersAsPct.exists(_.qty % 100.0 != 0), s"Tiers qty must be multiple of 100s, or risk getting `Invalid leavesQty` http error.... Tiers: ${tiersAsPct}")
  var lastHighestOpenPrice = 0.0
  var tiersWithPrices = Seq.empty[Tier]

  def canOpenWithQty(currPrice: Double, openOrders: Seq[(Double /*price*/, Int /*tier*/)], inOpen: Boolean /* NOTE: Unused!!!*/): Either[String, Tier] = {
    if (openOrders.isEmpty)  // no open orders yet!
      Right(tiersAsPct.head.copy(priceLow=math.floor(tiersAsPct.head.priceLow * currPrice)))
    else {
      val highestOpenPrice = openOrders.map(_._1).max
      if (highestOpenPrice != lastHighestOpenPrice) {
        // caching
        lastHighestOpenPrice = highestOpenPrice
        tiersWithPrices = tiersAsPct.map(t => t.copy(priceLow = math.floor(t.priceLow * highestOpenPrice), priceHigh = math.floor(t.priceHigh * highestOpenPrice)))
        log.info(s"New highestOpenPrice=$highestOpenPrice, starting with new tiers:\n${tiersWithPrices.map(t => s"- ${t.displayFriendly}").mkString("\n")}")
      }
      val matchingTier = tiersWithPrices.find(t => t.priceLow <= currPrice && currPrice < t.priceHigh)
      matchingTier match {
        case Some(t) if openOrders.exists(_._2 == t.tier) =>
          Left(s"Won't open new order for price: $currPrice, orders already exist in tier ${t.displayFriendly}")
        case Some(t) =>
          Right(t)
        case None =>
          Left(s"Won't open new order for price: $currPrice, no tier suitable out of:\n${tiersWithPrices.map(t => s"- ${t.displayFriendly}").mkString("\n")}")
      }
    }
  }
}

case class Tier(tier: Int, priceLow: Double, priceHigh: Double, qty: Double) {
  lazy val displayFriendly = f"$tier%2d: $qty%.2f @ $priceLow%.2f → $priceHigh%.2f"
}
