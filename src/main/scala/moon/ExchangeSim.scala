package moon

import java.io.File

import akka.actor.typed.javadsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed._
import akka.dispatch.{PriorityGenerator, UnboundedStablePriorityMailbox}
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import org.joda.time.DateTime
import play.api.libs.json.{JsError, JsSuccess}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Success, Try}

/**
 * Exchange simulator, interacting via:
 * - IRestGateway for issuing orders/cancels/amends
 * - sending messages to actorRef with exchange feedback
 *
 * Initial messages are read from file and fed through via actorRef !
 */
class ExchangeSim(dataDir: String, clock: DryRunClock, scheduler: akka2.DryRunTimerScheduler[ActorEvent], sleepEvery: Int=1, sleepForMs: Int=1) extends IRestGateway {
  val log = Logger(classOf[ExchangeSim])
  var simRef: ActorSystem[SimEvent] = null
  implicit val timeout: Timeout = 5.seconds

  def run(orchestrator: Behavior[ActorEvent]): Unit = {
    simRef = ActorSystem(simulator(orchestrator), "simulator-actor")

    // feed WS events from the files
    var prevFilename = ""
    var cache: OrderBookSummary = null
    var i: Long = 0
    for {
      filename <- new File(dataDir).list().sortBy { fname =>
        fname.split("\\.") match {
          case Array(s1, s2, s3, s4) => s"$s1.$s2.${"%03d".format(s3.toInt)}.$s4"
          case _ => fname
        }
      }
      source = Source.fromFile(s"$dataDir/$filename")
      line <- source.getLines
    } {
      if (prevFilename != filename) {
        log.info(s"Processing file $dataDir/$filename")
        prevFilename = filename
      }
      WsModel.asModel(line) match {
        case JsSuccess(msg, _) =>
          val msg2 = msg match {
            case x:OrderBook =>
              val summary = x.summary
              if (! summary.isEquivalent(cache)) {
                cache = summary
                Some(summary)
              } else
                None
            case x@(_:Info|_:Instrument|_:Funding|_:UpsertOrder|_:Trade) => Some(x)
            case _ => None
          }
          val now = msg2.flatMap(getTimestamp).map(_.getMillis)
          msg2.foreach { m => simRef ! WS(m) }
          now.foreach(clock.setTime)
          now.foreach(scheduler.setTime)
          if (msg2.isDefined) {
            i += 1; if (i % sleepEvery == 0) Thread.sleep(sleepForMs)  // give grafana bit of breathing space
          }
        case e: JsError =>
          log.error("WS consume error!", e)
      }
    }
  }

  sealed trait SimEvent
  case class WS(model: WsModel) extends SimEvent
  case class BulkOrders(orderReqs: OrderReqs, replyTo: ActorRef[Orders]) extends SimEvent
  case class SingleOrder(orderReq: OrderReq, replyTo: ActorRef[Order]) extends SimEvent
  case class AmendOrder(orderID: Option[String], origClOrdID: Option[String], price: Double, replyTo: ActorRef[Order]) extends SimEvent
  case class CancelOrder(orderID: Seq[String], clOrdID: Seq[String], replyTo: ActorRef[Orders]) extends SimEvent

  // https://discuss.lightbend.com/t/using-ask-pattern-from-outside-of-typed-system-type-mismatch/2445
  def simulator(orchestrator: Behavior[ActorEvent]): Behavior[SimEvent] = Behaviors.setup[SimEvent] { actorCtx =>
    // achieve priorities via https://doc.akka.io/docs/akka/current/typed/mailboxes.html
    val props = MailboxSelector.fromConfig("akka.dry-run-mailbox")
    val orchestratorRef = actorCtx.spawn(orchestrator, "orchestrator-actor", props)
    case class LoopCtx(byClOrdID: Map[String, Order]=Map.empty, orders: Seq[Order]=Vector.empty, bid: Double=0, ask: Double=0, timestamp: DateTime=null)
    def loop(ctx: LoopCtx): Behavior[SimEvent] = Behaviors.receiveMessage[SimEvent] {
//      // DEFAULTING TO **NOT** taking price from trade, as OrchestratorActor doesn't either!!! would need to issue orderbook summary
//      case WS(t:Trade) =>
//        val firstT = t.data.head
//        orchestratorRef ! WsEvent(t)
//        val ctx2 = ctx.copy(timestamp=firstT.timestamp)
//        loop(ctx2)
      case WS(t:Trade) =>
        // use trades to extract bin and ask. following rules:
        // - on Buy (or PlusTick/ZeroPlusTick?), last trade price = ask
        // - on Sell (or MinusTick/ZeroMinusTick?), last trade price = bid
        // - ask = bid + 0.5
        // do this in case we switch off voluminous OrderBook events...
        val firstT = t.data.head
        val (ask, bid) = if (firstT.side == Buy)
          (firstT.price, firstT.price - 0.5)
        else
          (firstT.price + 0.5, firstT.price)

        val (shouldFill, shouldntFill) = ctx.orders.partition(canFill(bid, ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val orders2 = filled ++ shouldntFill
        // simulate OrderBookSummary
        if (ctx.bid != bid || ctx.ask != ask) orchestratorRef ! WsEvent(OrderBookSummary("btcusd", firstT.timestamp, ask=ask, bid=bid))
        orchestratorRef ! WsEvent(t)
        // has price update triggered any orders?
        if (filled.nonEmpty) {
          log.info(s"Filled (0) bid: ${bid}, ask: ${ask}, ${filled.mkString(", ")}")
          orchestratorRef ! WsEvent(toWsModel(filled))
        }
        val ctx2 = ctx.copy(orders=orders2, bid=bid, ask=ask, timestamp=firstT.timestamp)
        loop(ctx2)
      case WS(obs:OrderBookSummary) =>
        val timestamp = getTimestamp(obs)
        val (shouldFill, shouldntFill) = ctx.orders.partition(canFill(obs.bid, obs.ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val orders2 = filled ++ shouldntFill
        // log.info(s"Updating with $obs")
        orchestratorRef ! WsEvent(obs)
        // has price update triggered any orders?
        if (filled.nonEmpty) {
          log.info(s"Filled (2) bid: ${obs.bid}, ask: ${obs.ask}, ${filled.mkString(", ")}")
          orchestratorRef ! WsEvent(toWsModel(filled))
        }
        val ctx2 = ctx.copy(orders=orders2, bid=obs.bid, ask=obs.ask, timestamp=timestamp.get)
        loop(ctx2)
      case WS(ob:OrderBook) =>
        // FIXME: Following is repeated in OrderBookSummary! Keeping it here in case summary goes away...
        val obs = ob.summary
        val timestamp = getTimestamp(obs)
        val (shouldFill, shouldntFill) = ctx.orders.partition(canFill(obs.bid, obs.ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val orders2 = filled ++ shouldntFill
        // log.info(s"Updating with $obs")
        orchestratorRef ! WsEvent(obs)
        // has price update triggered any orders?
        if (filled.nonEmpty) {
          log.info(s"Filled (1) bid: ${obs.bid}, ask: ${obs.ask}, ${filled.mkString(", ")}")
          orchestratorRef ! WsEvent(toWsModel(filled))
        }
        val ctx2 = ctx.copy(orders=orders2, bid=obs.bid, ask=obs.ask, timestamp=timestamp.get)
        loop(ctx2)
      case WS(other) =>
        // pass through, but update timestamp
        orchestratorRef ! WsEvent(other)
        getTimestamp(other) match {
          case Some(ts) =>
            loop(ctx.copy(timestamp = ts))
          case None =>
            Behaviors.same
        }
      case x:BulkOrders =>
        val orders = x.orderReqs.orders.map(o => createOrder(o, ctx.timestamp, ctx.bid, ctx.ask))
        val (shouldFill, shouldntFill) = orders.partition(canFill(ctx.bid, ctx.ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val orders2 = filled ++ shouldntFill
        log.info(s"Issued orders bid: ${ctx.bid}, ask: ${ctx.ask}: ${orders2.mkString(", ")}")
        x.replyTo ! Orders(orders2)
        loop(ctx.copy(orders = ctx.orders ++ orders2))
      case x:SingleOrder =>
        val order = createOrder(x.orderReq, ctx.timestamp, ctx.bid, ctx.ask)
        val order2 = if (canFill(ctx.bid, ctx.ask)(order)) order.copy(ordStatus=Some(Filled)) else order
        // reply with all orders, but only keep state of non filled ones
        log.info(s"Issued 1 order bid: ${ctx.bid}, ask: ${ctx.ask}: $order2")
        x.replyTo ! order2
        loop(ctx.copy(orders = ctx.orders :+ order2))
      case x:AmendOrder =>
        val (matched, unmatched) = ctx.orders.partition(v => (v.clOrdID.exists(x.origClOrdID.contains) || x.orderID.contains(v.orderID)) && v.ordStatus.exists(os => os != Filled || os != Canceled))
        val withNewPrice = matched.map(v => v.copy(price = Some(x.price)))
        val (shouldFill, shouldntFill) = withNewPrice.partition(canFill(ctx.bid, ctx.ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val withNewState = filled ++ shouldntFill
        log.info(s"Amended: ${x.origClOrdID} / ${x.orderID} @ ${x.price}, ${withNewState.mkString(", ")}")
        // if (filled.nonEmpty) orchestratorRef ! WsEvent(toWsModel(filled))
        assert(withNewState.size == 1)  // presume only 1!
        x.replyTo ! withNewState.head
        loop(ctx.copy(orders=unmatched ++ withNewState))
      case x:CancelOrder =>
        val (matched, unmatched) = ctx.orders.partition(v => (v.clOrdID.exists(x.clOrdID.contains) || x.orderID.contains(v.orderID)) && v.ordStatus.exists(os => os != Filled || os != Canceled))
        val withNewState = matched.map(o => o.copy(ordStatus = Some(Canceled)))
        log.info(s"Cancelled: ${x.clOrdID.mkString(";")} / ${x.orderID.mkString(";")},  ${withNewState.mkString(", ")}")
        // orchestratorRef ! WsEvent(toWsModel(withNewState))
        x.replyTo ! Orders(withNewState)
        loop(ctx.copy(orders=unmatched ++ withNewState))
    }
    loop(LoopCtx())
  }

  private def toWsModel(os: Seq[Order]): UpsertOrder =
    UpsertOrder(Some("update"), os.map(o => OrderData(orderID=o.orderID, clOrdID=o.clOrdID, orderQty=Some(o.orderQty), price=o.price, side=Some(o.side), ordStatus=o.ordStatus, ordType=Some(o.ordType), timestamp=o.timestamp)))

  private def createOrder(orderReq: OrderReq, timestamp: DateTime, bid: Double, ask: Double): Order = {
    val orderID = java.util.UUID.randomUUID().toString
    val price = if (orderReq.side == Buy && orderReq.ordType == Market)
      bid
    else if (orderReq.side == Sell && orderReq.ordType == Market)
      ask
    else if (orderReq.side == Buy && orderReq.pegOffsetValue.isDefined)  // trailing stoploss - FIXME: estimating with a generic stoploss
      bid + orderReq.pegOffsetValue.get.abs
    else if (orderReq.side == Sell && orderReq.pegOffsetValue.isDefined) // trailing stoploss - FIXME: estimating with a generic stoploss
      ask - orderReq.pegOffsetValue.get.abs
    else
      orderReq.price.get
    Order(orderID=orderID, clOrdID=orderReq.clOrdID, orderQty=orderReq.orderQty, price=Some(price), ordStatus=Some(New), side=orderReq.side, ordType=orderReq.ordType, timestamp=timestamp, symbol="btcusd")
  }

  private def canFill(bid: Double, ask: Double)(o: Order): Boolean = (o.ordStatus, o.side, o.ordType, o.price.get) match {
    case (Some(Filled), _, _, _)                 => false
    case (Some(Canceled), _, _, _)               => false
    case (_, _,  Market, _)                      => true
    case (_, Buy,  Limit, price) if price >= ask => true
    case (_, Sell, Limit, price) if price <= bid => true
    case (_, Buy, Stop, price)   if price <= ask => true
    case (_, Sell, Stop, price)  if price >= bid => true
    case _                                       => false
  }

  private def getTimestamp(x: WsModel): Option[DateTime] = x match {
    case x:Trade            => Some(x.data.head.timestamp)
    case x:OrderBookSummary => Some(x.timestamp)
    case x:OrderBook        => Some(x.data.head.timestamp)
    case x:Info             => Some(x.timestamp)
    case x:Funding          => Some(x.data.head.timestamp)
    case x:UpsertOrder      => Some(x.data.head.timestamp)
    case _                  => None
  }


  override def placeBulkOrdersAsync(orderReqs: OrderReqs): (Seq[String], Future[Orders]) = {
    implicit val akkaScheduler: Scheduler = simRef.scheduler
    val orderReqs2 = orderReqs.copy(orderReqs.orders.map {
      case o if o.clOrdID.isDefined => o
      case o => o.copy(clOrdID = Some(java.util.UUID.randomUUID().toString))
    })
    val clOrdIDs = orderReqs2.orders.map(o => o.clOrdID.get)
    val resF: Future[Orders] = simRef ? (ref => BulkOrders(orderReqs2, ref))
    (clOrdIDs, resF)
  }

  override def placeMarketOrderAsync(qty: Double, side: OrderSide): (String, Future[Order]) = {
    implicit val akkaScheduler: Scheduler = simRef.scheduler
    val clOrdID = java.util.UUID.randomUUID().toString
    val resF: Future[Order] = simRef ? (ref => SingleOrder(OrderReq(qty, side, OrderType.Market, clOrdID=Some(clOrdID)), ref))
    (clOrdID, resF)
  }

  override def placeLimitOrderAsync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide): (String, Future[Order]) = {
    implicit val akkaScheduler: Scheduler = simRef.scheduler
    val clOrdID = java.util.UUID.randomUUID().toString
    val resF: Future[Order] = simRef ? (ref => SingleOrder(OrderReq(qty, side, OrderType.Limit, price=Some(price), clOrdID=Some(clOrdID)), ref))
    (clOrdID, resF)
  }

  override def amendOrderAsync(orderID: Option[String], origClOrdID: Option[String], price: Double): Future[Order] = {
    implicit val akkaScheduler: Scheduler = simRef.scheduler
    val resF: Future[Order] = simRef ? (ref => AmendOrder(orderID, origClOrdID, price, ref))
    resF
  }

  override def cancelOrderAsync(orderIDs: Seq[String], clOrdIDs: Seq[String]): Future[Orders] = {
    implicit val akkaScheduler: Scheduler = simRef.scheduler
    val resF: Future[Orders] = simRef ? (ref => CancelOrder(orderIDs, clOrdIDs, ref))
    resF
  }

  // position closing - noops
  override def cancelAllOrdersAsync(): Future[Orders] = Future.successful(Orders(Nil))
  override def closePositionAsync(): Future[String] = Future.successful("")
  override def cancelAllOrdersSync(): Try[Orders] = Success(Orders(Nil))
  override def closePositionSync(): Try[String] = Success("")

  // not needed
  override def placeStopOrderAsync(qty: Double, price: Double, isClose: Boolean, side: OrderSide): (String, Future[Order]) = ???
  override def placeTrailingStopOrderAsync(qty: Double, pegOffsetValue: Double, isClose: Boolean, side: OrderSide): (String, Future[Order]) = ???

  override def placeBulkOrdersSync(orderReqs: OrderReqs): Try[Orders] = ???
  override def placeStopOrderSync(qty: Double, price: Double, isClose: Boolean, side: OrderSide): Try[Order] = ???
  override def placeTrailingStopOrderSync(qty: Double, pegOffsetValue: Double, isClose: Boolean, side: OrderSide): Try[Order] = ???
  override def placeMarketOrderSync(qty: Double, side: OrderSide): Try[Order] = ???
  override def placeLimitOrderSync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide): Try[Order] = ???
  override def amendOrderSync(orderID: Option[String], clOrdID: Option[String], price: Double): Try[Order] = ???
  override def cancelOrderSync(orderIDs: Seq[String], origClOrdIDs: Seq[String]): Try[Orders] = ???
}

// From: https://doc.akka.io/docs/akka/2.2/scala/mailboxes.html#Mailbox_configuration_examples
class DryRunMailbox(settings: akka.actor.ActorSystem.Settings, config: Config) extends UnboundedStablePriorityMailbox(
  PriorityGenerator {
    case SendMetrics => 0
    case _:RestEvent => 1
    case _ => 10
  }
)

//class DryRunMailbox extends UnboundedPriorityMailbox(
//  cmp = (o1: Envelope, o2: Envelope) => (o1.message, o2.message) match {
//    case (e1: ActorEvent, e2: ActorEvent) if e1.isPriority =>
//      1
//    case (e1: ActorEvent, e2: ActorEvent) if e2.isPriority =>
//      -1
//    case _ =>
//      0
//  },
//  initialCapacity = 100,
//)
