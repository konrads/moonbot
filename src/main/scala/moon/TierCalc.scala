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
  val firstTradeQty = tradePoolQty / (0 until tierCnt).map(math.pow(tierQtyPerc, _)).sum
  val qtyTiers = (0 until tierCnt).map(math.pow(firstTradeQty, _))

  override def canOpenWithQty(currPrice: Double, existingOpenPrices: Seq[Double]): Option[Double /*qty*/] = {
    if (existingOpenPrices.isEmpty)  // no open orders yet!
      Some(qtyTiers.head)
    else if (existingOpenPrices.size > tierCnt)  // too many open orders already!
      None
    else {
      val openPrices = existingOpenPrices.sorted
      val highestOpenPrice = openPrices.last

      val tiers = (0 until tierCnt).map { tier =>
        val priceHigh = highestOpenPrice * math.pow(tierPricePerc, tier)
        val priceLow = priceHigh * tierPricePerc
        val qty = qtyTiers(tier)
        val haveExistingOrder = openPrices.exists(p => priceHigh <= p && p < priceLow)
        val currPriceInThisTier = priceHigh <= currPrice && currPrice < priceLow
        (currPriceInThisTier && haveExistingOrder, qty)
      }

      if (tiers.map(_._1).exists(x => x))
        None
      else
        Some(tiers.head._2)
    }
  }
}
