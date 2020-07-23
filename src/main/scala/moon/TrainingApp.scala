package moon

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger


/**
 * Training tool, runs through test setups (specs) in brute force fashion, recording the winner.
 */
object TrainingApp extends App {
  val log = Logger("TrainingApp")

  def bruteForceRun(desc: String, backtestDataDir: String="data/training", tradeQty: Int=100, testSpecs: Iterator[(Double, Double, Boolean, Boolean, Strategy)]): (Double, Strategy) = {
    var winningStrategy: Strategy = null
    var winningPandl: Double = Double.MinPositiveValue

    while (testSpecs.hasNext) {
      val (takeProfitMargin, stoplossMargin, openWithMarket, useTrailingStoploss, strategy) = testSpecs.next
      val sim = new ExchangeSim(
        dataDir = backtestDataDir,
        strategy = strategy,
        tradeQty = tradeQty,
        takeProfitMargin = takeProfitMargin, stoplossMargin = stoplossMargin,
        metrics = None,
        openWithMarket = openWithMarket,
        useTrailingStoploss = useTrailingStoploss,
        useSynthetics = false)
      val (finalCtx, finalExchangeCtx) = sim.run()
      val finalPandl = finalCtx.ledger.ledgerMetrics.runningPandl
      if (finalPandl > winningPandl) {
        log.warn(s"$desc:: new winner! pandL: $finalPandl, conf: ${strategy.config}")
        winningPandl = finalPandl
        winningStrategy = strategy
      } else
        log.warn(s"$desc: running PandL: $finalPandl, conf: ${strategy.config}")
    }

    (winningPandl, winningStrategy)
  }

  def trainRsi(): (Double, Strategy) = {
    val testSpecs = for {
      takeProfitMargin    <- (6 until 10 by 2).map(_.toDouble)
      stoplossMargin      <- (6 until 10 by 2).map(_.toDouble)
      openWithMarket      <- Seq(true, false)
      useTrailingStoploss <- Seq(true, false)
      window              <- 5 until 100 by 5
      upper               <- 50 until 70 by 5
      lower               <- 30 until 50 by 5
      if upper != lower
    } yield {
      val conf = ConfigFactory.parseString(
        s"""|window = $window
            |upper  = $upper
            |lower  = $lower
            |""".stripMargin)
      (takeProfitMargin, stoplossMargin, openWithMarket, useTrailingStoploss, new RSIStrategy(conf))
    }
    bruteForceRun(desc="RSI", testSpecs=testSpecs.iterator)
  }

  trainRsi()
}
