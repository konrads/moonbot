package rcb

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.rogach.scallop._
import play.api.libs.json.{JsError, JsResult, JsSuccess}

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
    val ordertype  = opt[String](default = Some("limit"))
    val price      = opt[BigDecimal]()
    val qty        = opt[BigDecimal]()
    val orderid    = opt[String]()
    val clordrid   = opt[String]()
    val minwssleep = opt[Long](default = Some(10))
    val action     = trailArg[String]()
    verify()
  }
  val cliConf = new CliConf()

  implicit val serviceSystem = akka.actor.ActorSystem()
  implicit val executionContext = serviceSystem.dispatcher

  val restGateway = new RestGateway(url = bitmexUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, syncTimeoutMs = restSyncTimeoutMs)
  val wsGateway = new WsGateWay(wsUrl = bitmexWsUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, minSleepInMs = cliConf.minwssleep.toOption)
  val consumeAll: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value, _) => log.info(s"WS ${value.getClass.getSimpleName}: $value")
    case s:JsError           => log.error(s"WS error!: $s")
  }
  val consumeOrder: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:UpsertOrder, _) => log.info(s"WS UpsertOrder: $value")
    case JsSuccess(value:Trade,  _)      => log.info(s"WS Trade: $value")  // Not an order but useful in order monitoring
    case s:JsError                       => log.error(s"WS error!: $s")
  }
  val consumeOrderBook: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook,  _) => log.info(s"WS OrderBook: $value")
    case s:JsError                      => log.error(s"WS error!: $s")
  }
  val consumeTrade: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:Trade,  _) => log.info(s"WS Trade: $value")
    case s:JsError                  => log.error(s"WS error!: $s")
  }

    // validate sets of options
  (cliConf.action(), cliConf.ordertype.toOption, cliConf.price.toOption, cliConf.qty.toOption, cliConf.orderid.toOption, cliConf.clordrid.toOption) match {
    case ("bid", Some("limit"), Some(price), Some(qty), _, _) =>
      log.info(s"issuing limit bid: price: $price, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeLimitOrderAsync(qty, price, OrderSide.Buy)
      log.info(s"REST bid limit request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST limit bid response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST limit bid exception: $clOrdID, $exc")
      }
    case ("ask", Some("limit"), Some(price), Some(qty), _, _) =>
      log.info(s"issuing limit ask: price: $price, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeLimitOrderAsync(qty, price, OrderSide.Sell)
      log.info(s"REST limit ask request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST limit ask response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST limit ask exception: $clOrdID, $exc")
      }
    case ("bid", Some("market"), _, Some(qty), _, _) =>
      log.info(s"issuing market bid: qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeMarketOrderAsync(qty, OrderSide.Buy)
      log.info(s"REST market bid request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST market bid response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST market bid exception: $clOrdID, $exc")
      }
    case ("ask", Some("market"), _, Some(qty), _, _) =>
      log.info(s"issuing market ask: qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeMarketOrderAsync(qty, OrderSide.Sell)
      log.info(s"REST market ask request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST market ask response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST market ask exception: $clOrdID, $exc")
      }
    case ("bid", Some("stopMarket"), Some(price), Some(qty), _, _) =>
      log.info(s"issuing stopMarket bid: price: $price, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeStopMarketOrderAsync(qty, price, OrderSide.Buy)
      log.info(s"REST bid request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST stopMarket bid response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST stopMarket bid exception: $clOrdID, $exc")
      }
    case ("ask", Some("stopMarket"), Some(price), Some(qty), _, _) =>
      log.info(s"issuing stopMarket ask: price: $price, qty: $qty")
      wsGateway.run(consumeOrder)
      val (clOrdID, resF) = restGateway.placeStopMarketOrderAsync(qty, price, OrderSide.Sell)
      log.info(s"REST stopMarket ask request: $clOrdID")
      resF.onComplete {
        case Success(res) => log.info(s"REST stopMarket ask response: $clOrdID, $res")
        case Failure(exc) => log.error(s"REST stopMarket ask exception: $clOrdID, $exc")
      }
    case ("amend", _, Some(price), _, orderIDOpt, cliOrdOpt) =>
      log.info(s"amending: price: $price, orderid: $orderIDOpt")
      wsGateway.run(consumeOrder)
      val resF = restGateway.amendOrderAsync(orderIDOpt, cliOrdOpt, price)
      log.info(s"REST amend request: $orderIDOpt")
      resF.onComplete {
        case Success(res) => log.info(s"REST amend response: $orderIDOpt, $cliOrdOpt, $res")
        case Failure(exc) => log.error(s"REST amend exception: $orderIDOpt, $cliOrdOpt, $exc")
      }
    case ("cancel", _, _, _, orderIDOpt, cliOrdOpt) =>
      log.info(s"canceling: orderid: $orderIDOpt")
      wsGateway.run(consumeOrder)
      val resF = restGateway.cancelOrderAsync(orderIDOpt, cliOrdOpt)
      log.info(s"REST cancel request: $orderIDOpt")
      resF.onComplete {
        case Success(res) => log.info(s"REST cancel response: $orderIDOpt, $cliOrdOpt, $res")
        case Failure(exc) => log.error(s"REST cancel exception: $orderIDOpt, $cliOrdOpt, $exc")
      }
    case ("monitorAll", _, _, _,  _, _) =>
      log.info(s"monitoring all ws")
      wsGateway.run(consumeAll)
    case ("monitorOrder", _, _, _, _, _) =>
      log.info(s"monitoring orders")
      wsGateway.run(consumeOrder)
    case ("monitorOrderBook", _, _, _, _, _) =>
      log.info(s"monitoring order book")
      wsGateway.run(consumeOrderBook)
    case ("monitorTrade", _, _, _, _, _) =>
      log.info(s"monitoring trade")
      wsGateway.run(consumeTrade)
    case (action, orderTypeOpt, priceOpt, qtyOpt, orderidOpt, cliOrdOpt) =>
      log.error(s"Unknown params: action: $action, orderType: $orderidOpt, price: $priceOpt, amount: $qtyOpt, orderid: $orderidOpt, cliOrdOpt: $cliOrdOpt")
      sys.exit(-1)
  }
}
