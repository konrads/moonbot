package moon

import moon.Dir._
import moon.OrderStatus._
import moon.OrderType._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

// colours from: https://www.scala-lang.org/api/current/scala/Console$.html
import Console.{GREEN, RED, RESET, BOLD, UNDERLINED}


/**
 * Training tool, runs through test setups (specs) in brute force fashion, recording the winner.
 */
object TrainingApp extends App {
  val log = Logger("TrainingApp")

  // global params
  val backtestEventCsvDir     = "/Users/konrad/MyDocuments/bitmex/stage/trade_exploded/XBTUSD"
  val backtestDataDir         = null: String  //"data/training"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/XBTUSD/20190101-20200825/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/XBTUSD/20200625-20200825/1H.csv"
  val backtestCandleFile      = null: String //"/Users/konrad/MyDocuments/bitmex/stage/rollup/ETHUSD/20200101-20201104/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/ETHUSD/20200101-20200825/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/ETHUSD/20200701-20200930/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/XBTUSD/20200901-20201015/1H.csv"

  val minTradeCnt             = 100  // Note, roundtrip = 2 trades!
  val takeProfitPercs         = Seq(0.001)
  val tierss                  = Seq(
    Seq((0.99, 20.0), (0.98, 20.0), (0.97, 20.0), (0.96, 20.0), (0.95, 20.0)),
    // Seq((0.99, 60.0), (0.98, 20.0), (0.97, 10.0), (0.96, 6.0), (0.95, 4.0)),
    // Seq((0.99, 100.0)),
  )
  val useSynthetics           = true


  def bruteForceRun(desc: String, strategies: Iterator[Strategy]): (Double, Strategy) = {
    var winningStrategy: Strategy = null
    var winningPandl: Double = Double.MinValue
    var winningCtx: LedgerAwareCtx = null
    var winningParams: Map[String, Double] = Map.empty
    var winningTiers: Seq[(Double, Double)] = Seq.empty

    for {
      strategy       <- strategies
      takeProfitPerc <- takeProfitPercs
      tiers          <- tierss
    } {
      val sim = new ExchangeSim(
        eventCsvDir = backtestEventCsvDir,
        eventDataDir = backtestDataDir,
        candleFile = backtestCandleFile,
        strategy = strategy,
        tierCalc = TierCalcImpl(
          dir=LongDir,
          tiers=tiers
        ),
        dir=LongDir,
        takeProfitPerc = takeProfitPerc,
        metrics = None,
        symbol = "MOONUSD",  // mock symbol...
        namespace = "training",
        useSynthetics = useSynthetics)
      val (ctx, eCtx) = sim.run()
      val pandl = ctx.ledger.ledgerMetrics.runningPandl
      val price = (ctx.ledger.askPrice + ctx.ledger.bidPrice)/2  // was: ctx.ledger.tradeRollups.latestPrice
      val pandlUSD = pandl * price
      val trades = ctx.ledger.myTrades
      val tradesCnt = ctx.ledger.myTrades.size
      val limitTrades = trades.filter(_.ordType == Limit)
      if (pandl > winningPandl && tradesCnt >= minTradeCnt) {
        winningParams = Map("takeProfitPerc" -> takeProfitPerc)
        log.error(f"$GREEN$desc: NEW WINNER pandl: $pandl%.10f / $pandlUSD%.4f (${limitTrades.size} / $tradesCnt), params: ${winningParams.toList.sorted.map{case(k,v) => s"$k: $v"}.mkString(", ")}, tiers: ${tiers.mkString(", ")}$RESET")
        winningPandl = pandl
        winningStrategy = strategy
        winningTiers = tiers
        winningCtx = ctx
      } else
        log.warn(f"$desc: running pandl: $pandl / $pandlUSD (${limitTrades.size} / $tradesCnt)")
    }

    val winningPrice = (winningCtx.ledger.askPrice + winningCtx.ledger.bidPrice)/2  // was: ctx.ledger.tradeRollups.latestPrice
    val winningPandlUSD = winningPandl * winningPrice
    val winningTrades = winningCtx.ledger.myOrders.filter(_.ordStatus == Filled)
    val winningLimitTrades = winningTrades.filter(_.ordType == Limit)
    log.error(f"$GREEN$BOLD$desc: !!!FINAL WINNER!!! pandl: $winningPandl%.10f / $winningPandlUSD%.4f (${winningLimitTrades.size} / ${winningTrades.size}), params: ${winningParams.toList.sorted.map{case(k,v) => s"$k: $v"}.mkString(", ")}, tiers: ${winningTiers.mkString(", ")}$RESET")

    (winningPandl, winningStrategy)
  }

  // run the training
  bruteForceRun(desc="PERMABULL", strategies=Seq(new PermaBullStrategy(ConfigFactory.parseString(""))).iterator)

//  def trainIndecreasing: (Double, Strategy) = {
//    val strategies = for {
//      periods     <- indecreasingPeriods
//      minAbsSlope <- indecreasingMinAbsSlope
//      maxAbsSlope <- indecreasingMaxAbsSlope
//    } yield {
//      val conf = ConfigFactory.parseString(
//        s"""|periods      = [${periods.mkString(", ")}]
//            |minAbsSlope  = $minAbsSlope
//            |maxAbsSlope  = $maxAbsSlope
//            |""".stripMargin)
//      new IndecreasingStrategy(conf)
//    }
//    bruteForceRun(desc="INDECREASING", strategies=strategies.iterator)
//  }
}
