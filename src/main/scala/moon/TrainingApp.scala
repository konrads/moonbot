package moon

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger


/**
 * Training tool, runs through test setups (specs) in brute force fashion, recording the winner.
 */
object TrainingApp extends App {
  val log = Logger("TrainingApp")
  // global params
  val takeProfitMargins     = (5 until 10 by 5).map(_.toDouble)
  val stoplossMargins       = (5 until 10 by 5).map(_.toDouble)
  val openWithMarkets       = Seq(true, false)
  val useTrailingStoplosses = Seq(true, false)


  def bruteForceRun(desc: String, backtestDataDir: String="data/training", tradeQty: Int=100, strategies: Iterator[Strategy]): (Double, Strategy) = {
    var winningStrategy: Strategy = null
    var winningPandl: Double = Double.MinPositiveValue

    for {
      takeProfitMargin    <- takeProfitMargins
      stoplossMargin      <- stoplossMargins
      openWithMarket      <- openWithMarkets
      useTrailingStoploss <- useTrailingStoplosses
      strategy            <- strategies
    } {
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
        log.warn(s"$desc: -=-=-!!!NEW WINNER!!!-=-=- pandL: $finalPandl, strategy conf: ${strategy.config}, takeProfitMargin: $takeProfitMargin, stoplossMargin: $stoplossMargin, openWithMarket: $openWithMarket, useTrailingStoploss: $useTrailingStoploss")
        winningPandl = finalPandl
        winningStrategy = strategy
      } else
        log.warn(s"$desc: running PandL: $finalPandl, strategy conf: ${strategy.config}, takeProfitMargin: $takeProfitMargin, stoplossMargin: $stoplossMargin, openWithMarket: $openWithMarket, useTrailingStoploss: $useTrailingStoploss")
    }

    (winningPandl, winningStrategy)
  }

  def trainRsi: (Double, Strategy) = {
    val strategies = for {
      window <- 5 until 100 by 5
      upper  <- 50 until 60 by 5
      lower  <- 40 until 50 by 5
      if upper != lower
    } yield {
      val conf = ConfigFactory.parseString(
        s"""|window = $window
            |upper  = $upper
            |lower  = $lower
            |""".stripMargin)
      new RSIStrategy(conf)
    }
    bruteForceRun(desc="RSI", strategies=strategies.iterator)
  }

  def trainIndecreasing: (Double, Strategy) = {
    val strategies = for {
      periods     <- Seq(Seq(5, 4, 3), Seq(7, 5, 3), Seq(9, 6, 3), Seq(11, 7, 3))
      maxAbsSlope <- Seq(1.5, 2.0, 2.5)
      minAbsSlope <- Seq(10.0, 15.0, 20.0)
    } yield {
      val conf = ConfigFactory.parseString(
        s"""|periods = ${periods.mkString(", ")}
            |maxAbsSlope  = $maxAbsSlope
            |minAbsSlope  = $minAbsSlope
            |""".stripMargin)
      new IndecreasingStrategy(conf)
    }
    bruteForceRun(desc="INDECREASING", strategies=strategies.iterator)
  }

  trainRsi
  // trainIndecreasing
}
