package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import rcb.OrderLifecycle._

import scala.collection.Set
import scala.concurrent.duration.{Duration, MILLISECONDS, _}
import scala.util.{Failure, Success, Try}

object OrchestratorActor {
  trait PositionOpener {
    val maxMarkupRetries: Int
    val expiryMs: Long
    def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent]         // desired outcome - order filled
    def onExpired(l: Ledger): Behavior[ActorEvent]                               // expired (and order canceled)
    def onUnprocessed(l: Ledger, orderID: String, exc: Option[Throwable]=None): Behavior[ActorEvent]  // something gone wrong... if orderID present - failed to cancel...
    def openOrder(l: Ledger, markupCnt: Int): Try[Order]                         // how to open order
    def cancelOrder(orderID: String): Try[Orders]                                // how to cancel order
  }

  trait PositionCloser {
    def onDone(l: Ledger): Behavior[ActorEvent]                                      // deal with either order filled
    def onUnprocessed(l: Ledger, exc: Option[Throwable]=None): Behavior[ActorEvent]  // something gone wrong... if takeProfitID/stoplossID present - failed to 1 or the other...
    def openOrders(l: Ledger): Try[Orders]                                           // how to open takeProfit/stoploss orders
    def cancelOrders(orderIDs: Seq[String]): Try[Orders]                             // how to cancel order
  }

  def openPosition(ledger: Ledger, positionOpener: PositionOpener, metrics: Option[Metrics]=None): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] {
          case (_, Instrument) =>
            metrics.foreach(metrics => metrics.gauge(ctx.ledger.metrics()))
            Behaviors.same
          case (actorCtx, Expiry) =>
            // expiry timeout, cancel!
            positionOpener.cancelOrder(ctx.orderID) match {
              case Success(os) =>
                val o = os.orders.head  // note - expect only 1 order in cancellation!
                val ledger2 = ctx.ledger.record(o)
                loop(ctx.copy(ledger=ledger2, orderID=o.orderID, markupRetry=ctx.markupRetry+1))
              case Failure(exc) =>
                actorCtx.log.error(s"Failed to issue Expire of orderID: ${ctx.orderID}!")
                positionOpener.onUnprocessed(ledger, ctx.orderID, Some(exc))
            }
          case (actorCtx, WsEvent(data:UpsertOrder)) =>
            val ledger2 = ledger.record(data)
            val updatedCtxOrder = data.data.exists(_.orderID == ctx.orderID)
            if (! updatedCtxOrder)
              loop(ctx.copy(ledger2))
            else {
              val order = ledger2.ledgerOrdersById(ctx.orderID)
              order.lifecycle match {
                case Filled =>
                  timers.cancel(Expiry)
                  positionOpener.onFilled(ledger2, order.price)
                case Canceled =>
                  // presume cancelled due to expiry
                  timers.cancel(Expiry)
                  actorCtx.log.error(s"Canceled, due to expiry(?), orderID: ${ctx.orderID}!")
                  positionOpener.onExpired(ledger2)
                case PostOnlyFailure if ctx.markupRetry == positionOpener.maxMarkupRetries =>
                  timers.cancel(Expiry)
                  actorCtx.log.error(s"Maxed retries of PostOnly failures, orderID: ${ctx.orderID}!")
                  positionOpener.onUnprocessed(ledger2, ctx.orderID)
                case PostOnlyFailure =>
                  // adjust markup and retry
                  timers.cancel(Expiry)
                  val markupRetry2 = ctx.markupRetry + 1
                  positionOpener.openOrder(ledger2, markupRetry2) match {
                    case Success(order) =>
                      val ledger3 = ledger2.record(order)
                      timers.startSingleTimer(Expiry, Duration(positionOpener.expiryMs, MILLISECONDS))
                      loop(ctx.copy(ledger3, markupRetry=markupRetry2)) // keep retrying
                    case Failure(exc) =>
                      actorCtx.log.error(s"Failed to issue Expire of orderID: ${ctx.orderID}!")
                      positionOpener.onUnprocessed(ledger2, ctx.orderID, Some(exc))
                  }
              }
            }
          case (_, WsEvent(data:OrderBook)) => loop(ctx.copy(ledger.record(data)))
          case (_, WsEvent(data:Trade))     => loop(ctx.copy(ledger.record(data)))
        }

      // initial request
      positionOpener.openOrder(ledger, 0) match {
        case Success(o) =>
          timers.startSingleTimer(Expiry, Duration(positionOpener.expiryMs, MILLISECONDS))
          val ctx = OpenPositionCtx(ledger.record(o), o.orderID, markupRetry=positionOpener.maxMarkupRetries)
          loop(ctx)
        case Failure(exc) =>
          positionOpener.onUnprocessed(ledger, null, Some(exc))
      }
    }

  def closePosition(ledger: Ledger, positionCloser: PositionCloser, metrics: Option[Metrics]=None): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>
      // trigger issue first expiry
      timers.startSingleTimer(OpenTakeProfit, Duration(0, MILLISECONDS))

      def loop(ctx: ClosePositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] {
          case (_, Instrument) =>
            metrics.foreach(metrics => metrics.gauge(ctx.ledger.metrics()))
            Behaviors.same
          case (actorCtx, WsEvent(data:UpsertOrder)) =>
            val ledger2 = ledger.record(data)
            val los = data.data.map(od => ledger.ledgerOrdersById(od.orderID))
            val haveFilled = los.exists(_.lifecycle == OrderLifecycle.Filled)
            val haveNewOnly = los.map(_.lifecycle).toSet == Set(OrderLifecycle.New)
            val haveCanceled = los.exists(_.lifecycle == OrderLifecycle.Canceled)
            if (haveNewOnly)
              // keep waiting
              loop(ctx.copy(ledger2))
            else if (haveFilled && haveCanceled)
              // 1 filled, other canceled
              positionCloser.onDone(ledger2)
            else if (haveFilled)
              // 1 filled, other not yet canceled
              positionCloser.cancelOrders(los.map(_.orderID)) match {
                case Success(os) =>
                  val ledger3 = ledger2.record(os)
                  val haveCanceledOnly = los.map(_.lifecycle).toSet == Set(OrderLifecycle.Canceled)
                  if (haveCanceledOnly)
                    positionCloser.onDone(ledger3)
                  else
                    loop(ctx.copy(ledger3))
                case Failure(exc) =>
                  actorCtx.log.error(s"Failed to close position, relevantOrderIDs: ${ctx.relevantOrderIDs}", exc)
                  positionCloser.onUnprocessed(ledger2, Some(exc))
              }
            else
              loop(ctx.copy(ledger2))
        }

      // initial request
      positionCloser.openOrders(ledger) match {
        case Success(os) =>
          val ctx = ClosePositionCtx(ledger.record(os), os.orders.map(_.orderID))
          loop(ctx)
        case Failure(exc) =>
          positionCloser.onUnprocessed(ledger, Some(exc))
      }
      loop(ClosePositionCtx(ledger=ledger))
    }

  def apply(restGateway: IRestGateway,
            tradeQty: Int, minTradeVol: BigDecimal,
            openPositionExpiryMs: Long, backoffMs: Long = 500,
            bullScoreThreshold: BigDecimal=0.25, bearScoreThreshold: BigDecimal= -0.25,
            reqRetries: Int, markupRetries: Int,
            takeProfitMargin: BigDecimal, stoplossMargin: BigDecimal, postOnlyPriceAdj: BigDecimal,
            metrics: Option[Metrics]=None): Behavior[ActorEvent] = {

    assert(bullScoreThreshold > bearScoreThreshold, s"bullScoreThreshold ($bullScoreThreshold) <= bearScoreThreshold ($bearScoreThreshold)")

    /**
     * Gather enough WS data to trade, then switch to idle
     */
    def init(ctx: InitCtx): Behavior[ActorEvent] = Behaviors.withTimers[ActorEvent] { timers =>
      Behaviors.receivePartial[ActorEvent] {
        case (actorCtx, WsEvent(wsData)) =>
          val ledger2 = ctx.ledger.record(wsData)
          if (ledger2.isMinimallyFilled) {
            timers.startTimerAtFixedRate(Instrument, 1.minute)
            actorCtx.log.info(
              """
                |#########################################
                |# Ledger minimally filled, ready to go! #
                |#########################################"""".stripMargin)
            idle(IdleCtx(ledger2))
          } else
            init(ctx.copy(ledger2))
      }
    }

    /**
     * Waiting for market conditions to change to volumous bull or bear
     */
    def idle(ctx: IdleCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] {
      case Instrument =>
        metrics.foreach(metrics => metrics.gauge(ctx.ledger.metrics()))
        Behaviors.same
      case WsEvent(wsData) =>
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
        override def onUnprocessed(l: Ledger, orderID: String, exc: Option[Throwable]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.bidPrice - retryCnt * postOnlyPriceAdj, OrderSide.Buy)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      }, metrics)

    def closeLong(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        def onUnprocessed(l: Ledger, exc: Option[Throwable]=None): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        def openOrders(l: Ledger): Try[Orders] = restGateway.placeBulkOrdersSync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Sell, tradeQty, openPrice + takeProfitMargin),
          OrderReq.asStopMarketOrder(OrderSide.Sell, tradeQty, openPrice - stoplossMargin)))
        )
        def cancelOrders(orderIDs: Seq[String]): Try[Orders] = restGateway.cancelOrderSync(Some(orderIDs), None)
      }, metrics)

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val maxMarkupRetries = markupRetries
        override val expiryMs = openPositionExpiryMs
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeShort(l, openPrice)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, orderID: String, exc: Option[Throwable]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.askPrice + retryCnt * postOnlyPriceAdj, OrderSide.Sell)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      }, metrics)

    def closeShort(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        def onUnprocessed(l: Ledger, exc: Option[Throwable]=None): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        def openOrders(l: Ledger): Try[Orders] = restGateway.placeBulkOrdersSync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Buy, tradeQty, openPrice - takeProfitMargin),
          OrderReq.asStopMarketOrder(OrderSide.Buy, tradeQty, openPrice + stoplossMargin)))
        )
        def cancelOrders(orderIDs: Seq[String]): Try[Orders] = restGateway.cancelOrderSync(Some(orderIDs), None)
      }, metrics)

    init(InitCtx(ledger = Ledger()))
  }
}
