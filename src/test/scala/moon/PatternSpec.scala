package moon


import org.scalatest._
import org.scalatest.matchers.should._
import moon.pattern._


class PatternSpec extends FlatSpec with Matchers with Inside {
  "hsAndLs" should "work" in {
    hsAndLs(Vector(1.0, 2.0, 3.0, 4.0, 5.0, 4.1, 3.1, 2.1, 3.2, 4.0, 3.3), 3) shouldBe Vector((4, true, 5.0), (7, false, 2.1), (9, true, 4.0))
    hsAndLs(Vector(1.0, 2.0, 3.0, 4.0, 5.0, 4.1, 3.1, 2.1, 3.2, 3.3, 4.2), 3) shouldBe Vector((4, true, 5.0), (7, false, 2.1), (10, true, 4.2))  // second high last
    hsAndLs(Vector(1.0, 2.0, 3.0, 4.0, 5.0, 4.1, 3.1, 2.1, 3.2, 3.3, 1.2), 3) shouldBe Vector((4, true, 5.0), (10, false, 1.2))  // first low last
  }
}
