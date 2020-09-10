package moon

import scala.math.Ordering

package object pattern {
  def hsAndLs(xs: Seq[Candle], vertexCnt: Int, lookForH: Boolean = true): Seq[(Boolean, Candle)] = {
    def high(xs: Seq[Candle], h: Double, hInd: Int, ind: Int): (Int, Double) =
      if (ind >= xs.size)
        (hInd, h)
      else if (xs(ind).high > h)
        high(xs, xs(ind).high, ind, ind+1)
      else
        high(xs, h, hInd, ind+1)

    def low(xs: Seq[Candle], l: Double, lInd: Int, ind: Int): (Int, Double) =
      if (ind >= xs.size)
        (lInd, l)
      else if (xs(ind).low < l)
        low(xs, xs(ind).low, ind, ind+1)
      else
        low(xs, l, lInd, ind+1)

    def hsAndLs2(xs: Seq[Candle], vertexCnt: Int, lookForH: Boolean, ind: Int, soFar: Seq[(Boolean, Candle)]): Seq[(Boolean, Candle)] =
      if (vertexCnt == 0 || ind >= xs.size)
        soFar
      else if (lookForH) {
        // look for hs
        val (hInd, h) = high(xs, xs(ind).high, ind, ind + 1)
        val soFar2 = soFar :+ (true, xs(hInd))
        hsAndLs2(
          xs,
          vertexCnt - 1,
          false,
          hInd + 1,
          soFar2)
      } else {
        // look for ls
        val (lInd, l) = low(xs, xs(ind).low, ind, ind + 1)
        val soFar2 = soFar :+ (false, xs(lInd))
        hsAndLs2(
          xs,
          vertexCnt - 1,
          true,
          lInd + 1,
          soFar2)
      }
    hsAndLs2(xs, vertexCnt, lookForH, 0, Vector.empty)
  }

  def max[T, I: Ordering](ts: Seq[(T, Int)])(toOrdering: T => I): (T, Int) = {
    val (_, ind) = ts.map{ case (x, ind) => (toOrdering(x), ind)}.max
    ts(ind)
  }

  def min[T, I: Ordering](ts: Seq[(T, Int)])(toOrdering: T => I): (T, Int) = {
    val (_, ind) = ts.map{ case (x, ind) => (toOrdering(x), ind)}.min
    ts(ind)
  }
}
