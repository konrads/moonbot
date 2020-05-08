package rcb

import org.scalatest._
import org.scalatest.matchers.should._
import org.ta4j.core.Bar

class TalibSpec extends FlatSpec with Matchers with Inside  {
  "TA-LIB" should "work on arrays" in {
    val data = Array(23.98, 23.92, 23.79, 23.67, 23.54, 23.36, 23.65, 23.72, 24.16,
      23.91, 23.81, 23.92, 23.74, 24.68, 24.94, 24.93, 25.10, 25.12, 25.20, 25.06, 24.50, 24.31, 24.57, 24.62,
      24.49, 24.37, 24.41, 24.35, 23.75, 24.09)


  }
}
