package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

import scala.collection.Set
import scala.concurrent.duration.{Duration, MILLISECONDS, _}
import scala.util.{Failure, Success, Try}
import rcb.TradeLifecycle._
import rcb.OrderStatus._

object OrchestratorActor {
  trait PositionOpener {
    val maxMarkupRetries: Int
    val expiryMs: Long
    def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent]             // desired outcome - order filled
    def onExpired(l: Ledger): Behavior[ActorEvent]                                   // expired (and order canceled)
    def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent]           // unexpected cancel (not from bot)
    def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent]  // coding error???
    def openOrder(l: Ledger, markupCnt: Int): Try[Order]                             // how to open order
    def cancelOrder(orderID: String): Try[Orders]                                    // how to cancel order
    def backoffStrategy(retry: Int): Int = Array(0, 100, 200, 500, 1000, 2000, 5000, 1000)(math.max(retry, 7))
  }

  trait PositionCloser {
    def onDone(l: Ledger): Behavior[ActorEvent]                                      // takeProfit or stoploss filled
    def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent]    // unexpected cancel (not from bot)
    def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent]  // coding error???
    def openOrders(l: Ledger): Try[Orders]                                           // how to open orders
    def cancelOrders(orderIDs: Seq[String]): Try[Orders]                             // how to cancel orders
    def backoffStrategy(retry: Int): Int = Array(0, 100, 200, 500, 1000, 2000, 5000, 1000)(math.max(retry, 7))
  }

  def openPosition(initLedger: Ledger, positionOpener: PositionOpener, metrics: Option[Metrics]=None)(implicit log: Logger): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] { case (actorCtx, wsEvent) =>
          (ctx, wsEvent) match {
            case (_, Instrument) =>
              val ledger2 = ctx.ledger.withMetrics()
              ledger2.ledgerMetrics.foreach(lm => metrics.foreach(m => m.gauge(lm.metrics))) // FIXME: fugly
              loop(ctx.copy(ledger2))
            case (_, Expiry) if ctx.orderID != null =>
              actorCtx.self ! Cancel(ctx.orderID)
              loop(ctx.copy(lifecycle = Cancelling))
            case (_, Expiry) => // no orderID, nothing to cancel
              positionOpener.onExpired(ctx.ledger)
            case (_, Cancel(orderID)) => // no limit on retries
              positionOpener.cancelOrder(orderID) match {
                case Success(os) =>
                  val ledger2 = ctx.ledger.record(os)
                  val order = ledger2.ledgerOrdersById(orderID)
                  if (order.ordStatus == Canceled) {
                    actorCtx.log.info(s"Expired orderID: ${order.orderID}")
                    timers.cancelAll()
                    positionOpener.onExpired(ledger2)
                  } else
                    loop(ctx.copy(ledger=ledger2, lifecycle=Cancelling))
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Cancel(orderID)
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"Unexpected failure of cancellation of orderID: $orderID", exc)
                  timers.cancelAll()
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(orderID), exc)
              }
            case (_, Issue) =>
              positionOpener.openOrder(ctx.ledger, ctx.markupRetry) match {
                case Success(o) =>
                  // FIXME: not checking the status here, relying on WS. Some statuses eg. Filled, PostOnlyFailure are only delivered via WS
                  val ledger2 = ctx.ledger.record(o)
                  loop(ctx.copy(ledger=ledger2, orderID=o.orderID, lifecycle=Issuing))
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Issue
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"Unexpected failure of issuing of orders", exc)
                  timers.cancelAll()
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(ctx.orderID), exc)
              }
            case (OpenPositionCtx(ledger, orderID, Issuing, _), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              val order = ledger2.ledgerOrdersById(orderID)
              order.ordStatus match {
                case Filled =>
                  actorCtx.log.info(s"Filled orderID: $orderID @ ${order.price}")
                  timers.cancelAll()
                  positionOpener.onFilled(ledger2, order.price)
                case PostOnlyFailure =>
                  val backoff = positionOpener.backoffStrategy(ctx.markupRetry)
                  timers.startSingleTimer(Issue, Duration(backoff, MILLISECONDS))
                  loop(ctx.copy(ledger=ledger2, markupRetry=ctx.markupRetry+1))
                case Canceled =>
                  positionOpener.onExternalCancel(ledger2, order.orderID)
                case _ =>
                  loop(ctx.copy(ledger = ledger2))
              }
            case (OpenPositionCtx(_, orderID, Cancelling, _), WsEvent(data)) =>
              val ledger2 = ctx.ledger.record(data)
              val order = ledger2.ledgerOrdersById(orderID)
              order.ordStatus match {
                case Filled =>
                  actorCtx.log.info(s"Filled (2) orderID: $orderID @ ${order.price}")
                  timers.cancelAll()
                  positionOpener.onFilled(ledger2, order.price)
                case PostOnlyFailure | Canceled =>
                  actorCtx.log.info(s"Expired orderID: $orderID")
                  timers.cancelAll()
                  positionOpener.onExpired(ledger2)
                case _ =>
                  loop(ctx.copy(ledger = ledger2))
              }
            case (ctx, data) =>
              actorCtx.log.error(s"### openPosition: Unexpected combo of ctx:\n$ctx\ndata:\n$data")
              Behaviors.same
          }
      }

      // initial request
      positionOpener.openOrder(initLedger, 0) match {
        case Success(o) =>
          log.info(s"Initial position opening, orderID: ${o.orderID}")
          timers.startSingleTimer(Expiry, Duration(positionOpener.expiryMs, MILLISECONDS))
          val ctx = OpenPositionCtx(initLedger.record(o), o.orderID)
          loop(ctx)
        case Failure(exc:RecoverableError) =>
          log.warn(s"Initial position opening recoverable error", exc) // FIXME inelegant way of re-starting failed request
          timers.startSingleTimer(Issue, 0.microseconds)
          loop(OpenPositionCtx(initLedger, null))
        case Failure(exc) =>
          log.info(s"Initial position opening failure!", exc)
          positionOpener.onIrrecoverableError(initLedger, None, exc)
      }
    }

  def closePosition(initLedger: Ledger, positionCloser: PositionCloser, metrics: Option[Metrics]=None)(implicit log: Logger): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: ClosePositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] { case (actorCtx, wsEvent) =>
          (ctx, wsEvent) match {
            case (_, Instrument) =>
              val ledger2 = ctx.ledger.withMetrics()
              ledger2.ledgerMetrics.foreach(lm => metrics.foreach(m => m.gauge(lm.metrics))) // FIXME: fugly
              loop(ctx.copy(ledger2))
            case (_, Cancel(orderIDs@_*)) => // no limit on retries
              positionCloser.cancelOrders(orderIDs) match {
                case Success(os) =>
                  val ledger2 = ctx.ledger.record(os)
                  val orders = orderIDs.map(ledger2.ledgerOrdersById)
                  val orderStatuses = orders.map(_.ordStatus).toSet
                  if (orderStatuses == Set(Filled, Canceled)) {
                    actorCtx.log.info(s"Got a fill and cancel in orderIDs: ${orderIDs.mkString(", ")}")
                    positionCloser.onDone(ledger2)
                  } else
                    loop(ctx.copy(ledger=ledger2, lifecycle=Cancelling))
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Cancel(orderIDs:_*)
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"Unexpected failure of cancellation of orderIDs: ${orderIDs.mkString(", ")}", exc)
                  positionCloser.onIrrecoverableError(ctx.ledger, ctx.orderIDs, exc)
              }
            case (_, Issue) =>
              positionCloser.openOrders(ctx.ledger) match {
                case Success(os) =>
                  // FIXME: not checking the status here, relying on WS. Some statuses eg. Filled, PostOnlyFailure are only delivered via WS
                  val ledger2 = ctx.ledger.record(os)
                  val orderIDs = os.orders.map(_.orderID)
                  loop(ctx.copy(ledger=ledger2, orderIDs=orderIDs, lifecycle=Issuing))
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Issue
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"Unexpected failure of issuing of orders", exc)
                  positionCloser.onIrrecoverableError(ctx.ledger, ctx.orderIDs, exc)
              }
            case (ClosePositionCtx(ledger, orderIDs, Issuing), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              val orders = orderIDs.map(ledger2.ledgerOrdersById)
              val orderStatuses = orders.map(_.ordStatus).toSet
              if (orderStatuses == Set(Canceled))
                positionCloser.onExternalCancels(ledger, orderIDs)
              else if (orderStatuses == Set(Filled, Canceled))
                positionCloser.onDone(ledger2)
              else if (orderStatuses.contains(Filled)) {
                actorCtx.self ! Cancel(orderIDs:_*)  // FIXME: hack, canceling even the filled order
                loop(ctx.copy(ledger=ledger2))
              } else
                loop(ctx.copy(ledger=ledger2))
            case (ctx, data) =>
              actorCtx.log.error(s"### closePosition: Unexpected combo of ctx:\n$ctx\ndata:\n$data")
              Behaviors.same
          }
        }

      // initial request
      positionCloser.openOrders(initLedger) match {
        case Success(os) =>
          val orderIDs = os.orders.map(_.orderID)
          log.info(s"Initial position opening, orderID: ${orderIDs.mkString(", ")}")
          val ctx = ClosePositionCtx(initLedger.record(os), orderIDs)
          loop(ctx)
        case Failure(exc:RecoverableError) =>
          log.warn(s"Initial position opening recoverable error", exc) // FIXME inelegant way of re-starting failed request
          timers.startSingleTimer(Issue, 0.microseconds)
          loop(ClosePositionCtx(initLedger, null))
        case Failure(exc) =>
          log.info(s"Initial position opening failure!", exc)
          positionCloser.onIrrecoverableError(initLedger, Nil, exc)
      }
    }

  def apply(restGateway: IRestGateway,
            tradeQty: Int, minTradeVol: BigDecimal,
            openPositionExpiryMs: Long, backoffMs: Long = 500,
            bullScoreThreshold: BigDecimal=0.25, bearScoreThreshold: BigDecimal= -0.25,
            reqRetries: Int, markupRetries: Int,
            takeProfitMargin: BigDecimal, stoplossMargin: BigDecimal, postOnlyPriceAdj: BigDecimal,
            metrics: Option[Metrics]=None)(implicit log: Logger): Behavior[ActorEvent] = {

    assert(bullScoreThreshold > bearScoreThreshold, s"bullScoreThreshold ($bullScoreThreshold) <= bearScoreThreshold ($bearScoreThreshold)")

    /**
     * Gather enough WS data to trade, then switch to idle
     */
    def init(ctx: InitCtx): Behavior[ActorEvent] = Behaviors.withTimers[ActorEvent] { timers =>
      Behaviors.receivePartial[ActorEvent] {
        case (actorCtx, WsEvent(wsData)) =>
          actorCtx.log.debug(s"init, received: $wsData")
          val ledger2 = ctx.ledger.record(wsData)
          if (ledger2.isMinimallyFilled) {
            timers.startTimerAtFixedRate(Instrument, 1.minute)
            // border from: https://www.asciiart.eu/art-and-design/borders
            actorCtx.log.info(
              """
                |.-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-.
                ||                                             |
                ||   Ledger minimally filled, ready to go!     |
                ||                                             |
                |`-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-'""".stripMargin)
            idle(IdleCtx(ledger2))
          } else
            init(ctx.copy(ledger2))
      }
    }

    /**
     * Waiting for market conditions to change to volumous bull or bear
     */
    def idle(ctx: IdleCtx): Behavior[ActorEvent] = Behaviors.receivePartial[ActorEvent] {
      case (_, Instrument) =>
        val ledger2 = ctx.ledger.withMetrics()
        ledger2.ledgerMetrics.foreach(lm => metrics.foreach(m => m.gauge(lm.metrics))) // FIXME: fugly
        idle(ctx.copy(ledger2))
      case (actorCtx, WsEvent(wsData)) =>
        actorCtx.log.debug(s"idle, received: $wsData")
        val ledger2 = ctx.ledger.record(wsData)
        if (ledger2.orderBookHeadVolume > minTradeVol && ledger2.sentimentScore >= bullScoreThreshold)
          openLong(ledger2)
        else if (ledger2.orderBookHeadVolume > minTradeVol && ledger2.sentimentScore <= bearScoreThreshold)
          openShort(ledger2)
        else
          idle(ctx.copy(ledger = ledger2))
    }

    def openLong(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val maxMarkupRetries = markupRetries
        override val expiryMs = openPositionExpiryMs
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeLong(l, openPrice)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $orderID")
        override def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $orderID", exc)
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.bidPrice - retryCnt * postOnlyPriceAdj, OrderSide.Buy)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      }, metrics)

    def closeLong(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDs.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDs.mkString(", ")}", exc)
        override def openOrders(l: Ledger): Try[Orders] = restGateway.placeBulkOrdersSync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Sell, tradeQty, openPrice + takeProfitMargin),
          OrderReq.asStopMarketOrder(OrderSide.Sell, tradeQty, openPrice - stoplossMargin)))
        )
        override def cancelOrders(orderIDs: Seq[String]): Try[Orders] = restGateway.cancelOrderSync(Some(orderIDs), None)
      }, metrics)

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val maxMarkupRetries = markupRetries
        override val expiryMs = openPositionExpiryMs
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeShort(l, openPrice)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID")
        override def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID", exc)
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.askPrice + retryCnt * postOnlyPriceAdj, OrderSide.Sell)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      }, metrics)

    def closeShort(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing orderID: ${orderIDs.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing orderID: ${orderIDs.mkString(", ")}", exc)
        override def openOrders(l: Ledger): Try[Orders] = restGateway.placeBulkOrdersSync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Buy, tradeQty, openPrice - takeProfitMargin),
          OrderReq.asStopMarketOrder(OrderSide.Buy, tradeQty, openPrice + stoplossMargin)))
        )
        override def cancelOrders(orderIDs: Seq[String]): Try[Orders] = restGateway.cancelOrderSync(Some(orderIDs), None)
      }, metrics)

    init(InitCtx(ledger = Ledger()))
  }
}
