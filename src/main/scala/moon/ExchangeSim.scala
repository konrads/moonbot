package moon

import java.io.File

import moon.Behaviour._
import moon.RunType._
import org.joda.time.DateTime
import play.api.libs.json.{JsError, JsSuccess}

import scala.io.Source


class ExchangeSim(
  runType: RunType.Value = BacktestMoon,
  eventDataDir: String=null,
  candleFile: String=null,
  metrics: Option[Metrics],
  strategy: Strategy,
  tradeQty: Int,
  takeProfitMargin: Double, stoplossMargin: Double,
  openWithMarket: Boolean = false,
  useTrailingStoploss: Boolean = false,
  useSynthetics: Boolean = false) {

  assert((eventDataDir != null && candleFile == null) || (eventDataDir == null && candleFile != null))

  val log = org.slf4j.LoggerFactory.getLogger(classOf[ExchangeSim])

  def run(): (LedgerAwareCtx, ExchangeCtx) = {
    val eventIter: Iterator[WsModel] = if (eventDataDir != null) eventsFromDataDir(eventDataDir) else eventsFromCandleFile(candleFile)

    if (runType == BacktestMoon) {
      val behaviorDsl = MoonOrchestrator.asDsl(
        strategy,
        tradeQty,
        takeProfitMargin, stoplossMargin,
        openWithMarket,
        useTrailingStoploss,
        true)
      val (finalCtx, finalExchangeCtx) = eventIter.foldLeft((InitCtx(Ledger()): Ctx, ExchangeCtx())) {
        case ((ctx2, exchangeCtx2), event) => paperExchangeSideEffectHandler(behaviorDsl, ctx2, exchangeCtx2, metrics, log, true, WsEvent(event))
      }
      (finalCtx.asInstanceOf[LedgerAwareCtx], finalExchangeCtx)
    } else if (runType == BacktestYabol) {
      val behaviorDsl = YabolOrchestrator.asDsl(
        strategy,
        tradeQty)
      val (finalCtx, finalExchangeCtx) = eventIter.foldLeft((YabolIdleCtx(Ledger()): YabolCtx, ExchangeCtx())) {
        case ((ctx2, exchangeCtx2), event) => paperExchangeSideEffectHandler(behaviorDsl, ctx2, exchangeCtx2, metrics, log, true, WsEvent(event))
      }
      (finalCtx.asInstanceOf[LedgerAwareCtx], finalExchangeCtx)
    } else
      throw new Exception(s"Invalid dslType: $runType")
  }

  def eventsFromDataDir(eventDataDir: String): Iterator[WsModel] = {
    val eventIter0: Iterator[WsModel] = {
      var prevFilename: String = null
      for {
        filename <- new File(eventDataDir).list().sortBy { fname =>
          fname.split("\\.") match {
            case Array(s1, s2, s3, s4) => s"$s1.$s2.${"%03d".format(s3.toInt)}.$s4"
            case _ => fname
          }
        }.iterator
        source = Source.fromFile(s"$eventDataDir/$filename")
        line <- source.getLines
        msg <- (WsModel.asModel(line) match {
          case JsSuccess(x, _) =>
            Seq(x)
          case JsError(e) =>
            log.error("WS consume error!", e)
            Nil
        }).iterator
      } yield {
        if (filename != prevFilename) {
          log.info(s"Processing data file $eventDataDir/$filename...")
          prevFilename = filename
        }
        msg
      }
    }
    new OptimizedIter(eventIter0, useSynthetics)
  }

  def eventsFromCandleFile(candleFile: String): Iterator[WsModel] =
    new OptimizedIter(Candle.fromFile(candleFile).map(c => Trade(data = Seq(TradeData(side=OrderSide.Buy, size=c.volume, price=c.close, tickDirection=TickDirection.PlusTick, timestamp=new DateTime(c.period * 1000L))))), true)
}
