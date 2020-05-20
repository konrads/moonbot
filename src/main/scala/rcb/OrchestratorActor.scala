package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.{Duration, MILLISECONDS}
import scala.util.{Failure, Success, Try}

import rcb.OrderLifecycle._

object OrchestratorActor {
  trait PositionOpener {
    val maxMarkupRetries: Int
    val expiryMs: Long
    def onFilled(l: Ledger): Behavior[ActorEvent]                                // desired outcome - order filled
    def onExpired(l: Ledger): Behavior[ActorEvent]                               // expired (and order canceled)
    def onUnprocessed(l: Ledger, orderID: String, exc: Option[Throwable]=None): Behavior[ActorEvent]  // something gone wrong... if orderID present - failed to cancel...
    def openOrder(l: Ledger, markupCnt: Int): Try[Order]                          // how to open order
    def cancelOrder(orderID: String): Try[Orders]                                // how to cancel order
  }

  trait PositionCloser {
    val maxMarkupRetries: Int
    val expiryMs: Long
    def onProfitTake(l: Ledger): Behavior[ActorEvent]                            // desired outcome - profit taken, stoploss canceled
    def onStoploss(l: Ledger): Behavior[ActorEvent]                              // stoploss trigged, takeProfit order canceled
    def onExpired(l: Ledger): Behavior[ActorEvent]                               // expired (both take profit and stoploss canceled)
    def onUnprocessed(l: Ledger, takeProfitID: Option[String], stoplossID: Option[String]): Behavior[ActorEvent]  // something gone wrong... if takeProfitID/stoplossID present - failed to 1 or the other...
    def openTakeProfit(l: Ledger, retryCnt: Int): Try[Order]                     // how to open take profit order
    def openStoploss(takeProfitPrice: BigDecimal, retryCnt: Int): Try[Order]     // how to open stoploss order
    def cancelOrder(orderID: String): Try[Orders]                                // how to cancel order (either take profit or stoploss)
  }

  def openPosition(ledger: Ledger, positionOpener: PositionOpener): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] {
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
                  positionOpener.onFilled(ledger2)
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
                      // FIXME: deal with potential info from WS data that has arrived already
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
          // FIXME: deal with potential info from WS data that has arrived already
          loop(ctx)
        case Failure(exc) =>
          positionOpener.onUnprocessed(ledger, null, Some(exc))
      }
    }

  def closePosition(ledger: Ledger, positionCloser: PositionCloser): Behavior[ActorEvent] =
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
            tradeQty: Int, minTradeVol: BigDecimal,
            openPositionExpiryMs: Long, closePositionExpiryMs: Long, backoffMs: Long = 500,
            bullScoreThreshold: BigDecimal=0.25, bearScoreThreshold: BigDecimal= -0.25,
            reqRetries: Int, markupRetries: Int,
            takeProfitAmount: BigDecimal, stoplossAmount: BigDecimal, postOnlyPriceAdjAmount: BigDecimal): Behavior[ActorEvent] = {

    assert(bullScoreThreshold > bearScoreThreshold, s"bullScoreThreshold ($bullScoreThreshold) <= bearScoreThreshold ($bearScoreThreshold)")

    /**
     * Gather enough WS data to trade, then switch to idle
     */
    def init(ctx: InitCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] {
      case WsEvent(wsData) =>
        val ledger2 = ctx.ledger.record(wsData)
        if (ledger2.isMinimallyFilled)
          idle(IdleCtx(ledger2))
        else
          init(ctx.copy(ledger2))
    }

    /**
     * Waiting for market conditions to change to volumous bull or bear
     */
    def idle(ctx: IdleCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] {
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
        override def onFilled(l: Ledger): Behavior[ActorEvent] = closeLong(l)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, orderID: String, exc: Option[Throwable]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty: BigDecimal, l.bidPrice - retryCnt * postOnlyPriceAdjAmount, OrderSide.Buy)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      })

    def closeLong(ledger: Ledger): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val maxMarkupRetries = markupRetries
        override val expiryMs = closePositionExpiryMs
        override def onProfitTake(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onStoploss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, takeProfitID: Option[String], stoplossID: Option[String]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openTakeProfit(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.bidPrice + retryCnt * postOnlyPriceAdjAmount, OrderSide.Sell)
        override def openStoploss(takeProfitPrice: BigDecimal, retryCnt: Int): Try[Order] = restGateway.placeStopMarketOrderSync(tradeQty, takeProfitPrice - takeProfitAmount, OrderSide.Sell)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      })

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val maxMarkupRetries = markupRetries
        override val expiryMs = openPositionExpiryMs
        override def onFilled(l: Ledger): Behavior[ActorEvent] = closeShort(l)
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, orderID: String, exc: Option[Throwable]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openOrder(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty: BigDecimal, l.askPrice + retryCnt * postOnlyPriceAdjAmount, OrderSide.Sell)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      })

    def closeShort(ledger: Ledger): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val maxMarkupRetries = markupRetries
        override val expiryMs = closePositionExpiryMs
        override def onProfitTake(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onStoploss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExpired(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onUnprocessed(l: Ledger, takeProfitID: Option[String], stoplossID: Option[String]): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def openTakeProfit(l: Ledger, retryCnt: Int): Try[Order] = restGateway.placeLimitOrderSync(tradeQty, l.askPrice + retryCnt * postOnlyPriceAdjAmount, OrderSide.Buy)
        override def openStoploss(takeProfitPrice: BigDecimal, retryCnt: Int): Try[Order] = restGateway.placeStopMarketOrderSync(tradeQty, takeProfitPrice + takeProfitAmount, OrderSide.Buy)
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
      })

    init(InitCtx(ledger = Ledger()))
  }
}
