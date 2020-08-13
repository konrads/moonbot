package moon

import com.typesafe.scalalogging.Logger
import moon.Dir._
import moon.talib._

object pattern {

  /**
   * HVF implementation (drawn for Long)
   *
   *                                             H1                                                                          /
   *                                            ____                                                                        /                                         ^
   *                                           /    \                                                                       |                                         |
   *                                          /      \                   RH2                                                /                    ^                    |
   *                                         /        \                 ____                                               |                     |                    |
   *                                         |         \               /    \             RH3                              /    trigger RH3      |                    |
   *                                        /          |              /      \           ____                 (RH4)       |     __________       |                    |
   *                                       /           \              |       \         /    \                 __      __/                       |                    |
   *                                      /            |             /        |        /      \      ___      /  \    /                          | Interim            | Full
   *                                      |            \            |         \       /        \    /   \    /    \  /                           | target: RH2-RL2    | target: H1-RL1
   *                                      /             \          /           \      |        \   /     ----      --                            |                    |
   *                                     /               \        /             \     /         ---               (RL4)         ----------       |                    |
   *                                    /                 \       |              \   /          RL3                             stoploss RL3     |                    |
   *                                   /                   \     /                ---                                                            v                    |
   *                                  /                     \   /                 RL2                                                                                 |
   *                                 |                       ---                                                                                                      V
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
   *                   setup                                           funnel                            channel           breakout
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
   */
  private val log = Logger("pattern")


  class HVF(setupPeriod: Int, initMinSlope: Double, minCandlesBetweenHsAndLs: Int = 5) {
    def matches(xs: Vector[Double]): Option[HVFResult] = {
      // determine if we're long or short
      val longHsLs = shrinkingHsLs(LongDir, xs.zipWithIndex)
      val shortHsLs = shrinkingHsLs(ShortDir, xs.zipWithIndex)

      // check the trends prior to first long/short Hs & Ls


      val candidateHLs = shrinkingHsLs(dir, funnel.zipWithIndex, Vector.empty)

      val (funnelIndStart, funnelIndEnd) = (xs.length/2, xs.length/4*3)  // funnel can be either from half to quarter of xs
      for (i <- funnelIndStart to funnelIndEnd) {
        val (setup, funnel) = xs.splitAt(i)
        matches2(setup, funnel) match {
          case Some(res) => return Some(res)
          case None      => None
        }
      }
      None
    }

    private def matches2(setup: Vector[Double], funnel: Vector[Double]): Option[HVFResult] = {
      val setupMaMom = ma_mom(setup, setupPeriod)
      val setupMaMomAbs = math.abs(setupMaMom)

      val dirOpt = if (setupMaMomAbs > initMinSlope && setupMaMom > 0)
        Some(LongDir)
      else if (setupMaMomAbs > initMinSlope && setupMaMom < 0)
        Some(ShortDir)
      else
        None

      dirOpt match {
        case Some(dir) =>
          // look for ps and ts
          val candidateHLs = shrinkingHsLs(dir, funnel.zipWithIndex, Vector.empty)
          val res = if (isAcceptableHVF(dir, candidateHLs))
            Some(calculateHFV(dir, candidateHLs))
          else if (candidateHLs.size >= 6)
            matches2(setup, funnel.drop(1))
          else
            None
          res
        case None =>
          None
      }
    }

    def shrinkingHsLs(dir: Dir.Value, funnel: Vector[(Double, Int)], soFar: Vector[(Double, Int)]=Vector.empty): Vector[(Double, Int)] = ???
    def isAcceptableHVF(dir: Dir.Value, hLs: Vector[(Double, Int)]): Boolean = ???
    def calculateHFV(dir: Dir.Value, hLs: Vector[(Double, Int)]): HVFResult = ???
  }

  case class HVFResult(dir: Dir.Value, initMaMom: Double, pAndTs: Vector[(Double, Int)], trigger: Double, targets: Vector[Target])
  case class Target(takeProfit: Double, stoploss: Double)  // intermediate, ..., primary
}
