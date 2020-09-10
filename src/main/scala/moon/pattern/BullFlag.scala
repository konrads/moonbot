package moon.pattern

import moon.Candle

class BullFlag(
                minFlagWidth: Int,
                minPoleGradient: Double,
                maxFlag2PoleRatio: Double = 0.33,
                minBreakoutVolIncrease: Double = 1.5) {
  /**
   *                      B
   *        H
   *                      |
   *        |\           /
   *       /  ---       |
   *       |     \      |
   *       |      ----  /
   *      /           \/
   *     |
   *     |            FL
   *    /         (flag low)
   *   |
   *   |
   *   /
   *
   *   L
   *
   *     ▒▒
   *   ▒▒▒▒               ▒
   *   ▒▒▒▒              ▒▒
   *   ▒▒▒▒              ▒▒
   *   ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒
   *
   *                      ^
   *                      |
   *           higher volume on breakout
   *
   *  Bull flag starts of with a near to vertical pole up to H, then a channel down to FL (flag low).
   *  Breakout happens at FL, with prices rising above the channel, and volume bigger than previous channel volume.
   *
   *  Criteria:
   *  - L -> H defines the pole, steep gradient
   *  - H -> FL defines the flag, gentle gradient
   *
   *
   *
   *
   *  The channel is defined by:
   *  - best fit line on highs vs best fit line on lows. They must not cross
   */
  def patternMatch(candles: Seq[Candle]): Either[String, BullFlagMatch] = {
    val candles2 = candles.dropRight(1).zipWithIndex
    val (breakout, breakoutInd) = (candles.last, candles.length-1)
    val (lCandle, lInd) = min(candles2)(_.low)
    val candles3 = candles2.drop(lInd)
    val (hCandle, hInd) = max(candles3)(_.high)

    if (hCandle.high > breakout.high)
      Left(s"Breakout not yet met ${hCandle.high} > ${breakout.high}")
    else if (breakoutInd - hInd < minFlagWidth)
      Left(s"Funnel too narrow ${breakoutInd - hInd}")
    else {
      // determine quality of the pole
      val poleGradients = for (i <- lInd to hInd) yield {
        val pole = candles.slice(i, hInd)
        val poleL = pole.head.low
        val poleHeight = hCandle.high - poleL
        val poleGradient = poleHeight / hCandle.high / pole.length
        (poleGradient, poleHeight, i)
      }
      val bestPoleGradient = poleGradients.dropWhile(_._1 < minPoleGradient).lastOption
      bestPoleGradient match {
        case None =>
          Left(s"Unmatched best pole gradient: $minPoleGradient, max: ${bestPoleGradient.max._1}")
        case Some((poleGradient, poleHeight, _)) =>
          val flag = candles2.drop(lInd)
          val flagL = min(flag)(_.low)._1.low
          val flagHeight = hCandle.high - flagL
          val flag2PoleRatio = flagHeight / poleHeight
          if (flag2PoleRatio < maxFlag2PoleRatio)
            Left(s"Insufficient flag2PoleRatio: $flag2PoleRatio vs $maxFlag2PoleRatio")
          else {
            val flagVolumeAvg = flag.map(_._1.volume).sum / flag.length
            val breakoutVolIncrease = breakout.volume / flagVolumeAvg
            if (breakoutVolIncrease < flagVolumeAvg)
              Left(s"Insufficient breakout volume: ${breakout.volume} vs $flagVolumeAvg * $minBreakoutVolIncrease")
            else
              Right(BullFlagMatch(lCandle, hCandle, breakout, poleGradient, flag2PoleRatio, breakoutVolIncrease))
          }
      }
    }
  }
}

case class BullFlagMatch(poleStart: Candle, flagStart: Candle, breakout: Candle, poleGradient: Double, flag2PoleRatio: Double, breakoutVolIncrease: Double)
