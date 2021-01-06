package moon

import moon.Dir._

trait TierCalc {
  def canOpenWithQty(currPrice: Double, existingOpenPrices: Seq[Double]): Either[String, Tier]
}

case class TierCalcImpl(dir: Dir.Value,
                        tradePoolQty: Double=50,
                        tierCnt: Int=5,
                        tierPricePerc: Double=0.95 /* how much the price decreases per tier */,
                        tierQtyPerc: Double=0.8    /* how much qty decreases per tier*/,
                       ) extends TierCalc {
  assert(dir == LongDir, "*NOT* catering for shorts!")
  val firstTradeQty = tradePoolQty / (0 until tierCnt).map(math.pow(tierQtyPerc, _)).sum
  val qtyTiers = (0 until tierCnt).map(n => round(firstTradeQty * math.pow(tierQtyPerc, n), 0))

  override def canOpenWithQty(currPrice: Double, existingOpenPrices: Seq[Double]): Either[String, Tier] = {
    if (existingOpenPrices.isEmpty)  // no open orders yet!
      Right(Tier(
        tier = 0,
        priceHigh = currPrice,
        priceLow = currPrice * tierPricePerc,
        qty = qtyTiers.head
      ))
    else if (existingOpenPrices.size >= tierCnt)  // too many open orders already!
      Left(s"Number of open orders ${existingOpenPrices.size} exceeds tier count $tierCnt for currPrice: $currPrice")
    else {
      val highestOpenPrice = existingOpenPrices.max
      val tiers = for(tier <- 0 until tierCnt) yield {
        val priceHigh = highestOpenPrice * math.pow(tierPricePerc, tier)
        val priceLow = priceHigh * tierPricePerc
        Tier(
          tier = tier,
          priceHigh = priceHigh,
          priceLow = priceLow,
          qty = qtyTiers(tier))
      }

      for(tier <- tiers) {
        val currPriceIntThisTier = currPrice > tier.priceLow && currPrice <= tier.priceHigh
        if (currPriceIntThisTier) {
          val existingPrice = existingOpenPrices.collectFirst{case p if p > tier.priceLow && p <= tier.priceHigh => p}
          existingPrice match {
            case Some(p) =>
              return Left(s"Tier $tier already holds order with price $p (${tier.priceLow}, ${tier.priceHigh}], no room for currPrice: $currPrice")
            case None =>
              return Right(tier)
          }
        }
      }
      Left(s"No matching tier for $currPrice in tiers:\n${tiers.map(t => s"- $t").mkString("\n")}")
    }
  }
}

case class Tier(tier: Int, priceHigh: Double, priceLow: Double, qty: Double)
