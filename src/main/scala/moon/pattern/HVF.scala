package moon.pattern

import com.typesafe.scalalogging.Logger
import moon.{Candle, Dir}
import moon.Dir.{LongDir, ShortDir}
import moon.talib.ma_mom

class HVF(dir: Dir.Value, minAmplitudeRatio2_1: Double, minAmplitudeRatio3_2: Double, maxRectRatio1_0: Double) {
  /**
   * HVF implementation (drawn for Long)
   *
   *                                             H1                                                                          /
   *                                            ____                                                                        /                                           ^
   *                                           /    \                                                                       |                                           |
   *                                          /      \                   RH2                                                /                      ^                    |
   *                                         /        \                 ____                                               |                       |                    |
   *                                         |         \               /    \             RH3                              /    trigger RH3        |                    |
   *                                        /          |              /      \           ____                             |     __________         |                    |
   *                                       /           \              |       \         /    \                 __      __/                         |                    |
   *                                      /            |             /        |        /      \      ___      /  \    /         axis (mid-funnel)  | Interim            | Full
   *                                      |            \            |         \       /        \    /   \    /    \  /          ----------         | target: RH2-RL2    | target: H1-RL1
   *                                      /             \          /           \      |        \   /     ----      --                              |                    |
   *                                     /               \        /             \     /         ---                             ----------         |                    |
   *                                    /                 \       |              \   /          RL3                             stoploss RL3       |                    |
   *                                   /                   \     /                ---                                                              v                    |
   *                                  /                     \   /                 RL2                                                                                   |
   *                                 |                       ---                                                                                                        V
   *                                /                        RL1
   *                        __     |
   *                       /  \   /
   *                      /   |   |
   *                     /    |   |
   *                    /     |   |
   *              __    |     |   |
   *    __       /  \   /     |   |
   *   /  \     /    ---      \   /
   *  /    |   |               ---
   *       \   /
   *        --
   *        L1
   *
   *                   trend                                           HVF                              channel           breakout
   *  <----------------------------------------> <----------------------------------------------> <------------------> <----------
   *
   * Prerequisites:
   * - as this is a continuation pattern, I need to establish overall direction. Ie.
   *   - setup, between L1 & H1:
   *     - find overall L1 and H1:
   *       - need to be in the first half of the graph
   *       - whichever comes first dictates the direction: L1 - long, H1 - short
   *     - establish quality of setup:
   *       - H1-L1 > N * H1-RL1, where N = ?? 1 or 2 ??
   *       - gradient/slope? naah
   *       - majority of data points prior to H1 must be below RL1???
   *   - funnel, between H1 & RL3 (or further?):
   *     - H1 > RH2 > RH3 ...
   *     - L1 > LH2 > LH3 ...
   *     - must have min pairs: H1/L1 ... RH3/RL3, though accept more eg. RH4/RL4
   *     - ...TODO: accept borderline conditions where RH2 is barely over H1...???
   *     - establish quality of the funnel
   *       -
   *     - Ls and Hs ned to be eg. 3 candles apart?
   *   - breakout is...
   *     - TODO: how to establish when is breakout? ie. 1..3 candles back? moves with volume?
   *
   *
   *     - L1 comes before H1 for Long, opposite for short
   *
   *
   *
   *   needs to show MA MOM +ve for HVF (shown), -ve fo inverted HVF.
   * - the graph's global trough (T0) needs to happen prior to H1
   * - Establish peaks (H1/RH2/RH3) and troughs: (RL1/RL2/RL3), via:
   *   - presume you only see: setup + funnel + channel + 1 candle of breakout. Hence remove the last candle.
   *   - in channel + funnel, look for H1, take it out of the graph, look for RL1, ... down to RL2
   *   - detect a channel... not sure how???
   *   - breakout needs to:
   *     - surge above RH3
   *     - have volume increased in the channel
   *
   *  Actions upon breakout:
   *  - buy market after price > RH3
   *  - setup takProfit = RH3 + Interim target (RH2 - RL2) * 90%
   *  - setup stoploss = RL3
   *  ...consider switching to full target
   *  - if approaching interim target, switch:
   *    - takeProfit => RH3 + Full target * 90%
   *    - stoploss => RH3 + Interim target / 2
   *  - if gone past interim target, eg. RH3 + Interim target * 120%
   *    - switch to trailing stoploss???
   *
   * For inverted HVF, switch:
   * - MA MOM
   * - H1..3 <--> RL1..3
   *
   *
   *
   * NOTES from course (for up HVF):
   * - 3 impulses = 3 "hills". First should have a steep runnup, gradual come down. Ideally 2nd impulse too:
   *
   *     ---
   *    /   --
   *    |      -
   *    |       --
   *   /          --
   *   |             --
   *
   *  - RH2 - RL1 ~= 61.8% of H1 - RL1
   *  - RH3 - RL2 ~= 50% of RH2 - RL2
   *  - for symmetrical, gradient of H1-RH2-RH3 = - RL1-RL2-RL3
   *  - ascending = top line almost parallel, bottom on a slope
   *    - descending vice versa
   *  - for:
   *    - bulls - trade symmetrical
   *    - bear  - trade descending, can do symmetrical
   *  - RRR of 2.5+
   *  - interim targets = IL1 = axis + RH3 - RL3, IL2 = axis + RH2 - RL2, where axis = (RH3 + LH3) / 2
   *  - full target = axis + H1 - RL1
   *  - avoid ascending (top heavy) bull HVFs, unless in the funnel (as a primer?)
   *  - always!!! avoid descending (bottom heavy) bull HVFs!!!
   *  - set triggers just above RH3 & below LH3
   *  - inside the funnel, the candle bodies are small compared to wicks
   *  - second chance - when missed the breakout, but after *some time* it dipped below RH3. Note, the dip must happen prior to IL2 (second interim)
   *  - vol = volatility of price, *not* volume!!!
   *  - volume can precede a price move, not sure how to account for that...
   *    - funnelling should be at low volume?
   *    - volume reduces from 1st, to 2nd, 3rd impulse (being highest at Hs and Ls)
   *  - consider getting back in on second entries...? less time in the market
   *  - symmetry of the HVF impulse up/down legs:
   *    - up leg of 1st impulse less steep than down leg, on the second leg more or less even, on the third up leg more steep than down. *Important* bit is the gradient ratios change
   *    - first impulse can be more violent, second should be easier, third up steep, down less so
   *
   *
   * QUESTION to the group:
   * - how to fill interim, full targets?
   * - proportions of RH2-LH2, RH3-LH3 in relation to H1-RL1, in terms of min retracements % (61.8%, 50%) and max ???
   *   - as per community: RHx -> RH(x+1) qualification depends on proportionality, amplitude, time frame and squeeze if present
   * - how to tell when RH3/LH3 has been developed?
   * - what are min time windows of HVF?
   * - ** There’s a mention of minimal retracement % for RH2 and RH3, how about maximum? Eg. when the price goes over previous RH3, how do I know if it’s a breakout or a new RH3?
   * - is there *any* action on interim levels 1 and 2? ie. shifting of stoplosses?
   */

  def matches1(xs: Vector[Candle]): Either[String, HVFCandidate] = {
    val lookForH = dir == ShortDir
    val hsLs = hsAndLs(xs, 7, lookForH)
    if (hsLs.size != 7)
      Left(s"Not enough vertices: ${hsLs.size}")
    else {
      val impulse1 = HVFImpulse(resize1st(xs, hsLs(0), hsLs(1), hsLs(2)), hsLs(1), hsLs(2))
      val impulse2 = HVFImpulse(resize1st(xs, hsLs(2), hsLs(3), hsLs(4)), hsLs(3), hsLs(4))
      val impulse3 = HVFImpulse(resize1st(xs, hsLs(4), hsLs(5), hsLs(6)), hsLs(5), hsLs(6))
      if (impulse2.amplitude / impulse1.amplitude > minAmplitudeRatio2_1 || impulse3.amplitude / impulse2.amplitude > minAmplitudeRatio3_2)
        Left(s"Invalid amplitude ratios: ${impulse1.amplitude}, ${impulse2.amplitude}, ${impulse3.amplitude}")
      else if (impulse2.rectRatio / impulse1.rectRatio > maxRectRatio1_0)
        Left(s"Invalid rect ratios: ${impulse1.rectRatio}, ${impulse2.rectRatio}, ${impulse3.rectRatio}")
      else
        Right(HVFCandidate(dir, impulse1, impulse2, impulse3, null))
    }
  }

  def resize1st(xs: Seq[Candle], v0: (Boolean, Candle), v1: (Boolean, Candle), v2: (Boolean, Candle)): (Boolean, Candle) = {
    val (isHigh, new1st) = if (v0._1)
      (true, xs.takeWhile(_.period < v1._2.period).reverse.dropWhile(_.high < v2._2.high).head)
    else
      (false, xs.takeWhile(_.period < v1._2.period).reverse.dropWhile(_.low > v2._2.low).head)
    (isHigh, new1st)
  }
}

case class HVFunnel(startInd: Int, endInd: Int, high: Double, low: Double, axis: Double)
case class HVFImpulse(v1: (Boolean, Candle), v2: (Boolean, Candle), v3: (Boolean, Candle)) {
  lazy val amplitude: Double = if (v1._1)
    // short
    v3._2.high - v2._2.low
  else
    // long
    v2._2.high - v3._2.low

  lazy val width: Double = v3._2.period - v1._2.period

  lazy val rectRatio: Double = amplitude / width
}
case class HVFTrade(dir: Dir.Value, entry: Double, target1: Double, target2: Double, target3: Double, stoploss: Double)
case class HVFCandidate(dir: Dir.Value, impulse1: HVFImpulse, impulse2: HVFImpulse, impulse3: HVFImpulse, funnel: HVFunnel) {
  lazy val boundary = if (dir == LongDir)
    ((impulse1.v2._2.high, impulse1.v2._2.period), (impulse3.v3._2.low, impulse3.v3._2.period))
  else
    ((impulse1.v2._2.low, impulse1.v2._2.period), (impulse3.v3._2.high, impulse3.v3._2.period))

  lazy val trade: HVFTrade = {
    val rh1 = impulse1.v2._2.high
    val lh1 = impulse1.v3._2.low
    val rh2 = impulse2.v2._2.high
    val lh2 = impulse2.v3._2.low
    val rh3 = impulse3.v2._2.high
    val lh3 = impulse3.v3._2.low
    val axis = (rh3 + lh3) / 2
    HVFTrade(dir = dir, entry = rh3, target1 = axis + (rh3 - lh3), target2 = axis + (rh2 - lh2), target3 = axis + (rh1 - lh1), stoploss = lh3)
  }

  override def toString: String = s"HVFCandidate: boundary: ${impulse1.v2._2}, ${impulse3.v3._2}, recRatios: ${impulse1.rectRatio}, ${impulse2.rectRatio}, ${impulse3.rectRatio}, amplitudes: ${impulse1.amplitude}, ${impulse2.amplitude}, ${impulse3.amplitude}"

  // considering funnel entries?
  // lazy val funnelTrade: HVFTrade = ???
  // lazy val funnelCheck: Boolean = ???
}
