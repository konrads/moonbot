package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.{Duration, SECONDS}
import scala.util.{Failure, Try}

object OrchestratorActor {
  trait RestReqProcessor {
    val retries: Int
    def onFilled(c: OpenOrderCtx): Behavior[ActorEvent]
    def onCanceled(c: OpenOrderCtx): Behavior[ActorEvent]
    def onExpired(c: OpenOrderCtx): Behavior[ActorEvent]
    def onFailure(c: OpenOrderCtx, exc: Throwable): Behavior[ActorEvent]
    def onPostOnlyFailure(c: OpenOrderCtx, retry: Int): Option[Behavior[ActorEvent]] = None  // might use to markup original order
    def processRequest(ctx: OpenOrderCtx): Try[Order]
    def processCancel(ctx: OpenOrderCtx): Try[Orders]
  }

  def processRestReq(ledger: Ledger, retries: Int=0, timeoutSecs: Int, backoffSecs: Int, reqProcessor: RestReqProcessor): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>
      // trigger issue first expiry
      timers.startSingleTimer(TriggerReq, Duration(0, SECONDS))

      def loop(ctx: OpenOrderCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] {
          case (actorCtx, TriggerCancel) =>
            if (ctx.retries >= 0) {
              val canceledCtx = reqProcessor.processCancel(ctx) match {
                case scala.util.Success(os) =>
                  val ledger2 = os.orders.foldLeft(ctx.ledger) { case (ledger, o) => ledger.record(o) }
                  actorCtx.self ! TriggerReq
                  ctx.copy(ledger = ledger2, orderID = null)
                case Failure(e:TemporarilyUnavailableError) =>
                  timers.startSingleTimer(TriggerCancel, Duration(backoffSecs, SECONDS))
                  ctx.copy(retries = ctx.retries - 1)
                case Failure(exc) =>
                  actorCtx.log.error(s"Failed on cancellation of order: ${ctx.orderID}", exc)
                  throw new Exception(s"Failed on cancellation of order: ${ctx.orderID}", exc)
                }
                loop(canceledCtx)
            } else
              reqProcessor.onFailure(ExceededRetriesError(ctx))
          case (actorCtx, TriggerReq) =>
            // reissue request if not exceeded retries
            if (ctx.retries >= 0) {
              val reissuedCtx =
                reqProcessor.processRequest(ctx) match {
                  case scala.util.Success(o) =>
                    val ledger2 = ledger.record(o)
                    ctx.copy(ledger = ledger2, retries = retries - 1)
                  case Failure(e:TemporarilyUnavailableError) =>
                    timers.startSingleTimer(TriggerReq, Duration(backoffSecs, SECONDS))
                    ctx.copy(retries = ctx.retries - 1)
                  case Failure(exc) =>
                    actorCtx.log.error(s"Failed on request of order: ${ctx.orderID}", exc)
                    throw new Exception(s"Failed on request order: ${ctx.orderID}", exc)
                }
              loop(reissuedCtx)
            } else
              reqProcessor.onExceededRetries(ctx)
          case (actorCtx, WsEvent(wsData)) =>
            // accumulate WS events
            val ctx2 = ctx.copy(ledger = ctx.ledger.record(wsData))
            ctx2.ledger.byOrderID(ctx.orderID) match {
              case Some(o) => o.lifecycle match {
                case OrderLifecycle.Canceled => reqProcessor.onCanceled(ctx2)
                case OrderLifecycle.Filled => reqProcessor.onFilled(ctx2)
                case OrderLifecycle.PostOnlyFailure => reqProcessor.onPostOnlyFailure(ctx2, ctx2.retries).getOrElse(reqProcessor.onCanceled(ctx2))
                case OrderLifecycle.Unknown => actorCtx.log.error(s"Have order in Unknown lifecycle state: $o\nkeeping course..."); loop(ctx2)
                case _ => loop(ctx2)
              }
              case None =>
                // not recorded yet...
                actorCtx.log.debug(s"Encountered WS order not yet recorded in ledger via REST... : ${ctx.orderID}")
                loop(ctx2)
            }
        }

      loop(OpenOrderCtx(ledger, retries=retries))
    }

  def apply(restGateway: IRestGateway, tradeQty: Int, tradeTimeoutSecs: Int, holdTimeoutSecs: Int, backoffSecs: Int = 1, maxRetries: Int, markup: BigDecimal, stoploss: BigDecimal): Behavior[ActorEvent] = {
    /**
     * Idle, ie. not in long or short position:
     * - waits for buy/sell opportunity
     * - issues limit buy/sell
     * - awaits
     *   - if filled, issues limit order & progresses to next state
     *   - else continues in idle
     */
    def idle(ctx: IdleCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] {
      case WsEvent(wsData) =>
        val ledger2 = ctx.ledger.record(wsData)
        if (ledger2.canOpenLong)
          openLong(ledger2)
        //        else if (ledger2.canOpenShort)
        //          openShort(ledger2)
        else
        idle(ctx.copy(ledger = ledger2))
    }

    def openLong(ledger: Ledger): Behavior[ActorEvent] =
      processRestReq(ledger, maxRetries, tradeTimeoutSecs, backoffSecs, new RestReqProcessor {
        val retries: Int = maxRetries
        override def onFilled(c: OpenOrderCtx) = longTakeProfit(c.ledger)
        override def onCanceled(c: OpenOrderCtx) = idle(IdleCtx(ledger = c.ledger))
        override def onExpired(c: OpenOrderCtx) = idle(IdleCtx(ledger = c.ledger))
        override def onFailure(c: OpenOrderCtx, exc: Throwable) = idle(IdleCtx(ledger = c.ledger))
        //  override def onPostOnlyFailure(c: OpenOrderCtx) = Some(idle(IdleCtx(ledger = c.ledger)))
        override def processRequest(ctx: OpenOrderCtx): Try[Order] = restGateway.placeLimitOrderSync(tradeQty: BigDecimal, ctx.ledger.bidPrice, OrderSide.Sell)
        override def processCancel(ctx: OpenOrderCtx): Try[Orders] = restGateway.cancelOrderSync(ctx.orderID)
      })

    def longTakeProfit(ledger: Ledger): Behavior[ActorEvent] =
      processRestReq(ledger, maxRetries, holdTimeoutSecs, backoffSecs, new RestReqProcessor {
        val retries: Int = maxRetries
        override def onFilled(c: OpenOrderCtx) = idle(IdleCtx(c.ledger))
        override def onCanceled(c: OpenOrderCtx) = longTakeProfit(c.ledger)
        override def onExpired(c: OpenOrderCtx) = longStoploss(c.ledger)
        override def onFailure(c: OpenOrderCtx, exc: Throwable) = longTakeProfit(c.ledger)
        // override def onPostOnlyFailure(c: OpenOrderCtx) = Some(longTakeProfit(c.ledger))
        override def processRequest(ctx: OpenOrderCtx): Try[Order] = {
          val limitPrice = ctx.ledger.bidPrice + markup
          restGateway.placeLimitOrderSync(tradeQty: BigDecimal, limitPrice, OrderSide.Sell)
        }
        override def processCancel(ctx: OpenOrderCtx): Try[Orders] = restGateway.cancelOrderSync(ctx.orderID)
      })

    def longStoploss(ledger: Ledger): Behavior[ActorEvent] =
      processRestReq(ledger, maxRetries, tradeTimeoutSecs, backoffSecs, new RestReqProcessor {
        val retries: Int = maxRetries
        def onFilled(c: OpenOrderCtx) = idle(IdleCtx(c.ledger))
        def onCanceled(c: OpenOrderCtx) = longStoploss(c.ledger)
        def onExpired(c: OpenOrderCtx) = longStoploss(c.ledger)
        def onFailure(c: OpenOrderCtx, exc: Throwable) = longStoploss(c.ledger)

        def processRequest(ctx: OpenOrderCtx): Try[Order] = {
          restGateway.placeMarketOrderSync(tradeQty: BigDecimal, OrderSide.Sell)
        }
        override def processCancel(ctx: OpenOrderCtx): Try[Orders] = restGateway.cancelOrderSync(ctx.orderID)
      })

    idle(IdleCtx(ledger = new Ledger()))
  }
}
