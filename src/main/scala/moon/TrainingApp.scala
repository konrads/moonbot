package moon

import moon.OrderStatus._
import moon.OrderType._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import scala.collection.immutable.ListMap
// colours from: https://www.scala-lang.org/api/current/scala/Console$.html
import Console.{GREEN, RED, RESET, BOLD, UNDERLINED}


/**
 * Training tool, runs through test setups (specs) in brute force fashion, recording the winner.
 */
object TrainingApp extends App {
  val log = Logger("TrainingApp")

  // global params
  val takeProfitMargins       = 5 to 15 by 5
  val stoplossMargins         = 5 to 15 by 5
  val openWithMarkets         = Seq(false, true)
  val useTrailingStoplosses   = Seq(false, true)
  // rsi
  val rsiWindows              = 10 to 100 by 5
  val rsiLower                = 50 to 65 by 5
  val rsiUpper                = 80 to 95 by 5
  // indecreasing
  val indecreasingPeriods     = Seq(Seq(5, 4, 3),    Seq(7, 5, 3),    Seq(9, 6, 3),
                                    Seq(7, 5, 4, 3), Seq(9, 7, 5, 3), Seq(12, 9, 6, 3))
  val indecreasingMinAbsSlope = Seq(2.1, 2.3, 2.5)
  val indecreasingMaxAbsSlope = Seq(10.0, 15.0)
  // macd
  val macdResamplePeriods     = 30*1000 to 120*1000 by 10*1000
  // bbands
  val bbandsWindows           = 4 to 20 by 2
  val bbandsDevUps            = Seq(1.8, 2.0, 2.2)
  val bbandsDevDowns          = Seq(1.8, 2.0, 2.2)


  def bruteForceRun(desc: String, backtestDataDir: String="data/training", tradeQty: Int=100, strategies: Iterator[Strategy]): (Double, Strategy) = {
    var winningStrategy: Strategy = null
    var winningPandl: Double = Double.MinValue
    var winningCtx: Ctx = null
    var winningParams: ListMap[String, Any] = ListMap.empty

    for {
      strategy            <- strategies
      takeProfitMargin    <- takeProfitMargins
      stoplossMargin      <- stoplossMargins
      openWithMarket      <- openWithMarkets
      useTrailingStoploss <- useTrailingStoplosses
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
      val finalPrice = (finalCtx.ledger.bidPrice + finalCtx.ledger.askPrice) / 2
      val finalPandlUSD = finalPandl * finalPrice
      val finalTrades = finalCtx.ledger.myOrders.filter(_.ordStatus == Filled)
      val finalLimitTrades = finalTrades.filter(_.ordType == Limit)
      val finalParams = ListMap("takeProfitMargin" -> takeProfitMargin, "stoplossMargin" -> stoplossMargin, "openWithMarket" -> openWithMarket, "useTrailingStoploss" -> useTrailingStoploss)
      if (finalPandl > winningPandl) {
        log.error(s"$GREEN$desc: NEW WINNER pandl: $finalPandl / $finalPandlUSD (${finalLimitTrades.size} / ${finalTrades.size}), strategy conf: ${strategy.config}, ${finalParams.map{case (k,v) => s"$k: $v"}.mkString(", ")}$RESET")
        winningPandl = finalPandl
        winningStrategy = strategy
        winningCtx = finalCtx
        winningParams = finalParams
      } else
        log.warn(s"$desc: running pandl: $finalPandl / $finalPandlUSD (${finalLimitTrades.size} / ${finalTrades.size}), strategy conf: ${strategy.config}, ${finalParams.map{case (k,v) => s"$k: $v"}.mkString(", ")}$RESET")
    }

    val winningPrice = (winningCtx.ledger.bidPrice + winningCtx.ledger.askPrice) / 2
    val winningPandlUSD = winningPandl * winningPrice
    val winningTrades = winningCtx.ledger.myOrders.filter(_.ordStatus == Filled)
    val winningLimitTrades = winningTrades.filter(_.ordType == Limit)
    log.error(s"$GREEN$BOLD$desc: !!!FINAL WINNER!!! pandl: $winningPandl / $winningPandlUSD (${winningLimitTrades.size} / ${winningTrades.size}), strategy conf: ${winningStrategy.config}, ${winningParams.map{case (k,v) => s"$k: $v"}.mkString(", ")}$RESET")

    (winningPandl, winningStrategy)
  }

  def trainIndecreasing: (Double, Strategy) = {
    val strategies = for {
      periods     <- indecreasingPeriods
      minAbsSlope <- indecreasingMinAbsSlope
      maxAbsSlope <- indecreasingMaxAbsSlope
    } yield {
      val conf = ConfigFactory.parseString(
        s"""|periods      = [${periods.mkString(", ")}]
            |maxAbsSlope  = $maxAbsSlope
            |minAbsSlope  = $minAbsSlope
            |""".stripMargin)
      new IndecreasingStrategy(conf)
    }
    bruteForceRun(desc="INDECREASING", strategies=strategies.iterator)
  }

  def trainRsi: (Double, Strategy) = {
    val strategies = for {
      window <- rsiWindows
      lower  <- rsiLower
      upper  <- rsiUpper
      if upper > lower
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

  def trainMacd: (Double, Strategy) = {
    val strategies = for {
      resamplePeriodMs <- macdResamplePeriods
    } yield {
      val conf = ConfigFactory.parseString(
        s"""|resamplePeriodMs = $resamplePeriodMs
            |""".stripMargin)
      new MACDStrategy(conf)
    }
    bruteForceRun(desc="MACD", strategies=strategies.iterator)
  }

  def trainBbands: (Double, Strategy) = {
    val strategies = for {
      window   <- bbandsWindows
      devUps   <- bbandsDevUps
      devDowns <- bbandsDevDowns
    } yield {
      val conf = ConfigFactory.parseString(
        s"""|window  = $window
            |devUp   = $devUps
            |devDown = $devDowns
            |""".stripMargin)
      new BBandsStrategy(conf)
    }
    bruteForceRun(desc="BBANDS", strategies=strategies.iterator)
  }

  if (args.length != 1) {
    log.error(s"${RED}Please provide single indicator to train$RESET")
    System.exit(-1)
  } else
    args(0).toLowerCase match {
      case "rsi"          => trainRsi
      case "indecreasing" => trainIndecreasing
      case "macd"         => trainMacd
      case "bbands"       => trainBbands
      case "all"          =>
        val allReses = Map(
          // "RSI"          -> trainRsi,
          "INDECREASING" -> trainIndecreasing,
          "MACD"         -> trainMacd,
          "BBANDS"       -> trainBbands,
        )
        val (desc, (pandl, strategy)) = allReses.toSeq.maxBy(_._2._1)
        log.warn(s"$GREEN$BOLD$UNDERLINED$desc: BEST OF ALL pandl: $pandl, strategy conf: ${strategy.config}$RESET")
      case _ =>
        log.error(s"${RED}Invalid indicator: ${args(0)}$RESET")
        System.exit(-1)
  }
}

// RESULTS (better ones later):

// RSI:
// 15:09:39 ERROR TrainingApp - RSI: *** !!!NEW WINNER!!! *** pandl: -3.382690226695524E-4 / -3.183449772343158 (17 / 68), strategy conf: Config(SimpleConfigObject({"lower":50,"upper":70,"window":15})), takeProfitMargin: 5.0, stoplossMargin: 5.0, openWithMarket: true, useTrailingStoploss: true
// 16:04:01 ERROR TrainingApp - RSI: *** !!!NEW WINNER!!! *** pandl: -2.9185298342863757E-4 / -2.7477228757347656 (17 / 62), strategy conf: Config(SimpleConfigObject({"lower":55,"upper":85,"window":15})), takeProfitMargin: 5.0, stoplossMargin: 5.0, openWithMarket: true, useTrailingStoploss: true
// 18:22:24 ERROR TrainingApp - RSI: NEW WINNER pandl: -1.730434358038352E-4 / -1.6291606872341573 (6 / 30), strategy conf: Config(SimpleConfigObject({"lower":65,"upper":80,"window":35})), takeProfitMargin: 10.0, stoplossMargin: 10.0, openWithMarket: true, useTrailingStoploss: true
// 19:17:44 ERROR TrainingApp - RSI: !!!FINAL WINNER!!! pandl: -1.6515677267412744E-4 / -1.5549097255337414 (6 / 28), strategy conf: Config(SimpleConfigObject({"lower":50,"upper":80,"window":50})), takeProfitMargin: 10.0, stoplossMargin: 10.0, openWithMarket: true, useTrailingStoploss: true

// INDECREASING:
// 20:36:51 ERROR TrainingApp - INDECREASING: !!!FINAL WINNER!!! pandl: -1.149828745754082E-5 / -0.10825350184088244 (7 / 10), strategy conf: Config(SimpleConfigObject({"maxAbsSlope":10,"minAbsSlope":2.5,"periods":[11,7,3]})), takeProfitMargin: 5.0, stoplossMargin: 5.0, openWithMarket: false, useTrailingStoploss: false
// 22:11:51 ERROR TrainingApp - INDECREASING: !!!FINAL WINNER!!! pandl: 1.986037232197188E-5 / 0.18698044031828476 (5 / 6), strategy conf: Config(SimpleConfigObject({"maxAbsSlope":10,"minAbsSlope":2.5,"periods":[9,6,3]})), takeProfitMargin: 15.0, stoplossMargin: 15.0, openWithMarket: false, useTrailingStoploss: true
// 22:21:59 ERROR TrainingApp - INDECREASING: NEW WINNER pandl: 2.344868712292683E-5 / 0.2207635270905754 (2 / 2), strategy conf: Config(SimpleConfigObject({"maxAbsSlope":10,"minAbsSlope":2.3,"periods":[12,9,6,3]})), takeProfitMargin: 15.0, stoplossMargin: 15.0, openWithMarket: false, useTrailingStoploss: true

// BBANDS:
// 07:22:36 ERROR TrainingApp - BBANDS: NEW WINNER pandl: 1.736868385355042E-5 / 0.15928385744994752 (2 / 2), strategy conf: Config(SimpleConfigObject({"devDown":1.8,"devUp":2.2,"window":6})), takeProfitMargin: 10, stoplossMargin: 15, openWithMarket: false, useTrailingStoploss: true
// 07:29:00 ERROR TrainingApp - BBANDS: NEW WINNER pandl: 2.333576439042008E-5 / 0.21400646128344494 (2 / 2), strategy conf: Config(SimpleConfigObject({"devDown":1.8,"devUp":2.2,"window":6})), takeProfitMargin: 15, stoplossMargin: 15, openWithMarket: false, useTrailingStoploss: true

// MACD: 21:36:42 ERROR TrainingApp - MACD: NEW WINNER pandl: 8.833493745676047E-8 / 8.10097627681586E-4 (15 / 20), strategy conf: Config(SimpleConfigObject({"resamplePeriodMs":30000})), takeProfitMargin: 15, stoplossMargin: 15, openWithMarket: false, useTrailingStoploss: false


// Lets drill more into:
// INDECREASING:
