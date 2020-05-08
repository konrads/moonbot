package rcb

import com.typesafe.scalalogging.Logger
import org.rogach.scallop._
import play.api.libs.json.{JsError, JsResult, JsSuccess}
import rcb.BotApp.{bitmexApiKey, bitmexApiSecret, bitmexRestRetries, bitmexUrl, bitmexWsUrl, orchestrator}


object Cli extends App {
  private val log = Logger("Cli")

  // playground for RestGateway, WsGateway, etc
  class Conf extends ScallopConf(args) {
    val price   = opt[BigDecimal]()
    val qty     = opt[BigDecimal]()
    val markup  = opt[BigDecimal]()
    val orderID = opt[String]()
    val action  = trailArg[String]()
    verify()
  }
  val conf = new Conf()

  implicit val serviceSystem = akka.actor.ActorSystem()
  implicit val executionContext = serviceSystem.dispatcher

  val restGateway = new RestGateway(url = bitmexUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret, maxRetries = bitmexRestRetries)
  val wsGateway = new WsGateWay(wsUrl = bitmexWsUrl, apiKey = bitmexApiKey, apiSecret = bitmexApiSecret)
  val consumeAll: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value, _) => log.info(s"${value.getClass.getSimpleName}: $value")
    case s:JsError           => log.error(s"error!: $s")
  }
  val consumeOrder: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:UpdatedOrder, _) => log.info(s"UpdatedOrder: $value")
    case s:JsError                        => log.error(s"error!: $s")
  }
  val consumeOrderBook: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook,  _) => log.info(s"OrderBook: $value")
    case s:JsError                      => log.error(s"error!: $s")
  }

    // validate sets of options
  (conf.action().toLowerCase, conf.price.toOption, conf.qty.toOption, conf.markup.toOption, conf.orderID.toOption) match {
    case ("bid", Some(price), Some(qty), Some(markup), _) =>
      log.info(s"issuing bid: price: $price, qty: $qty, markup: $markup")
      restGateway.placeOrder(qty, price, OrderSide.Buy, markup).onComplete {
        case scala.util.Success(resp) => log.info(s"bid response: $resp")
        case scala.util.Failure(exc)  => log.error(s"bid exception: $exc")
      }
      wsGateway.run(consumeOrder)
    case ("ask", Some(price), Some(qty), Some(markup), _) =>
      log.info(s"issuing ask: price: $price, qty: $qty, markup: $markup")
      restGateway.placeOrder(qty, price, OrderSide.Sell, markup).onComplete {
        case scala.util.Success(resp) => log.info(s"ask response: $resp")
        case scala.util.Failure(exc)  => log.error(s"ask exception: $exc")
      }
      wsGateway.run(consumeOrder)
    case ("amend", Some(price), _, _, Some(orderID)) =>
      log.info(s"amending: price: $price, orderID: $orderID")
      restGateway.amendOrder(orderID, price).onComplete {
        case scala.util.Success(resp) => log.info(s"amend response: $resp")
        case scala.util.Failure(exc)  => log.error(s"amend exception: $exc")
      }
      wsGateway.run(consumeOrder)
    case ("cancel", _, _, _, Some(orderID)) =>
      log.info(s"canceling: orderID: $orderID")
      restGateway.cancelOrder(orderID).onComplete {
        case scala.util.Success(resp) => log.info(s"cancel response: $resp")
        case scala.util.Failure(exc)  => log.error(s"cancel exception: $exc")
      }
      wsGateway.run(consumeOrder)
    case ("monitorAll", _, _, _,  _) =>
      log.info(s"monitoring all ws")
      wsGateway.run(consumeAll)
    case ("monitorOrder", _, _, _, _) =>
      log.info(s"monitoring orders")
      wsGateway.run(consumeOrder)
    case ("monitorOrderBook", _, _, _, _) =>
      log.info(s"monitoring order book")
      wsGateway.run(consumeOrderBook)
    case (action, priceOpt, qtyOpt, markupOpt, orderIDOpt) =>
      log.error(s"Unknown params: action: $action, price: $priceOpt, amount: $qtyOpt, markup: $markupOpt, orderID: $orderIDOpt"); sys.exit(-1)
  }
}
