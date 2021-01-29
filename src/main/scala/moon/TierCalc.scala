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

case class TierCalcImpl(dir: Dir.Value,
                        openOrdersWithTiers: Seq[(Double /* price limit as % */, Double /* qty of the pool as % */)],
                       ) extends TierCalc {
  val log = Logger[TierCalc]
  assert(dir == LongDir, "*NOT* catering for shorts!")
  assert(openOrdersWithTiers.map(_._1).size == openOrdersWithTiers.size, s"openOrdersWithTiers not unique!!!\n${openOrdersWithTiers.mkString("\n")}")
  assert(! openOrdersWithTiers.map(_._1).exists(_ > 1.0), s"tier prices need to be < 1, tiers: $openOrdersWithTiers")
  val openOrdersWithTiers_sorted = openOrdersWithTiers.sortBy(-_._1)
  val tiersAsPct = (1.0 +: openOrdersWithTiers_sorted.map(_._1))
    .zip(openOrdersWithTiers_sorted).zipWithIndex
    .map { case ((high, (low, qty)), tier) => Tier(tier=tier, priceLow=low, priceHigh=high, qty=qty)}.sortBy(- _.priceHigh)
  var lastHighestOpenPrice = 0.0
  var tiersWithPrices = Seq.empty[Tier]

  def canOpenWithQty(currPrice: Double, openOrders: Seq[(Double /*price*/, Int /*tier*/)], inOpen: Boolean): Either[String, Tier] = {
    if (openOrders.isEmpty)  // no open orders yet!
      Right(Tier(tier= -1, priceLow=currPrice, priceHigh=Double.PositiveInfinity, qty=tiersAsPct.head.qty))
    else {
      val highestOpenPrice = openOrders.map(_._1).max
      if (highestOpenPrice != lastHighestOpenPrice) {
        // caching
        lastHighestOpenPrice = highestOpenPrice
        tiersWithPrices = tiersAsPct.map(t => t.copy(priceLow = t.priceLow * highestOpenPrice, priceHigh = t.priceHigh * highestOpenPrice))
        log.info(s"InOpen=$inOpen, highestOpenPrice=$highestOpenPrice, starting with new tiers:\n${tiersWithPrices.map(t => s"- ${t.displayFriendly}").mkString("\n")}")
      }
      val matchingTier = if (inOpen)
        tiersWithPrices.find(t => currPrice >= t.priceLow && currPrice < t.priceHigh)
      else
        tiersWithPrices.dropWhile(t => currPrice < t.priceHigh).headOption

      matchingTier match {
        case Some(t) if openOrders.exists(_._2 == t.tier) =>
          Left(s"InOpen=$inOpen, won't open new order for price: $currPrice, orders already exist in tier ${t.displayFriendly}")
        case Some(t) =>
          Right(t)
        case None =>
          Left(s"InOpen=$inOpen, won't open new order for price: $currPrice, no tier suitable out of:\n${tiersWithPrices.map(t => s"- ${t.displayFriendly}").mkString("\n")}")
      }
    }
  }
}

case class Tier(tier: Int, priceLow: Double, priceHigh: Double, qty: Double) {
  lazy val displayFriendly = f"$tier%2d: $qty%.2f @ $priceHigh%.2f ← $priceLow%.2f"
}
