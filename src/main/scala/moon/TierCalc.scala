package moon

import moon.Dir._

trait TierCalc {
  def canOpenWithQty(currPrice: Double, existingOpenPrices: Seq[Double]): Either[String, Tier]
}

case class TierCalcImpl(dir: Dir.Value,
                        tiers: Seq[(Double /* price limit as % */, Double /* qty of the pool as % */)],
                       ) extends TierCalc {
  assert(dir == LongDir, "*NOT* catering for shorts!")
  assert(! tiers.map(_._1).exists(_ > 1.0), s"tier prices need to be < 1, tiers: $tiers")
  val tiersAsPct = (1.0 +: tiers.map(_._1)).zip(tiers).zipWithIndex.map { case ((high, (low, qty)), tier) => Tier(tier=tier, priceLow=low, priceHigh=high, qty=qty)}.sortBy(- _.priceHigh)

  def canOpenWithQty(currPrice: Double, existingOpenPrices: Seq[Double]): Either[String, Tier] = {
    if (existingOpenPrices.isEmpty)  // no open orders yet!
      Right(tiersAsPct.head.copy(priceLow = tiersAsPct.head.priceLow * currPrice, priceHigh = tiersAsPct.head.priceHigh * currPrice))
    else if (existingOpenPrices.size >= tiersAsPct.size)  // too many open orders already!
      Left(s"Number of open orders ${existingOpenPrices.size} exceeds tier count ${tiersAsPct.size} for currPrice: $currPrice")
    else {
      val highestOpenPrice = existingOpenPrices.max
      val tiersWithPrices = tiersAsPct.map(t => t.copy(priceLow = t.priceLow * highestOpenPrice, priceHigh = t.priceHigh * highestOpenPrice))
      tiersWithPrices.find(t => currPrice > t.priceLow && currPrice <= t.priceHigh) match {
        case Some(t) =>
          val existingPrice = existingOpenPrices.collectFirst { case p if p > t.priceLow && p <= t.priceHigh => p }
          existingPrice match {
            case Some(p) =>
              Left(s"Tier $t already holds order with price $p (${t.priceLow}, ${t.priceHigh}], no room for currPrice: $currPrice")
            case None =>
              Right(t)
          }
        case None =>
          Left(s"No matching tier for $currPrice in tiers:\n${tiersWithPrices.map(t => s"- $t").mkString("\n")}")
      }
    }
  }
}

case class Tier(tier: Int, priceLow: Double, priceHigh: Double, qty: Double)
