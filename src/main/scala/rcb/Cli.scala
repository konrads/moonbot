package rcb

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.rogach.scallop._
import play.api.libs.json.{JsError, JsResult, JsSuccess}
import rcb.BotApp.conf


object Cli extends App {
  private val log = Logger("Cli")

  val conf = ConfigFactory.load()
  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")
  val bitmexRestRetries = conf.getInt("bitmex.restRetries")
  val bitmexRetryBackoffMs  = conf.getLong("bitmex.retryBackoffMs")
  val bitmexRestSyncTimeout = conf.getInt("bitmex.restSyncTimeout")

  // playground for RestGateway, WsGateway, etc
  class CliConf extends ScallopConf(args) {
    val price   = opt[BigDecimal]()
    val qty     = opt[BigDecimal]()
    val markup  = opt[BigDecimal]()
    val orderid = opt[String]()
    val expiry  = opt[Int]()
    val minwssleep = opt[Int](default = Some(10))
    val action  = trailArg[String]()
    verify()
  }
  val cliConf = new CliConf()

  implicit val serviceSystem = akka.actor.ActorSystem()
  implicit val executionContext = serviceSystem.dispatcher

  val restGateway = new RestGateway(url = bitmexUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, maxRetries = bitmexRestRetries, retryBackoffMs = bitmexRetryBackoffMs, syncTimeoutSecs = bitmexRestSyncTimeout)
  val wsGateway = new WsGateWay(wsUrl = bitmexWsUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, minSleepInSecs = cliConf.minwssleep.toOption)
  val consumeAll: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value, _) => log.info(s"WS ${value.getClass.getSimpleName}: $value")
    case s:JsError           => log.error(s"WS error!: $s")
  }
  val consumeOrder: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:UpdatedOrder, _) => log.info(s"WS UpdatedOrder: $value")
    case JsSuccess(value:InsertedOrder, _)  => log.info(s"WS InsertOrder: $value")
    case JsSuccess(value:Trade,  _)       => log.info(s"WS Trade: $value")  // Not an order but useful in order monitoring
    case s:JsError                        => log.error(s"WS error!: $s")
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
  (cliConf.action(), cliConf.price.toOption, cliConf.qty.toOption, cliConf.markup.toOption, cliConf.orderid.toOption, cliConf.expiry.toOption) match {
    case ("bid", Some(price), Some(qty), Some(markup), _, expiryOpt) =>
      log.info(s"issuing bid: price: $price, qty: $qty, markup: $markup")
      wsGateway.run(consumeOrder)
      restGateway.placeOrderSync(qty, price, OrderSide.Buy, expiryOpt) match {
        case scala.util.Success(resp) => log.info(s"REST bid response: $resp")
        case scala.util.Failure(exc)  => log.error(s"REST bid exception: $exc")
      }
    case ("ask", Some(price), Some(qty), Some(markup), _, expiryOpt) =>
      log.info(s"issuing ask: price: $price, qty: $qty, markup: $markup")
      wsGateway.run(consumeOrder)
      restGateway.placeOrderSync(qty, price, OrderSide.Sell, expiryOpt) match {
        case scala.util.Success(resp) => log.info(s"REST ask response: $resp")
        case scala.util.Failure(exc)  => log.error(s"REST ask exception: $exc")
      }
    case ("amend", Some(price), _, _, Some(orderid), expiryOpt) =>
      log.info(s"amending: price: $price, orderid: $orderid")
      wsGateway.run(consumeOrder)
      restGateway.amendOrderSync(orderid, price, expiryOpt) match {
        case scala.util.Success(resp) => log.info(s"REST amend response: $resp")
        case scala.util.Failure(exc)  => log.error(s"REST amend exception: $exc")
      }
    case ("cancel", _, _, _, Some(orderid), expiryOpt) =>
      log.info(s"canceling: orderid: $orderid")
      wsGateway.run(consumeOrder)
      restGateway.cancelOrderSync(orderid, expiryOpt) match {
        case scala.util.Success(resp) => log.info(s"REST cancel response: $resp")
        case scala.util.Failure(exc)  => log.error(s"REST cancel exception: $exc")
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
    case (action, priceOpt, qtyOpt, markupOpt, orderidOpt, expiryOpt) =>
      log.error(s"Unknown params: action: $action, price: $priceOpt, amount: $qtyOpt, markup: $markupOpt, orderid: $orderidOpt, expiryOpt: $expiryOpt"); sys.exit(-1)
  }
}
