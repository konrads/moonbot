package moon

import java.io.File

import moon.OrchestratorActor._
import play.api.libs.json.{JsError, JsSuccess}

import scala.io.Source


class ExchangeSim(
    dataDir: String,
    metrics: Option[Metrics],
    strategy: Strategy,
    tradeQty: Int,
    takeProfitMargin: Double, stoplossMargin: Double,
    openWithMarket: Boolean = false,
    useTrailingStoploss: Boolean = false,
    useSynthetics: Boolean = false) {

  val log = org.slf4j.LoggerFactory.getLogger(classOf[ExchangeSim])

  def run(): Ctx = {
    val eventIter0: Iterator[WsModel] = {
      var prevFilename: String = null
      for {
        filename <- new File(dataDir).list().sortBy { fname =>
          fname.split("\\.") match {
            case Array(s1, s2, s3, s4) => s"$s1.$s2.${"%03d".format(s3.toInt)}.$s4"
            case _ => fname
          }
        }.iterator
        source = Source.fromFile(s"$dataDir/$filename")
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
          log.info(s"Processing data file $dataDir/$filename...")
          prevFilename = filename
        }
        msg
      }
    }
    val eventIter = new OptimizedIter(eventIter0, useSynthetics)

    val behaviorDsl = OrchestratorActor.asDsl(
      strategy,
      tradeQty,
      takeProfitMargin, stoplossMargin,
      openWithMarket,
      useTrailingStoploss)

    val (finalCtx, _finalExchangeCtx) = eventIter.foldLeft((InitCtx(Ledger()):Ctx, ExchangeCtx())) {
      case ((ctx2, exchangeCtx2), event) => paperExchangeSideEffectHandler(behaviorDsl, ctx2, exchangeCtx2, metrics, log, true, WsEvent(event))
    }
    finalCtx
  }
}
