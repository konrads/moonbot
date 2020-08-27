package moon.pattern

class BullFlag {
  /**
   *        H
   *
   *        |\
   *       /  \
   *       |   -
   *       |    \
   *      /      --
   *     |         \    |
   *     |          ----
   *    /
   *   |             FL (flag low)
   *   |
   *   /
   *
   *   L
   *
   *     ▒▒
   *   ▒▒▒▒
   *   ▒▒▒▒
   *   ▒▒▒▒             ▒
   *   ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒
   *
   *                     ^
   *                     |
   *        higher volume on breakout
   *
   *  Bull flag starts of with a near to vertical pole up to H, then a channel down to FL (flag low).
   *  Breakout happens at FL, with prices rising above the channel, and volume bigger than previous channel volume.
   *
   *  The pole is established by:
   *  - L -> H is steep gradient
   *
   *  The channel is defined by:
   *  - best fit line on highs vs best fit line on lows. They must not cross
   */
  def patternMatch(lows: Seq[Double], highs: Seq[Double], vwaps: Seq[Double], volumes: Seq[Double], currPrice: Double, currVolume: Double): BullFlagMatch = {



    ???
  }



}

case class BullFlagMatch()
