package moon

package object pattern {
  def hsAndLs(xs: Seq[Double], vertexCnt: Int): Seq[(Int, Boolean, Double)] = {
    def hs(xs: Seq[Double], h: Double, hInd: Int, ind: Int): (Int, Double) =
      if (ind >= xs.size)
        (hInd, h)
      else if (xs(ind) > h)
        hs(xs, xs(ind), ind, ind+1)
      else
        hs(xs, h, hInd, ind+1)

    def ls(xs: Seq[Double], l: Double, lInd: Int, ind: Int): (Int, Double) =
      if (ind >= xs.size)
        (lInd, l)
      else if (xs(ind) < l)
        ls(xs, xs(ind), ind, ind+1)
      else
        ls(xs, l, lInd, ind+1)

    def hsAndLs2(xs: Seq[Double], vertexCnt: Int, lookForH: Boolean, ind: Int, soFar: Seq[(Int, Boolean, Double)]): Seq[(Int, Boolean, Double)] =
      if (vertexCnt == 0 || ind >= xs.size)
        soFar
      else if (lookForH) {
        // look for hs
        val (hInd, h) = hs(xs, xs(ind), ind, ind + 1)
        val soFar2 = soFar :+ (hInd, true, h)
        hsAndLs2(
          xs,
          vertexCnt - 1,
          false,
          hInd + 1,
          soFar2)
      } else {
        // look for ls
        val (lInd, l) = ls(xs, xs(ind), ind, ind + 1)
        val soFar2 = soFar :+ (lInd, false, l)
        hsAndLs2(
          xs,
          vertexCnt - 1,
          true,
          lInd + 1,
          soFar2)
      }
    hsAndLs2(xs, vertexCnt, true, 0, Vector.empty)
  }
}
