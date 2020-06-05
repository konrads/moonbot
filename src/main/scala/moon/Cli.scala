package moon

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.rogach.scallop._
import play.api.libs.json.{JsError, JsResult, JsSuccess}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}


object Cli extends App {
  val log = Logger("Cli")

  val conf = ConfigFactory.load()
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .withFallback(ConfigFactory.parseResources("application.private.conf"))
    .resolve()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")
  val restSyncTimeoutMs = conf.getLong("bot.restSyncTimeoutMs")

  // playground for RestGateway, WsGateway, etc
  class CliConf extends ScallopConf(args) {
    val ordertype       = opt[String](default = Some("limit"))
    val price           = opt[BigDecimal]()
    val takeprofitprice = opt[BigDecimal]()
    val stoplossprice   = opt[BigDecimal]()
    val pegoffsetvalue  = opt[BigDecimal](default = Some(BigDecimal(10)))
    val reduceOnly      = opt[Boolean](default = Some(false))
    val qty             = opt[BigDecimal]()
    val side            = opt[String](default = Some("buy"))
    val orderid         = opt[String]()
    val clordid         = opt[String]()
    val minwssleep      = opt[Long](default = Some(100))
    val action          = trailArg[String]()
    verify()
  }
  val cliConf = new CliConf()

  implicit val serviceSystem = akka.actor.ActorSystem()
  implicit val executionContext = serviceSystem.dispatcher
  val oSide = cliConf.side.toOption.map(_.toLowerCase) match {
    case Some("buy") => OrderSide.Buy
    case _           => OrderSide.Sell
  }

  val restGateway = new RestGateway(url = bitmexUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, syncTimeoutMs = restSyncTimeoutMs)
  val wsGateway = new WsGateWay(wsUrl = bitmexWsUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, minSleepInMs = cliConf.minwssleep.toOption)
  val consumeAll: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value, _) => log.info(s"WS ${value.getClass.getSimpleName}: $value")
    case s:JsError           => log.error(s"WS error!: $s")
  }
  val consumeOrder: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:UpsertOrder, _) => log.info(s"WS UpsertOrder: $value")
    case s:JsError                       => log.error(s"WS error!: $s")
  }
  val consumeOrderTrade: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:UpsertOrder, _) => log.info(s"WS UpsertOrder: $value")
    case JsSuccess(value:Trade, _)       => log.info(s"WS Trade: $value")
    case s:JsError                       => log.error(s"WS error!: $s")
  }
  val consumeTrade: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:Trade, _) => log.info(s"WS Trade: $value")
    case s:JsError                 => log.error(s"WS error!: $s")
  }
  val consumeOrderBook: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook, _) => log.info(s"WS OrderBook: $value")
    case s:JsError                     => log.error(s"WS error!: $s")
  }
  val consumeInstrument: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:Instrument, _) => log.info(s"WS Instrument: $value")
    case s:JsError                      => log.error(s"WS error!: $s")
  }
  val consumeNone: PartialFunction[JsResult[WsModel], Unit] = {
    case s:JsError                      => log.error(s"WS error!: $s")
  }

  // validate sets of options
  (cliConf.action(), cliConf.ordertype.toOption, cliConf.price.toOption, cliConf.takeprofitprice.toOption, cliConf.stoplossprice.toOption, cliConf.pegoffsetvalue.toOption, cliConf.qty.toOption, cliConf.orderid.toOption, cliConf.clordid.toOption, cliConf.reduceOnly.toOption) match {
    case ("order", Some("takeProfitAndStoploss"), _, Some(takeProfitPrice), Some(stoplossPrice), _, Some(qty), _, _, _) =>
      log.info(s"issuing $oSide takeProfitAndStoploss'es: takeProfitPrice: $takeProfitPrice, stoplossPrice: $stoplossPrice, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdIDs, resF) = restGateway.placeBulkOrdersAsync(OrderReqs(Seq(
        OrderReq.asLimitOrder(oSide, qty, takeProfitPrice, true),
        OrderReq.asStopOrder(oSide, qty, stoplossPrice, true))))
      log.info(s"REST takeProfitAndStoploss request: ${clOrdIDs.mkString(", ")}")
      resF.onComplete {
        case Success(res) => log.info(s"REST takeProfitAndStoploss response: ${clOrdIDs.mkString(", ")}, $res")
        case Failure(exc) => log.error(s"REST takeProfitAndStoploss exception: ${clOrdIDs.mkString(", ")}", exc)
      }
    case ("order", Some("takeProfitAndTrailingStoploss"), _, Some(takeProfitPrice), _, Some(pegOffsetValue), Some(qty), _, _, _) =>
      log.info(s"issuing $oSide takeProfitAndTrailingStoploss'es: takeProfitPrice: $takeProfitPrice, pegOffsetValue: $pegOffsetValue, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdIDs, resF) = restGateway.placeBulkOrdersAsync(OrderReqs(Seq(
        OrderReq.asLimitOrder(oSide, qty, takeProfitPrice, true),
        OrderReq.asTrailingStopOrder(oSide, qty, pegOffsetValue, true))))
      log.info(s"REST $oSide takeProfitAndStoploss request: ${clOrdIDs.mkString(", ")}")
      resF.onComplete {
        case Success(res) => log.info(s"REST $oSide takeProfitAndStoploss response: ${clOrdIDs.mkString(", ")}, $res")
        case Failure(exc) => log.error(s"REST $oSide takeProfitAndStoploss exception: ${clOrdIDs.mkString(", ")}", exc)
      }
    case ("order", Some("limit"), Some(price), _, _, _, Some(qty), _, _, Some(reduceOnly)) =>
      log.info(s"issuing $oSide limit: price: $price, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeLimitOrderAsync(qty, price, reduceOnly, oSide)
      log.info(s"REST $oSide limit request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST $oSide limit response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST $oSide limit exception: $clOrdID", exc)
      }
    case ("order", Some("market"), _, _, _, _, Some(qty), _, _, _) =>
      log.info(s"issuing $oSide market: qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeMarketOrderAsync(qty, oSide)
      log.info(s"REST $oSide market request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST $oSide market response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST $oSide market exception: $clOrdID", exc)
      }
    case ("order", Some("stop"), Some(price), _, _, _, Some(qty), _, _, Some(close)) =>
      log.info(s"issuing $oSide stop: price: $price, qty: $qty, close: $close")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeStopOrderAsync(qty, price, close, oSide)
      log.info(s"REST bid request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST $oSide stop response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST $oSide stop exception: $clOrdID", exc)
      }
    case ("order", Some("trailingStop"), _, _, _, Some(pegOffsetValue), Some(qty), _, _, Some(close)) =>
      log.info(s"issuing $oSide trailingStop: pegOffsetValue: $pegOffsetValue, qty: $qty, close: $close")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeTrailingStopOrderAsync(qty, pegOffsetValue, close, oSide)
      log.info(s"REST $oSide trailingStop request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST $oSide trailingStop response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST $oSide trailingStop exception: $clOrdID", exc)
      }
    case ("amend", _, Some(price), _, _, _, _, orderIDOpt, clOrdOpt, _) =>
      log.info(s"amending: price: $price, orderid: $orderIDOpt")
      wsGateway.run(consumeOrder)
      val resF = restGateway.amendOrderAsync(orderIDOpt, clOrdOpt, price)
      log.info(s"REST amend request: $orderIDOpt")
      resF.onComplete {
        case Success(res) => log.info(s"REST amend response: $orderIDOpt, $clOrdOpt, $res")
        case Failure(exc) => log.error(s"REST amend exception: $orderIDOpt, $clOrdOpt", exc)
      }
    case ("cancel", _, _, _, _, _, _, orderIDOpt, cliOrdOpt, _) =>
      log.info(s"canceling: orderid: $orderIDOpt")
      wsGateway.run(consumeOrder)
      val resF = restGateway.cancelOrderAsync(orderIDOpt.map(_.split(",").toSeq).getOrElse(Nil), cliOrdOpt.map(_.split(",").toSeq).getOrElse(Nil))
      log.info(s"REST cancel request: $orderIDOpt")
      resF.onComplete {
        case Success(res) => log.info(s"REST cancel response: $orderIDOpt, $cliOrdOpt, $res")
        case Failure(exc) => log.error(s"REST cancel exception: $orderIDOpt, $cliOrdOpt", exc)
      }
    case ("closePosition", _, _, _, _, _, _, _, _, _) =>
      log.info(s"closing position")
      wsGateway.run(consumeOrder)
      restGateway.closePositionSync() match {
        case Success(res) => log.info(s"REST closePosition response: $res")
        case Failure(exc) => log.error(s"REST closePosition exception", exc)
      }
    case ("monitorLedger", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring ledger")
      def consumeWs(ledger: Ledger=Ledger()): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] {
        case WsEvent(wsData) =>
          val ledger2 = ledger.record(wsData)
          log.info(s"...wsData: $wsData\n                                ledgerOrders:${ledger2.ledgerOrders.map(o => s"\n                                - $o").mkString}")
          if (ledger2.isMinimallyFilled) {
            if (ledger2.sentimentScore >= 0.25)
              log.info(s"bullish - ${ledger2.sentimentScore}! would buy @ ${ledger2.bidPrice}")
            else if (ledger2.sentimentScore <= -0.25)
              log.info(s"bearish - ${ledger2.sentimentScore}! would sell @ ${ledger2.askPrice}")
            else
              log.info(s"neutral - ${ledger2.sentimentScore}...")
          } else {
            log.warn("ledger not yet minimally filled...")
          }
          consumeWs(ledger2)
      }
      val ledgerMonitorActor: ActorRef[ActorEvent] = ActorSystem(consumeWs(), "ledger-monitor-actor")
      val consumeAll: PartialFunction[JsResult[WsModel], Unit] = {
        case JsSuccess(value, _) => ledgerMonitorActor ! WsEvent(value)
        case s:JsError           => log.error(s"WS error!: $s")
      }
      wsGateway.run(consumeAll)
    case ("monitorAll", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring all ws")
      wsGateway.run(consumeAll)
    case ("monitorOrder", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring orders")
      wsGateway.run(consumeOrder)
    case ("monitorTrade", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring trades")
      wsGateway.run(consumeTrade)
    case ("monitorOrderTrade", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring orders & trades")
      wsGateway.run(consumeOrderTrade)
    case ("monitorOrderBook", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring order book")
      wsGateway.run(consumeOrderBook)
    case ("monitorInstrument", _, _, _, _, _, _, _, _, _) =>
      log.info(s"monitoring instrument")
      wsGateway.run(consumeInstrument)
    case ("monitorDebug", _, _, _, _, _, _, _, _, _) =>
      // overwrite debug level
      val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
      rootLogger.setLevel(ch.qos.logback.classic.Level.DEBUG)
      log.info(s"monitoring instrument")
      wsGateway.run(consumeNone)
    case (action, orderTypeOpt, priceOpt, takeProfitPriceOpt, stoplossPriceOpt, pegOffsetValueOpt, qtyOpt, orderidOpt, clOrdOpt, reduceOnlyOpt) =>
      log.error(s"Unknown params: action: $action, orderType: $orderTypeOpt, price: $priceOpt, takeProfitPriceOpt: $takeProfitPriceOpt, stoplossPriceOpt: $stoplossPriceOpt, pegOffsetValueOpt: $pegOffsetValueOpt, amount: $qtyOpt, orderid: $orderidOpt, clOrdOpt: $clOrdOpt, reduceOnlyOpt: $reduceOnlyOpt")
      sys.exit(-1)
  }
}
