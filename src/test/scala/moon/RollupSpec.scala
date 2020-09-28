package moon

import org.scalatest._
import org.scalatest.matchers.should._
import moon.DataFreq._

class RollupSpec extends FlatSpec with Matchers with Inside {
  it should "work for sequential timeseries" in {
    // populate bucket #1
    val r1 = Seq((1, 20.0, 20.0), (3, 10.0, 10.0), (5, 5.0, 30.0), (6, 6.0, 40.0)).foldLeft(RollupBuckets(10, 10)) {
      case (soFar, (ts, price, volume)) => soFar.add(ts, price, volume)
    }

    r1.forecast.open shouldBe Vector(20.0)
    r1.forecast.close shouldBe Vector(6.0)
    r1.forecast.high shouldBe Vector(20.0)
    r1.forecast.low shouldBe Vector(5.0)
    r1.forecast.volume shouldBe Vector(100.0)

    // populate bucket #2
    val r2 = Seq((11, 50.0, 100.0), (13, 100.0, 40.0), (15, 80.0, 60.0)).foldLeft(r1) {
      case (soFar, (ts, price, volume)) => soFar.add(ts, price, volume)
    }

    r2.forecast.open shouldBe Vector(20.0, 50.0)
    r2.forecast.close shouldBe Vector(6.0, 80.0)
    r2.forecast.high shouldBe Vector(20.0, 100.0)
    r2.forecast.low shouldBe Vector(5.0, 50.0)
    r2.forecast.volume shouldBe Vector(100.0, 100.0)  // with useLatest = 200

    // populate bucket #3, with ffill
    val r3 = Seq((31, 5.0, 10.0), (33, 3.0, 20.0)).foldLeft(r2) {
      case (soFar, (ts, price, volume)) => soFar.add(ts, price, volume)
    }

    r3.forecast.open shouldBe Vector(20.0, 50.0, 50.0, 5.0)
    r3.forecast.close shouldBe Vector(6.0, 80.0, 80.0, 3.0)
    r3.forecast.high shouldBe Vector(20.0, 100.0, 100.0, 5.0)
    r3.forecast.low shouldBe Vector(5.0, 50.0, 50.0, 3.0)
    r3.forecast.volume shouldBe Vector(100.0, 200.0, 0.0, 0.0)  // with useLatest = 30

    // populate bucket #4, no ffill
    val r4 = Seq((43, 1.0, 10.0)).foldLeft(r3) {
      case (soFar, (ts, price, volume)) => soFar.add(ts, price, volume)
    }

    r4.forecast.open shouldBe Vector(20.0, 50.0, 50.0, 5.0, 1.0)
    r4.forecast.close shouldBe Vector(6.0, 80.0, 80.0, 3.0, 1.0)
    r4.forecast.high shouldBe Vector(20.0, 100.0, 100.0, 5.0, 1.0)
    r4.forecast.low shouldBe Vector(5.0, 50.0, 50.0, 3.0, 1.0)
    r4.forecast.volume shouldBe Vector(100.0, 200.0, 0.0, 30.0, 30.0)  // with useLatest = 10

    // populate bucket #5, 2 ffills
    val r5 = Seq((73, 2.0, 20.0)).foldLeft(r4) {
      case (soFar, (ts, price, volume)) => soFar.add(ts, price, volume)
    }

    r5.forecast.open shouldBe Vector(20.0, 50.0, 50.0, 5.0, 1.0, 1.0, 1.0, 2.0)
    r5.forecast.close shouldBe Vector(6.0, 80.0, 80.0, 3.0, 1.0, 1.0, 1.0, 2.0)
    r5.forecast.high shouldBe Vector(20.0, 100.0, 100.0, 5.0, 1.0, 1.0, 1.0, 2.0)
    r5.forecast.low shouldBe Vector(5.0, 50.0, 50.0, 3.0, 1.0, 1.0, 1.0, 2.0)
    r5.forecast.volume shouldBe Vector(100.0, 200.0, 0.0, 30.0, 10.0, 0.0, 0.0, 0.0)  // with useLatest = 20

    // populate bucket #5, complex again
    val r6 = Seq((81, 7.0, 70.0), (82, 9.0, 90.0), (83, 4.0, 40.0), (85, 5.0, 50.0)).foldLeft(r5) {
      case (soFar, (ts, price, volume)) => soFar.add(ts, price, volume)
    }

    r6.forecast.open shouldBe Vector(20.0, 50.0, 50.0, 5.0, 1.0, 1.0, 1.0, 2.0, 7.0)
    r6.forecast.close shouldBe Vector(6.0, 80.0, 80.0, 3.0, 1.0, 1.0, 1.0, 2.0, 5.0)
    r6.forecast.high shouldBe Vector(20.0, 100.0, 100.0, 5.0, 1.0, 1.0, 1.0, 2.0, 9.0)
    r6.forecast.low shouldBe Vector(5.0, 50.0, 50.0, 3.0, 1.0, 1.0, 1.0, 2.0, 4.0)
    r6.forecast.volume shouldBe Vector(100.0, 200.0, 0.0, 30.0, 10.0, 0.0, 0.0, 20.0, 20.0) // with useLatest = 250
  }

  it should "respect m and h" in {
    val r = Rollups(3)
    r.withForecast(`1m`).forecast.high.isEmpty shouldBe true
    r.withForecast(`1h`).forecast.high.isEmpty shouldBe true

    val r2 = Seq((10, 1.0, 10.0), (71, 2.0, 20.0), (131, 3.0, 30.0), (200, 4.0, 40.0)).foldLeft(r) {
      case (soFar, (ts, price, volume)) => soFar.add(ts*60_000, price, volume)
    }
    r2.withForecast(`1m`).forecast.high.toSet shouldBe Set(1.0, 2.0, 3.0, 4.0)
    r2.withForecast(`1m`).forecast.high.size shouldBe 191
    r2.withForecast(`1m`).forecast.low.size shouldBe 191
    r2.withForecast(`1m`).forecast.open.size shouldBe 191
    r2.withForecast(`1m`).forecast.close.size shouldBe 191
    r2.withForecast(`1m`).forecast.vwap.size shouldBe 191
    r2.withForecast(`1m`).forecast.volume.size shouldBe 191
    r2.withForecast(`1m`).forecast.period.size shouldBe 191

    r2.withForecast(`1h`).forecast.high.toSet shouldBe Set(2.0, 3.0, 4.0)
    r2.withForecast(`1h`).forecast.high.size shouldBe 3
    r2.withForecast(`1h`).forecast.low.size shouldBe 3
    r2.withForecast(`1h`).forecast.open.size shouldBe 3
    r2.withForecast(`1h`).forecast.close.size shouldBe 3
    r2.withForecast(`1h`).forecast.vwap.size shouldBe 3
    r2.withForecast(`1h`).forecast.volume.size shouldBe 3
    r2.withForecast(`1h`).forecast.period.size shouldBe 3

    val r3 = r2.add(260*60_000, 5.0, 50.0)
    r3.withForecast(`1m`).forecast.high.toSet shouldBe Set(1.0, 2.0, 3.0, 4.0, 5.0)
    r3.withForecast(`1m`).forecast.high.size shouldBe 240
    r3.withForecast(`1m`).forecast.low.size shouldBe 240
    r3.withForecast(`1m`).forecast.open.size shouldBe 240
    r3.withForecast(`1m`).forecast.close.size shouldBe 240
    r3.withForecast(`1m`).forecast.vwap.size shouldBe 240
    r3.withForecast(`1m`).forecast.volume.size shouldBe 240
    r3.withForecast(`1m`).forecast.period.size shouldBe 240

    r3.withForecast(`1h`).forecast.high.toSet shouldBe Set(3.0, 4.0, 5.0)
    r3.withForecast(`1h`).forecast.high.size shouldBe 3
    r3.withForecast(`1h`).forecast.low.size shouldBe 3
    r3.withForecast(`1h`).forecast.open.size shouldBe 3
    r3.withForecast(`1h`).forecast.close.size shouldBe 3
    r3.withForecast(`1h`).forecast.vwap.size shouldBe 3
    r3.withForecast(`1h`).forecast.volume.size shouldBe 3
    r3.withForecast(`1h`).forecast.period.size shouldBe 3
  }

  it should "perform quickly" in {
    val d14 = 14*24*60*1000
    val freq = 10  // new trade every 10 ms
    val tradeCnt = d14 / freq
    val (upTo14d, r) = timeit {
      (0 to tradeCnt.toInt).foldLeft((Rollups(d14 / 60_000))) { case (soFar, i) => soFar.add(i, i % 1133, i % 2277) }
    }
    println(f"Time up to 14d: $upTo14d%.4f, ie. ${upTo14d / tradeCnt}%.8f per add()")

    val (next14d, r2) = timeit {
      (d14 to 2*d14 by freq).foldLeft(r) { case (soFar, i) => soFar.add(i, i % 1133, i % 2277) }
    }
    println(f"Time next 14d: $next14d%.4f, ie. ${next14d / tradeCnt}%.8f per add()")

    val (nextnext14d, r3) = timeit {
      (2*d14 to 3*d14 by freq).foldLeft(r2) { case (soFar, i) => soFar.add(i, i % 1133, i % 2277) }
    }
    println(f"Time next next 14d: $nextnext14d%.4f, ie. ${nextnext14d / tradeCnt}%.8f per add()")
  }
}
