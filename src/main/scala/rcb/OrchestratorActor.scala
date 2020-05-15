package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.{Duration, MILLISECONDS}
import scala.util.{Failure, Success, Try}

object OrchestratorActor {
  trait PositionOpener {
    def onFilled(l: Ledger): Behavior[ActorEvent]                                // desired outcome - order filled
    def onExpired(l: Ledger): Behavior[ActorEvent]                               // expired (and order canceled)
    def onUnprocessed(l: Ledger, orderID: Option[String]): Behavior[ActorEvent]  // something gone wrong... if orderID present - failed to cancel...
    def openOrder(l: Ledger, retryCnt: Int): Try[Order]                          // how to open order
    def cancelOrder(orderID: String): Try[Orders]                                // how to cancel order
  }

  trait PositionCloser {
    def onProfitTake(l: Ledger): Behavior[ActorEvent]                            // desired outcome - profit taken, stoploss canceled
    def onStoploss(l: Ledger): Behavior[ActorEvent]                              // stoploss trigged, takeProfit order canceled
    def onExpired(l: Ledger): Behavior[ActorEvent]                               // expired (both take profit and stoploss canceled)
    def onUnprocessed(l: Ledger, takeProfitID: Option[String], stoplossID: Option[String]): Behavior[ActorEvent]  // something gone wrong... if takeProfitID/stoplossID present - failed to 1 or the other...
    def openTakeProfit(l: Ledger, retryCnt: Int): Try[Order]                     // how to open take profit order
    def openStoploss(takeProfitPrice: BigDecimal, retryCnt: Int): Try[Order]     // how to open stoploss order
    def cancelOrder(orderID: String): Try[Orders]                                // how to cancel order (either take profit or stoploss)
  }

  def openPosition(ledger: Ledger, maxRetries: Int=0, timeoutMs: Long, backoffMs: Long, positionOpener: PositionOpener): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>
      // trigger issue first expiry
      timers.startSingleTimer(OpenOrder, Duration(0, MILLISECONDS))

      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] {case (actorCtx, event) =>
          (ctx, event, actorCtx) match {
            case (_, _, _) => ???
          }
        }

        loop(OpenPositionCtx(ledger=ledger, null, 0))
      }

  def closePosition(ledger: Ledger, maxRetries: Int=0, timeoutMs: Long, backoffMs: Long, positionOpener: PositionCloser): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>
      // trigger issue first expiry
      timers.startSingleTimer(OpenTakeProfit, Duration(0, MILLISECONDS))

      def loop(ctx: ClosePositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] {case (actorCtx, event) =>
          (ctx, event, actorCtx) match {
            case (_, _, _) => ???
          }
        }

      loop(ClosePositionCtx(ledger=ledger, null, null, 0))
    }

//          def processRestReq(ledger: Ledger, retries: Int=0, timeoutSecs: Int, backoffSecs: Int, reqProcessor: RestReqProcessor): Behavior[ActorEvent] =
//    Behaviors.withTimers[ActorEvent] { timers =>
//      // trigger issue first expiry
//      timers.startSingleTimer(TriggerReq, Duration(0, SECONDS))
//
//      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
//        Behaviors.receivePartial[ActorEvent] { case (actorCtx, event) =>
//          (ctx, event, actorCtx) match {
//            case (OpenPositionCtx(ledger, orderID, 0), TriggerReq, _) => ???
//            case (OpenPositionCtx(ledger, orderID, retries), TriggerReq, _) => ???
//            case (OpenPositionCtx(ledger, orderID, 0), TriggerCancel, _) => ???
//            case (OpenPositionCtx(ledger, orderID, retries), TriggerCancel, _) => ???
//            case (OpenPositionCtx(ledger, orderID, retries), WsEvent(wsData), actorCtx) =>
//              val ctx2 = ctx.copy(ledger = ledger.record(wsData))
//              ctx2.ledger.byOrderID(ctx.orderID) match {
//                case Some(o) => o.lifecycle match {
//                  case OrderLifecycle.Canceled => actorCtx !
//                  case OrderLifecycle.Filled => reqProcessor.onFilled(ctx2)
//                  case OrderLifecycle.PostOnlyFailure => reqProcessor.onPostOnlyFailure(ctx2, ctx2.retries).getOrElse(reqProcessor.onCanceled(ctx2))
//                  case OrderLifecycle.Unknown => actorCtx.log.error(s"Have order in Unknown lifecycle state: $o\nkeeping course..."); loop(ctx2)
//                  case _ => loop(ctx2)
//                }
//                case None =>
//                  // not recorded yet...
//                  actorCtx.log.debug(s"Encountered WS order not yet recorded in ledger via REST... : ${ctx.orderID}")
//                  loop(ctx2)
//              }
//
//
//
//
//            case (OpenPositionCtx(ledger, orderID, 0), TriggerCancel, _) =>
//              reqProcessor.onFailure(ctx, TemporarilyUnavailableError("Failed on retries to cancel"))
//            case (OpenPositionCtx(ledger, _, _), TriggerCancel, _) =>
//              val canceledCtx = reqProcessor.processCancel(ctx) match {
//                case Success(os) =>
//                  val ledger2 = os.orders.foldLeft(ledger) { case (ledger, o) => ledger.record(o) }
//                  actorCtx.self ! TriggerReq
//                  ctx.copy(ledger = ledger2, orderID = null)
//                case Failure(e:TemporarilyUnavailableError) =>
//                  timers.startSingleTimer(TriggerCancel, Duration(backoffSecs, SECONDS))
//                  ctx.copy(retries = ctx.retries - 1)
//                case Failure(exc) =>
//                  actorCtx.log.error(s"Failed on cancellation of order: ${ctx.orderID}", exc)
//                  throw new Exception(s"Failed on cancellation of order: ${ctx.orderID}", exc)
//              }
//              loop(canceledCtx)
//            case (OpenPositionCtx(ledger, _, retries), TriggerReq, _) =>
//              val reissuedCtx =
//                reqProcessor.processRequest(ctx) match {
//                  case Success(o) =>
//                    val ledger2 = ledger.record(o)
//                    ctx.copy(ledger = ledger2, retries = retries - 1, orderID = o.orderID)
//                  case Failure(e:TemporarilyUnavailableError) =>
//                    timers.startSingleTimer(TriggerReq, Duration(backoffSecs, SECONDS))
//                    ctx.copy(retries = ctx.retries - 1)
//                  case Failure(exc) =>
//                    actorCtx.log.error(s"Failed on request of order: ${ctx.orderID}", exc)
//                    throw new Exception(s"Failed on request order: ${ctx.orderID}", exc)
//                }
//              loop(reissuedCtx)
//
//            case (OpenPositionCtx(ledger, orderID, retries), TriggerReq, _) =>
//          }
//        }
//
//
//          case (actorCtx, TriggerCancel) =>
//            if (ctx.retries >= 0) {
//              val canceledCtx = reqProcessor.processCancel(ctx) match {
//                case Success(os) =>
//                  val ledger2 = os.orders.foldLeft(ctx.ledger) { case (ledger, o) => ledger.record(o) }
//                  actorCtx.self ! TriggerReq
//                  ctx.copy(ledger = ledger2, orderID = null)
//                case Failure(e:TemporarilyUnavailableError) =>
//                  timers.startSingleTimer(TriggerCancel, Duration(backoffSecs, SECONDS))
//                  ctx.copy(retries = ctx.retries - 1)
//                case Failure(exc) =>
//                  actorCtx.log.error(s"Failed on cancellation of order: ${ctx.orderID}", exc)
//                  throw new Exception(s"Failed on cancellation of order: ${ctx.orderID}", exc)
//                }
//                loop(canceledCtx)
//            } else
//              reqProcessor.onFailure(ExceededRetriesError(ctx))
//          case (actorCtx, TriggerReq) =>
//            // reissue request if not exceeded retries
//            if (ctx.retries >= 0) {
//              val reissuedCtx =
//                reqProcessor.processRequest(ctx) match {
//                  case Success(o) =>
//                    val ledger2 = ledger.record(o)
//                    ctx.copy(ledger = ledger2, retries = retries - 1)
//                  case Failure(e:TemporarilyUnavailableError) =>
//                    timers.startSingleTimer(TriggerReq, Duration(backoffSecs, SECONDS))
//                    ctx.copy(retries = ctx.retries - 1)
//                  case Failure(exc) =>
//                    actorCtx.log.error(s"Failed on request of order: ${ctx.orderID}", exc)
//                    throw new Exception(s"Failed on request order: ${ctx.orderID}", exc)
//                }
//              loop(reissuedCtx)
//            } else
//              reqProcessor.onExceededRetries(ctx)
//          case (actorCtx, WsEvent(wsData)) =>
//            // accumulate WS events
//            val ctx2 = ctx.copy(ledger = ctx.ledger.record(wsData))
//            ctx2.ledger.byOrderID(ctx.orderID) match {
//              case Some(o) => o.lifecycle match {
//                case OrderLifecycle.Canceled => reqProcessor.onCanceled(ctx2)
//                case OrderLifecycle.Filled => reqProcessor.onFilled(ctx2)
//                case OrderLifecycle.PostOnlyFailure => reqProcessor.onPostOnlyFailure(ctx2, ctx2.retries).getOrElse(reqProcessor.onCanceled(ctx2))
//                case OrderLifecycle.Unknown => actorCtx.log.error(s"Have order in Unknown lifecycle state: $o\nkeeping course..."); loop(ctx2)
//                case _ => loop(ctx2)
//              }
//              case None =>
//                // not recorded yet...
//                actorCtx.log.debug(s"Encountered WS order not yet recorded in ledger via REST... : ${ctx.orderID}")
//                loop(ctx2)
//            }
//        }
//
//      loop(OpenPositionCtx(ledger, retries=retries))
//    }

  def apply(restGateway: IRestGateway,
            tradeQty: Int,
            openPositionTimeoutMs: Long, closePositionTimeoutMs: Long, backoffMs: Long = 500,
            maxReqRetries: Int, maxPostOnlyRetries: Int,
            takeProfitAmount: BigDecimal, stoplossAmount: BigDecimal, postOnlyPriceAdjAmount: BigDecimal): Behavior[ActorEvent] = {
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
        else if (ledger2.canOpenShort)
          openShort(ledger2)
        else
        idle(ctx.copy(ledger = ledger2))
    }

    def openLong(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, maxReqRetries, openPositionTimeoutMs, backoffMs, new PositionOpener {
        override def onFilled(l: Ledger): Behavior[ActorEvent] = closeLong(l)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, orderID: Option[String]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty: BigDecimal, l.bidPrice - retryCnt * postOnlyPriceAdjAmount, OrderSide.Buy)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(orderID), None)
      })

    def closeLong(ledger: Ledger): Behavior[ActorEvent] =
      closePosition(ledger, maxReqRetries, openPositionTimeoutMs, backoffMs, new PositionCloser {
        override def onProfitTake(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onStoploss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, takeProfitID: Option[String], stoplossID: Option[String]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openTakeProfit(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.bidPrice + retryCnt * postOnlyPriceAdjAmount, OrderSide.Sell)
        override def openStoploss(takeProfitPrice: BigDecimal, retryCnt: Int): Try[Order] = restGateway.placeStopMarketOrderSync(tradeQty, takeProfitPrice - takeProfitAmount, OrderSide.Sell)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(orderID), None)
      })

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, maxReqRetries, openPositionTimeoutMs, backoffMs, new PositionOpener {
        override def onFilled(l: Ledger): Behavior[ActorEvent] = closeLong(l)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, orderID: Option[String]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty: BigDecimal, l.askPrice + retryCnt * postOnlyPriceAdjAmount, OrderSide.Sell)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(orderID), None)
      })

    def closeShort(ledger: Ledger): Behavior[ActorEvent] =
      closePosition(ledger, maxReqRetries, openPositionTimeoutMs, backoffMs, new PositionCloser {
        override def onProfitTake(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onStoploss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, takeProfitID: Option[String], stoplossID: Option[String]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openTakeProfit(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.askPrice + retryCnt * postOnlyPriceAdjAmount, OrderSide.Buy)
        override def openStoploss(takeProfitPrice: BigDecimal, retryCnt: Int): Try[Order] = restGateway.placeStopMarketOrderSync(tradeQty, takeProfitPrice + takeProfitAmount, OrderSide.Buy)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(orderID), None)
      })

    idle(IdleCtx(ledger = new Ledger()))
  }
}
