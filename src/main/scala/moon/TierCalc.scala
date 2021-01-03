package moon

import moon.Dir._

trait TierCalc {
  def canOpenWithQty(currPrice: Double, existingOpenPrices: Seq[Double]): Option[Double /*qty*/]
}

case class TierCalcImpl(dir: Dir.Value,
                        tradePoolQty: Double=0.5,
                        tierCnt: Int=5,
                        tierPricePerc: Double=0.95 /* how much the price decreases per tier */,
                        tierQtyPerc: Double=0.8    /* how much qty decreases per tier*/,
                       ) extends TierCalc {
  assert(dir == LongDir, "*NOT* catering for shorts!")
  type Price = Double
  type Quantity = Double
  val firstTradeQty = tradePoolQty / (0 until tierCnt).map(math.pow(tierQtyPerc, _)).sum
  val qtyTiers = (0 until tierCnt).map(n => firstTradeQty * math.pow(tierQtyPerc, n))

  override def canOpenWithQty(currPrice: Price, existingOpenPrices: Seq[Price]): Option[Quantity] = {
    if (existingOpenPrices.isEmpty)  // no open orders yet!
      Some(qtyTiers.head)
    else if (existingOpenPrices.size >= tierCnt)  // too many open orders already!
      None
    else {
      val openPrices = existingOpenPrices.sorted
      val highestOpenPrice = openPrices.last

      for(tier <- 0 until tierCnt) {
        val priceHigh = highestOpenPrice * math.pow(tierPricePerc, tier)
        val priceLow = priceHigh * tierPricePerc
        val currPriceIntThisTier = currPrice > priceLow && currPrice <= priceHigh
        if (currPriceIntThisTier) {
          val haveExistingOrder = openPrices.exists(p => p > priceLow && p <= priceHigh)
          if (haveExistingOrder)
            return None
          else
            return Some(qtyTiers(tier))
        }
      }
      None
    }
  }
}
