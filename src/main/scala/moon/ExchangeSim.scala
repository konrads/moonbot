package moon

import java.io.File

import moon.Behaviour._
import org.joda.time.DateTime
import play.api.libs.json.{JsError, JsSuccess}

import scala.io.Source


class ExchangeSim(
  eventDataDir: String=null,
  eventCsvDir: String=null,
  candleFile: String=null,
  metrics: Option[Metrics],
  symbol: String,
  namespace: String,
  strategy: Strategy,
  tierCalc: TierCalc,
  dir: Dir.Value,
  takeProfitPerc: Double,
  useSynthetics: Boolean = false) {

  assert(Array(eventDataDir, eventCsvDir, candleFile).count(_ != null) == 1)

  val log = org.slf4j.LoggerFactory.getLogger(classOf[ExchangeSim])

  def run(): (LedgerAwareCtx, ExchangeCtx) = {
    val eventIter: Iterator[WsModel] = if (eventDataDir != null)
      eventsFromDataDir(eventDataDir)
    else if (eventCsvDir != null)
      eventsFromCsvDir(eventCsvDir)
    else
      eventsFromCandleFile(candleFile)

    val behaviorDsl = Orchestrator.asDsl(
      strategy,
      tierCalc,
      takeProfitPerc,
      dir,
      true)
    val (finalCtx, finalExchangeCtx) = eventIter.foldLeft((InitCtx(Ledger()): Ctx, ExchangeCtx())) {
      case ((ctx2, exchangeCtx2), event) => paperExchangeSideEffectHandler(behaviorDsl, ctx2, exchangeCtx2, metrics, symbol, namespace, log, true, false, WsEvent(event))
    }
    (finalCtx.asInstanceOf[LedgerAwareCtx], finalExchangeCtx)
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

  def eventsFromCsvDir(eventCsvDir: String): Iterator[WsModel] = {
    val eventIter0: Iterator[WsModel] = {
      var prevFilename: String = null
      for {
        filename <- new File(eventCsvDir).list().sorted.iterator
        source = Source.fromFile(s"$eventCsvDir/$filename")
        line <- source.getLines
        // interpret: 2020-05-04D00:00:03.367608000,XBTUSD,Buy,1,8907.5,ZeroPlusTick,9d3d65b9-f073-ec03-eceb-61c49761ff1d,11226,0.00011226,1
        msg <- {
          val Array(timestamp, symbol, side, size, price, tickDirection, trdMatchID, grossValue, homeNotional, foreignNotional) = line.split(",")
          Seq(new Trade(Seq(TradeData(symbol=symbol, side=OrderSide.withName(side), size=size.toDouble, price=price.toDouble, tickDirection=TickDirection.withName(tickDirection), timestamp=parseDateTime(timestamp.take(23).replace("D", "T")+"Z")))))
        }
      } yield {
        if (filename != prevFilename) {
          log.info(s"Processing data file $eventCsvDir/$filename...")
          prevFilename = filename
        }
        msg
      }
    }
    new OptimizedIter(eventIter0, useSynthetics)
  }

  def eventsFromCandleFile(candleFile: String): Iterator[WsModel] =
    new OptimizedIter(
      Candle.fromFile(candleFile).map(c => Trade(data = Seq(TradeData(symbol=c.symbol, side=OrderSide.Buy, size=c.volume, price=c.close, tickDirection=TickDirection.PlusTick, timestamp=new DateTime(c.period * 1000L))))),
      useSynthetics
    )
}
