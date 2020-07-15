package moon

import java.io.File

import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.RunType._
import org.joda.time.DateTime
import play.api.libs.json.{JsError, JsSuccess}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Success, Try}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import ExchangeSim._

/**
 * Exchange simulator, interacting via:
 * - IRestGateway for issuing orders/cancels/amends
 * - sending messages to actorRef with exchange feedback
 *
 * Initial messages are read from file and fed through via actorRef !
 */
// From: https://doc.akka.io/docs/akka/2.2/scala/mailboxes.html#Mailbox_configuration_examples
//class DryRunMailbox(settings: akka.actor.ActorSystem.Settings, config: Config) extends UnboundedStablePriorityMailbox(
//  PriorityGenerator {
//    case SendMetrics => 0
//    case _:RestEvent => 0
//    case _ => 10
//  }
//)

object ExchangeSim {
  case class LoopCtx(eventIter: Iterator[WsModel], orders: Seq[Order]=Vector.empty, bid: Double=0, ask: Double=0, timestamp: DateTime=null, msgCnt: Long=1, lastMetricsMs: Long=0)

  // https://discuss.lightbend.com/t/using-ask-pattern-from-outside-of-typed-system-type-mismatch/2445
  def apply(dataDir: String, orchestratorRef: ActorRef[ActorEvent]): Behavior[SimEvent] = Behaviors.setup[SimEvent] { actorCtx =>
    // achieve priorities via https://doc.akka.io/docs/akka/current/typed/mailboxes.html
    //val props = MailboxSelector.fromConfig("akka.dry-run-mailbox")
    //val orchestratorRef = actorCtx.spawn(orchestrator, "orchestrator-actor", props)

    val eventIter: Iterator[WsModel] = {
      var cache: OrderBookSummary = null
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
          case JsSuccess(x: OrderBook, _) =>
            val summary = x.summary
            if (!summary.isEquivalent(cache)) {
              cache = summary
              Seq(summary)
            } else
              Nil
          case JsSuccess(x, _) =>
            Seq(x)
          case JsError(e) =>
            actorCtx.log.error("WS consume error!", e)
            Nil
        }).iterator
      } yield {
        if (filename != prevFilename) {
          actorCtx.log.info(s"Processing data file $dataDir/$filename...")
          prevFilename = filename
        }
        msg
      }
    }

    def loop(ctx: LoopCtx): Behavior[SimEvent] = Behaviors.receiveMessage[SimEvent] {
      case EventProcessed() =>
        val drainedCnt = 1
        if (ctx.msgCnt <= 1) {
          // drained, issue another WS event
          if (eventIter.hasNext) {
            val event = eventIter.next
            //if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Drained, remaining messages: ${ctx.msgCnt}, issuing: $event")
            val timestampO = getTimestamp(event)

            val (sentMetricsCnt, lastMetricsMs2) = timestampO match {
              case Some(ts) if ctx.lastMetricsMs <= 0 =>  // first instance
                (0, ts.getMillis + 60*1000)
              case Some(ts) if ts.getMillis >= ctx.lastMetricsMs + 60*1000 =>
                val nextMetricsMs = ctx.lastMetricsMs + 60*1000
                orchestratorRef ! SendMetrics(Some(nextMetricsMs))
                (1, nextMetricsMs)
              case _ =>
                (0, ctx.lastMetricsMs)
            }

            event match {
              case t:Trade =>
                val firstT = t.data.head
                val (ask, bid) = if (firstT.side == Buy)
                  (firstT.price, firstT.price - 0.5)
                else
                  (firstT.price + 0.5, firstT.price)
                val (shouldFill, shouldntFill) = ctx.orders.partition(canFill(bid, ask))
                val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
                val orders2 = filled ++ shouldntFill
                orchestratorRef ! WsEvent(t)
                // has price update triggered any orders?
                val msgCnt2 = if (filled.nonEmpty) {
                  if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Filled (0) bid: $bid, ask: $ask, ${filled.mkString(", ")}")
                  orchestratorRef ! WsEvent(toWsModel(filled))
                  ctx.msgCnt+2+sentMetricsCnt-drainedCnt
                } else
                  ctx.msgCnt+1+sentMetricsCnt-drainedCnt
                loop(ctx.copy(orders=orders2, bid=bid, ask=ask, timestamp=timestampO.get, msgCnt=msgCnt2, lastMetricsMs=lastMetricsMs2))
              case obs:OrderBookSummary =>
                val (shouldFill, shouldntFill) = ctx.orders.partition(canFill(obs.bid, obs.ask))
                val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
                val orders2 = filled ++ shouldntFill
                // if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Updating with $obs")
                orchestratorRef ! WsEvent(obs)
                // has price update triggered any orders?
                val msgCnt2 = if (filled.nonEmpty) {
                  if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Filled (1) bid: ${obs.bid}, ask: ${obs.ask}, ${filled.mkString(", ")}")
                  orchestratorRef ! WsEvent(toWsModel(filled))
                  ctx.msgCnt+2+sentMetricsCnt-drainedCnt
                } else
                  ctx.msgCnt+1+sentMetricsCnt-drainedCnt
                loop(ctx.copy(orders=orders2, bid=obs.bid, ask=obs.ask, timestamp=timestampO.get, msgCnt=msgCnt2, lastMetricsMs=lastMetricsMs2))
              case _: OrderBook =>
                throw new Exception("UNEXPECTED!!! should use OrderBookSummary instead...")
              case other =>
                orchestratorRef ! WsEvent(other)
                loop(ctx.copy(timestamp=timestampO.getOrElse(ctx.timestamp), msgCnt=ctx.msgCnt+1+sentMetricsCnt-drainedCnt, lastMetricsMs=lastMetricsMs2))
            }
          } else
          // FIXME: die, kill, poison pill?
            throw new Exception("Out of WS json messages!!!")
        } else {
          // keep draining the orchestrator mailbox
          // if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Drained & waiting, remaining messages: ${ctx.msgCnt}")
          if (ctx.msgCnt > 4) actorCtx.log.warn(s"!!! 0. excessive msgCnt: ${ctx.msgCnt}")
          loop(ctx.copy(msgCnt=ctx.msgCnt-drainedCnt))
        }
      case x:BulkOrders =>
        if (ctx.msgCnt > 4) actorCtx.log.warn(s"!!! 1. excessive msgCnt: ${ctx.msgCnt}")
        val orders = x.orderReqs.orders.map(o => createOrder(o, ctx.timestamp, ctx.bid, ctx.ask))
        val (shouldFill, shouldntFill) = orders.partition(canFill(ctx.bid, ctx.ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val orders2 = filled ++ shouldntFill
        if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Issued orders bid: ${ctx.bid}, ask: ${ctx.ask}: ${orders2.mkString(", ")}")
        x.replyTo ! Orders(orders2)
        loop(ctx.copy(orders = ctx.orders ++ orders2, msgCnt = ctx.msgCnt+1))
      case x:SingleOrder =>
        if (ctx.msgCnt > 4) actorCtx.log.warn(s"!!! 2. excessive msgCnt: ${ctx.msgCnt}")
        val order = createOrder(x.orderReq, ctx.timestamp, ctx.bid, ctx.ask)
        val order2 = if (canFill(ctx.bid, ctx.ask)(order)) order.copy(ordStatus=Some(Filled)) else order
        // reply with all orders, but only keep state of non filled ones
        if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Issued 1 order bid: ${ctx.bid}, ask: ${ctx.ask}: $order2")
        x.replyTo ! order2
        loop(ctx.copy(orders = ctx.orders :+ order2, msgCnt = ctx.msgCnt+1))
      case x:AmendOrder =>
        if (ctx.msgCnt > 4) actorCtx.log.warn(s"!!! 3. excessive msgCnt: ${ctx.msgCnt}")
        val (matched, unmatched) = ctx.orders.partition(v => (v.clOrdID.exists(x.origClOrdID.contains) || x.orderID.contains(v.orderID)) && v.ordStatus.exists(os => os != Filled || os != Canceled))
        val withNewPrice = matched.map(v => v.copy(price = Some(x.price)))
        val (shouldFill, shouldntFill) = withNewPrice.partition(canFill(ctx.bid, ctx.ask))
        val filled = shouldFill.map(_.copy(ordStatus = Some(Filled)))
        val withNewState = filled ++ shouldntFill
        if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Amended: ${x.origClOrdID} / ${x.orderID} @ ${x.price}, ${withNewState.mkString(", ")}")
        // if (filled.nonEmpty) orchestratorRef ! WsEvent(toWsModel(filled))
        assert(withNewState.size == 1)  // presume only 1!
        x.replyTo ! withNewState.head
        loop(ctx.copy(orders=unmatched ++ withNewState, msgCnt = ctx.msgCnt+1))
      case x:CancelOrder =>
        if (ctx.msgCnt > 4) actorCtx.log.warn(s"!!! 4. excessive msgCnt: ${ctx.msgCnt}")
        val (matched, unmatched) = ctx.orders.partition(v => (v.clOrdID.exists(x.clOrdID.contains) || x.orderID.contains(v.orderID)) && v.ordStatus.exists(os => os != Filled || os != Canceled))
        val withNewState = matched.map(o => o.copy(ordStatus = Some(Canceled)))
        if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"Cancelled: ${x.clOrdID.mkString(";")} / ${x.orderID.mkString(";")},  ${withNewState.mkString(", ")}")
        // orchestratorRef ! WsEvent(toWsModel(withNewState))
        x.replyTo ! Orders(withNewState)
        loop(ctx.copy(orders=unmatched ++ withNewState, msgCnt = ctx.msgCnt+1))
    }
    actorCtx.self ! EventProcessed() // kick off the process
    loop(LoopCtx(eventIter))
  }

  def setup(
      dataDir: String,
      strategy: Strategy,
      flushSessionOnRestart: Boolean=true,
      tradeQty: Int, minTradeVol: Double,
      openPositionExpiryMs: Long,
      reqRetries: Int, markupRetries: Int,
      takeProfitMargin: Double, stoplossMargin: Double, postOnlyPriceAdj: Double,
      metrics: Option[Metrics]=None,
      openWithMarket: Boolean=false,
      runType: RunType.Value=Live)(implicit execCtx: ExecutionContext): Behavior[SimEvent] = Behaviors.setup[SimEvent] { actorCtx =>
    implicit val akkaScheduler: Scheduler = actorCtx.system.scheduler
    implicit val timeout: Timeout = 5.seconds

    val simRestGateway = new ExchangeSimRestGateway(actorCtx.self)
    val orchestrator = OrchestratorActor(
      strategy = strategy,
      flushSessionOnRestart = flushSessionOnRestart,
      restGateway = simRestGateway,
      tradeQty = tradeQty, minTradeVol = minTradeVol,
      openPositionExpiryMs = openPositionExpiryMs,
      reqRetries = reqRetries, markupRetries = markupRetries,
      takeProfitMargin = takeProfitMargin, stoplossMargin = stoplossMargin, postOnlyPriceAdj = postOnlyPriceAdj,
      metrics = metrics,
      openWithMarket = openWithMarket,
      eventProcessedNotifier = Some(actorCtx.self),
      runType = runType)
    val orchestratorRef = actorCtx.spawn(orchestrator, "orchestrator-actor")
    val simRef = actorCtx.spawn(ExchangeSim(dataDir, orchestratorRef), "sim-actor")

    // handles both EventProcessed from orchestrator, and other from ExchangeSimRestGateway
    Behaviors.receiveMessage[SimEvent] {
      x =>
        simRef ! x
        Behaviors.same
    }
  }

  def getTimestamp(x: WsModel): Option[DateTime] = x match {
    case x:Trade            => Some(x.data.head.timestamp)
    case x:OrderBookSummary => Some(x.timestamp)
    case x:OrderBook        => Some(x.data.head.timestamp)
    case x:Info             => Some(x.timestamp)
    case x:Funding          => Some(x.data.head.timestamp)
    case x:UpsertOrder      => Some(x.data.head.timestamp)
    case _                  => None
  }

  def toWsModel(os: Seq[Order]): UpsertOrder =
    UpsertOrder(Some("update"), os.map(o => OrderData(orderID=o.orderID, clOrdID=o.clOrdID, orderQty=Some(o.orderQty), price=o.price, side=Some(o.side), ordStatus=o.ordStatus, ordType=Some(o.ordType), timestamp=o.timestamp)))

  def createOrder(orderReq: OrderReq, timestamp: DateTime, bid: Double, ask: Double): Order = {
    val orderID = uuid
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

  def canFill(bid: Double, ask: Double)(o: Order): Boolean = (o.ordStatus, o.side, o.ordType, o.price.get) match {
    case (Some(Filled), _, _, _)                 => false
    case (Some(Canceled), _, _, _)               => false
    case (_, _,  Market, _)                      => true
    case (_, Buy,  Limit, price) if price >= ask => true
    case (_, Sell, Limit, price) if price <= bid => true
    case (_, Buy, Stop, price)   if price <= ask => true
    case (_, Sell, Stop, price)  if price >= bid => true
    case _                                       => false
  }
}

class ExchangeSimRestGateway(simRef: ActorRef[SimEvent])(implicit val akkaScheduler: Scheduler, val timeout: Timeout) extends IRestGateway {  // (implicit execCtx: ExecutionContext)
  override def placeBulkOrdersAsync(orderReqs: OrderReqs): (Seq[String], Future[Orders]) = {
    val orderReqs2 = orderReqs.copy(orderReqs.orders.map {
      case o if o.clOrdID.isDefined => o
      case o => o.copy(clOrdID = Some(uuid))
    })
    val clOrdIDs = orderReqs2.orders.map(o => o.clOrdID.get)
    val resF: Future[Orders] = simRef ? (ref => BulkOrders(orderReqs2, ref))
    (clOrdIDs, resF)
  }

  override def placeMarketOrderAsync(qty: Double, side: OrderSide): (String, Future[Order]) = {
    val clOrdID = uuid
    val resF: Future[Order] = simRef ? (ref => SingleOrder(OrderReq(qty, side, OrderType.Market, clOrdID=Some(clOrdID)), ref))
    (clOrdID, resF)
  }

  override def placeLimitOrderAsync(qty: Double, price: Double, isReduceOnly: Boolean, side: OrderSide): (String, Future[Order]) = {
    val clOrdID = uuid
    val resF: Future[Order] = simRef ? (ref => SingleOrder(OrderReq(qty, side, OrderType.Limit, price=Some(price), clOrdID=Some(clOrdID)), ref))
    (clOrdID, resF)
  }

  override def amendOrderAsync(orderID: Option[String], origClOrdID: Option[String], price: Double): Future[Order] = {
    val resF: Future[Order] = simRef ? (ref => AmendOrder(orderID, origClOrdID, price, ref))
    resF
  }

  override def cancelOrderAsync(orderIDs: Seq[String], clOrdIDs: Seq[String]): Future[Orders] = {
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
