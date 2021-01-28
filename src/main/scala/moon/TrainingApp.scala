package moon

import moon.Dir._
import moon.OrderStatus._
import moon.OrderType._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

// colours from: https://www.scala-lang.org/api/current/scala/Console$.html
import Console.{GREEN, RED, RESET, BOLD, UNDERLINED}


/**
 * Training tool, runs through test setups (specs) in brute force fashion, recording the winner.
 */
object TrainingApp extends App {
  val log = Logger("TrainingApp")

  // global params
  val backtestEventCsvDir     = "/Users/konrad/MyDocuments/bitmex/stage/trade_exploded-20200701-20201001"
  val backtestDataDir         = null: String  //"data/training"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/XBTUSD/20190101-20200825/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/XBTUSD/20200625-20200825/1H.csv"
  val backtestCandleFile      = null: String //"/Users/konrad/MyDocuments/bitmex/stage/rollup/ETHUSD/20200101-20201104/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/ETHUSD/20200101-20200825/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/ETHUSD/20200701-20200930/1H.csv"
  // val backtestCandleFile      = "/Users/konrad/MyDocuments/bitmex/stage/rollup/XBTUSD/20200901-20201015/1H.csv"

  val minTradeCnt             = 100  // Note, roundtrip = 2 trades!
  val takeProfitPercs         = Seq(0.0005)
  val tierss                  = Seq(Seq(
    // (99 to 60 by -1).map(x => (x/100.0, 500.0)),
    (0.995, 50.0),
    (0.99,  50.0),
    (0.985, 50.0),
    (0.98,  50.0),
    (0.975, 50.0),
    (0.97,  50.0),
    (0.965, 50.0),
    (0.96,  50.0),
    (0.955, 50.0),
    (0.95,  50.0),
    (0.94,  50.0),
    (0.93,  50.0),
    (0.92,  50.0),
    (0.91,  50.0),
    (0.9,   50.0),
    (0.88,  50.0),
    (0.86,  50.0),
    (0.84,  50.0),
    (0.82,  50.0),
    (0.8,   50.0),
    (0.77,  50.0),
    (0.74,  50.0),
    (0.7,   50.0),
    (0.65,  50.0),
    (0.6,   50.0)
  ))
  val useSynthetics           = true

  // overwrite log level
  val logLevel = "INFO"
  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.valueOf(logLevel.toUpperCase))


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
          openOrdersWithTiers=tiers
        ),
        dir=LongDir,
        takeProfitPerc = takeProfitPerc,
        metrics = None, // Some(Metrics(host="localhost")),
        symbol = "MOONUSD",  // mock symbol...
        namespace = "training",
        useSynthetics = useSynthetics)
      val (ctx, eCtx) = sim.run()
      val pandl = ctx.ledger.ledgerMetrics.runningPandl
      val price = (ctx.ledger.askPrice + ctx.ledger.bidPrice)/2  // was: ctx.ledger.tradeRollups.latestPrice
      val pandlUSD = pandl * price
      val buysCnt = ctx.ledger.myBuyTrades.size
      val sellsCnt = ctx.ledger.mySellTrades.size
      if (pandl > winningPandl && sellsCnt >= buysCnt+minTradeCnt) {
        winningParams = Map("takeProfitPerc" -> takeProfitPerc)
        log.error(f"$GREEN$desc: NEW WINNER pandl: $pandl%.10f / $pandlUSD%.4f (buys:$buysCnt/sells:$sellsCnt), params: ${winningParams.toList.sorted.map{case(k,v) => s"$k: $v"}.mkString(", ")}, tiers: ${tiers.mkString(", ")}$RESET")
        winningPandl = pandl
        winningStrategy = strategy
        winningTiers = tiers
        winningCtx = ctx
      } else
        log.warn(f"$desc: running pandl: $pandl / $pandlUSD (buys:$buysCnt/sells:$sellsCnt)")
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
