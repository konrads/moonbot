package moon

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import moon.Dir._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.Sentiment._
import moon.TradeLifecycle._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}


object OrchestratorActor {
  def asDsl(strategy: Strategy,
            tradeQty: Int,
            takeProfitMargin: Double, stoplossMargin: Double,
            openWithMarket: Boolean = false,
            useTrailingStoploss: Boolean = false): (Ctx, ActorEvent, org.slf4j.Logger) => (Ctx, Option[SideEffect]) = {
    // open (aka init)
    def bestOpenPrice(d: Dir.Value, l: Ledger): Double = d match {
      case LongDir => l.bidPrice
      case ShortDir => l.askPrice
    }
    def shouldKeepOpen(d: Dir.Value, l: Ledger): (Boolean, Ledger) = {
      val strategyRes = strategy.strategize(l)
      val (sentiment, l2) = (strategyRes.sentiment, strategyRes.ledger)
      val shouldKeepGoing = (d == LongDir && sentiment == Bull) || (d == ShortDir && sentiment == Bear)
      (shouldKeepGoing, l2)
    }
    def openPositionOrder(d: Dir.Value, l: Ledger): OpenInitOrder = (d, openWithMarket) match {
      case (LongDir, true)   => OpenInitOrder(Buy, Market, uuid, tradeQty)
      case (LongDir, false)  => OpenInitOrder(Buy, Limit, uuid, tradeQty, Some(l.bidPrice))
      case (ShortDir, true)  => OpenInitOrder(Sell, Market, uuid, tradeQty)
      case (ShortDir, false) => OpenInitOrder(Sell, Limit, uuid, tradeQty, Some(l.askPrice))
      case _ => ???  // to be rid of warnings: It would fail on the following inputs: (_, false), (_, true)
    }

    // close
    def closePositionOrders(d: Dir.Value, openPrice: Double, l: Ledger): OpenTakeProfitStoplossOrders = (d, useTrailingStoploss) match {
        case (LongDir, true)   => OpenTakeProfitStoplossOrders(Sell, tradeQty, uuid, openPrice+takeProfitMargin, uuid, stoplossPeg=Some(stoplossMargin))
        case (LongDir, false)  => OpenTakeProfitStoplossOrders(Sell, tradeQty, uuid, openPrice+takeProfitMargin, uuid, stoplossMargin=Some(openPrice-stoplossMargin))
        case (ShortDir, true)  => OpenTakeProfitStoplossOrders(Buy, tradeQty, uuid, openPrice-takeProfitMargin, uuid, stoplossPeg=Some(stoplossMargin))
        case (ShortDir, false) => OpenTakeProfitStoplossOrders(Buy, tradeQty, uuid, openPrice-takeProfitMargin, uuid, stoplossMargin=Some(openPrice+stoplossMargin))
        case _ => ???  // to be rid of warnings: It would fail on the following inputs: (_, false), (_, true)
      }

    def tick(ctx: Ctx, event: ActorEvent, log: org.slf4j.Logger): (Ctx, Option[SideEffect]) = (ctx, event) match {
      // Common states/events
      case (_, SendMetrics(nowMs)) =>
        if (ctx.ledger.isMinimallyFilled) {
          val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
          val effect = PublishMetrics(ledger2.ledgerMetrics.metrics, nowMs)
          (ctx.withLedger(ledger2), Some(effect))
        } else
          (ctx, None)
      case (_, RestEvent(Failure(_: IgnorableError))) =>
        (ctx, None)

      // Init state
      case (InitCtx(ledger), WsEvent(data)) =>
        if (log.isDebugEnabled) log.debug(s"init: WsEvent: $data")
        val ledger2 = ledger.record(data)
        if (ledger2.isMinimallyFilled) {
          log.info(
            """
              |.-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-.
              ||                                             |
              ||   Ledger minimally filled, ready to go!     |
              ||                                             |
              |`-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-'""".stripMargin)
          (ctx.withLedger(ledger2), None)
        } else
          (ctx.withLedger(ledger2), None)
      case (InitCtx(ledger), RestEvent(Success(data))) =>
        if (log.isDebugEnabled) log.debug(s"init: RestEvent: $data")
        (ctx.withLedger(ledger.record(data)), None)
      case (InitCtx(_), RestEvent(Failure(exc))) =>
        if (log.isDebugEnabled) log.debug(s"init: unexpected failure: $exc", exc)
        (ctx, None)

      // idle state
      case (IdleCtx(ledger), WsEvent(wsData)) =>
        if (log.isDebugEnabled) log.debug(s"idle: WsEvent: $wsData")
        val ledger2 = ledger.record(wsData)
        val strategyRes = strategy.strategize(ledger2)
        val (sentiment, ledger3) = (strategyRes.sentiment, strategyRes.ledger)
        if (log.isDebugEnabled) log.debug(s"idle: Sentiment is $sentiment")
        if (sentiment == Bull) {
          val effect = openPositionOrder(LongDir, ledger)
          (OpenPositionCtx(dir = LongDir, ledger = ledger3), Some(effect))
        } else if (sentiment == Bear) {
          val effect = openPositionOrder(ShortDir, ledger)
          (OpenPositionCtx(dir = ShortDir, ledger = ledger3), Some(effect))
        } else // Neutral or Dry run
          (ctx.withLedger(ledger3), None)
      case (IdleCtx(_), RestEvent(Success(data))) =>
        if (log.isDebugEnabled) log.debug(s"idle: unexpected RestEvent: $data")
        (ctx.withLedger(ctx.ledger.record(data)), None)
      case (IdleCtx(_), RestEvent(Failure(exc))) =>
        if (log.isDebugEnabled) log.debug(s"idle: unexpected Rest failure: $exc", exc)
        (ctx, None)

      // open position
      case (ctx2@OpenPositionCtx(dir, lifecycle, clOrdID, ledger), RestEvent(Success(data))) =>
        val ledger2 = ledger.record(data)
        val clOrdIDMatch = data match {
          case o: Order => o.clOrdID.contains(clOrdID)
          case os: Orders => os.containsClOrdIDs(clOrdID)
          case _ => false
        }
        if (clOrdIDMatch) {
          val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
          (lifecycle, order.ordStatus) match {
            case (_, Filled) =>
              log.info(s"Open $dir: $lifecycle: filled orderID: ${order.fullOrdID} @ ${order.price}")
              val effect = closePositionOrders(dir, order.price, ledger2)
              val ctx3 = ClosePositionCtx(dir = dir, openPrice = order.price, takeProfitClOrdID = effect.takeProfitClOrdID, stoplossClOrdID = effect.stoplossClOrdID, ledger = ledger2)
              (ctx3, Some(effect))
            case (IssuingNew | IssuingOpenAmend, PostOnlyFailure) =>
              val effect = openPositionOrder(dir, ledger2)
              log.warn(s"Open $dir: $lifecycle: PostOnlyFailure for orderID: ${order.fullOrdID}, re-issuing order: clOrdID: ${effect.clOrdID}...")
              val ctx3 = ctx2.copy(ledger = ledger2, clOrdID = effect.clOrdID, lifecycle = IssuingNew)
              (ctx3, Some(effect))
            case (_, PostOnlyFailure) =>
              throw IrrecoverableError(s"Unexpected PostOnlyFailure of $dir opening clOrdID: ${order.clOrdID}", null)
            case (IssuingOpenCancel, Canceled) =>
              if (log.isDebugEnabled) log.debug(s"Canceled (due to change of heart) order: ${order.fullOrdID}")
              val ctx3 = IdleCtx(ledger2)
              (ctx3, None)
            case (_, Canceled) =>
              throw ExternalCancelError(s"Unexpected cancellation of $dir opening clOrdID: ${order.clOrdID}")
            case (IssuingNew, _) => // issued new
              if (log.isDebugEnabled) log.debug(s"Open $dir: presuming to have created $clOrdID")
              val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = Waiting)
              (ctx3, None)
            case (IssuingOpenAmend, _) => // presumingly amended, not checking if changed price...
              if (log.isDebugEnabled) log.debug(s"Open $dir: presuming to have amended $clOrdID: new price: ${order.price}")
              val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = Waiting)
              (ctx3, None)
            case _ =>
              if (log.isDebugEnabled) log.debug(s"Open $dir: catchall: ${order.ordStatus} in lifecycle: $lifecycle, order: $order")
              val ctx3 = ctx2.copy(ledger = ledger2)
              (ctx3, None)
          }
        } else
          (ctx2.copy(ledger = ledger2), None)

      case (ctx2@OpenPositionCtx(dir, lifecycle, clOrdID, ledger), RestEvent(Failure(exc: RecoverableError))) =>
        lifecycle match {
          case IssuingNew =>
            val effect = openPositionOrder(dir, ledger)
            log.warn(s"Open $dir: re-issuing new order ${effect.clOrdID} due to recoverable error", exc)
            val ctx3 = ctx2.copy(clOrdID = effect.clOrdID)
            (ctx3, Some(effect))
          case IssuingOpenCancel =>
            val effect = CancelOrder(clOrdID)
            log.warn(s"Open $dir: re-issuing order cancel ${effect.clOrdID} due to recoverable error", exc)
            (ctx2, Some(effect))
          case IssuingOpenAmend =>
            val bestPrice = bestOpenPrice(dir, ledger)
            val effect = AmendOrder(clOrdID, bestPrice)
            log.warn(s"Open $dir: re-issuing order amend ${effect.clOrdID} -> $bestPrice due to recoverable error", exc)
            (ctx2, Some(effect))
          case _ => // ignore
            (ctx2, None)
        }
      case (ctx2@OpenPositionCtx(dir, lifecycle, clOrdID, ledger), WsEvent(data)) =>
        val clOrdIDMatch = data match {
          case o: Order => Some(o.clOrdID.contains(clOrdID))
          case os: Orders => Some(os.containsClOrdIDs(clOrdID))
          case _ => None
        }
        val ledger2 = ledger.record(data)
        val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
        val ordStatusOpt = orderOpt.map(_.ordStatus)

        (lifecycle, ordStatusOpt, clOrdIDMatch) match {
          case (_, _, Some(false)) => // not an order we're watching...
            (ctx2.copy(ledger = ledger2), None)
          case (_, Some(Filled), Some(true)) =>
            log.info(s"Open $dir: $lifecycle: filled orderID: ${orderOpt.get.fullOrdID} @ ${orderOpt.get.price}")
            val effect = closePositionOrders(dir, orderOpt.get.price, ledger2)
            val ctx3 = ClosePositionCtx(dir = dir, openPrice = orderOpt.get.price, takeProfitClOrdID = effect.takeProfitClOrdID, stoplossClOrdID = effect.stoplossClOrdID, ledger = ledger2)
            (ctx3, Some(effect))
          case (IssuingNew | IssuingOpenAmend, Some(PostOnlyFailure), Some(true)) =>
            val effect = openPositionOrder(dir, ledger2)
            log.warn(s"Open $dir: $lifecycle: PostOnlyFailure for orderID: ${orderOpt.get.fullOrdID}, re-issuing order: clOrdID: ${effect.clOrdID}...")
            val ctx3 = ctx2.copy(ledger = ledger2, clOrdID = effect.clOrdID, lifecycle = IssuingNew)
            (ctx3, Some(effect))
          case (_, Some(PostOnlyFailure), Some(true)) =>
            throw IrrecoverableError(s"Unexpected PostOnlyFailure of $dir opening clOrdID: ${orderOpt.get.clOrdID}", null)
          case (IssuingOpenCancel, Some(Canceled), Some(true)) =>
            if (log.isDebugEnabled) log.debug(s"Canceled (due to change of heart) order: ${orderOpt.get.fullOrdID}")
            (IdleCtx(ledger2), None)
          case (_, Some(Canceled), Some(true)) =>
            throw ExternalCancelError(s"Unexpected cancellation of $dir opening clOrdID: ${orderOpt.get.clOrdID}")
          case (IssuingNew | IssuingOpenAmend, _, Some(true)) | (Waiting, _, None) => // either orer created, amended, or orderBook, Trade, etc. Up for review!
            val (shouldKeepGoing, ledger3) = shouldKeepOpen(dir, ledger2)
            if (!shouldKeepGoing) {
              log.info(s"Open $dir: having a change of heart, cancelling ${orderOpt.get.fullOrdID}...")
              val effect = CancelOrder(clOrdID)
              val ctx3 = ctx2.copy(ledger = ledger3, lifecycle = IssuingOpenCancel)
              (ctx3, Some(effect))
            } else {
              // need to update best price?
              val bestPrice = bestOpenPrice(dir, ledger3)
              if (orderOpt.get.price != bestPrice) {
                log.info(s"Open $dir: best price moved, will change: ${orderOpt.get.price} -> $bestPrice")
                val effect = AmendOrder(clOrdID, bestPrice)
                val ctx3 = ctx2.copy(ledger = ledger3, lifecycle = IssuingOpenAmend)
                (ctx3, Some(effect))
              } else {
                if (log.isDebugEnabled) log.debug(s"Open $dir: noop @ orderID: ${orderOpt.get.fullOrdID}, lifecycle: $lifecycle, data: $data")
                val ctx3 = ctx2.copy(ledger = ledger3, lifecycle = Waiting)
                (ctx3, None)
              }
            }
          case _ =>
            // catchall...
            val ctx3 = ctx2.copy(ledger = ledger2)
            (ctx3, None)
        }

      // Closing position - dealing with open of bulk orders, or cancel
      case (ctx2@ClosePositionCtx(dir, lifecycle, _, takeProfitClOrdID, stoplossClOrdID, ledger), RestEvent(Success(os: Orders))) if os.containsClOrdIDs(takeProfitClOrdID, stoplossClOrdID) =>
        val ledger2 = ledger.record(os)
        val takeProfitOrder = ledger2.ledgerOrdersByClOrdID.get(takeProfitClOrdID)
        val stoplossOrder = ledger2.ledgerOrdersByClOrdID.get(stoplossClOrdID)
        (takeProfitOrder, stoplossOrder) match {
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Canceled =>
            throw ExternalCancelError(s"Close $dir: unexpected (external?) cancels on both takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Rejected || sOrd.ordStatus == Rejected =>
            throw OrderRejectedError(s"Close $dir: unexpected rejections on either takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && sOrd.ordStatus == Canceled =>
            log.info(s"Close $dir: ✔✔✔ filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} and cancelled stoploss: ${sOrd.fullOrdID} ✔✔✔")
            (IdleCtx(ledger2), None)
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Filled =>
            log.info(s"Close $dir: ✗✗✗ cancelled takeProfit: ${tOrd.fullOrdID} and filled stoploss: ${sOrd.fullOrdID} @ ${sOrd.price} ✗✗✗")
            (IdleCtx(ledger2), None)
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == PostOnlyFailure || sOrd.ordStatus == PostOnlyFailure =>
            // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
            throw IrrecoverableError(s"PostOnlyFailure on closing position... need to deal?\ntakeProfitOrder: $takeProfitOrder\nstoplossOrder = $stoplossOrder", null)
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && lifecycle != IssuingStoplossCancel =>
            log.info(s"Close $dir: filled takeProfit: ${tOrd.fullOrdID} straight away, issuing cancel on stoploss: ${sOrd.fullOrdID}")
            val effect = CancelOrder(sOrd.clOrdID)
            val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = IssuingStoplossCancel)
            (ctx3, Some(effect))
          case (Some(tOrd), Some(sOrd)) if sOrd.ordStatus == Filled && lifecycle != IssuingTakeProfitCancel =>
            log.info(s"Close $dir: filled stoploss: ${sOrd.fullOrdID} straight away, issuing cancel on takeProfit: ${tOrd.fullOrdID}")
            val effect = CancelOrder(tOrd.clOrdID)
            val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = IssuingTakeProfitCancel)
            (ctx3, Some(effect))
          case (Some(_), Some(_)) =>
            // some other combinations of states - keep going
            (ctx2.copy(ledger = ledger2), None)
          case _ =>
            if (log.isDebugEnabled) log.debug(s"Close $dir: unexpected RestEvent: $os\nexpected to match takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID")
            (ctx2.copy(ledger = ledger2), None)
        }
      case (ctx2@ClosePositionCtx(dir, _, _, _, _, ledger), RestEvent(Success(other))) =>
        if (log.isDebugEnabled) log.debug(s"Close $dir: unexpected RestEvent, recording: $other")
        (ctx2.copy(ledger = ledger.record(other)), None)

      case (ctx2@ClosePositionCtx(dir, lifecycle, openPrice, takeProfitClOrdID, stoplossClOrdID, ledger), RestEvent(Failure(exc: RecoverableError))) =>
        lifecycle match {
          case IssuingTakeProfitCancel =>
            val effect = CancelOrder(takeProfitClOrdID)
            log.warn(s"Close $dir: re-issuing cancel on takeProfitClOrdID: $takeProfitClOrdID, due to recoverable error", exc)
            (ctx, Some(effect))
          case IssuingStoplossCancel =>
            val effect = CancelOrder(stoplossClOrdID)
            log.warn(s"Close $dir: re-issuing cancel on stoplossClOrdID: $stoplossClOrdID, due to recoverable error", exc)
            (ctx, Some(effect))
          case IssuingNew =>
            val effect = closePositionOrders(dir, openPrice, ledger)
            val ctx3 = ctx2.copy(takeProfitClOrdID = effect.takeProfitClOrdID, stoplossClOrdID = effect.stoplossClOrdID)
            log.info(s"Close $dir: re-issued orders: takeProfitClOrdID: ${effect.takeProfitClOrdID}, stoplossClOrdID: ${effect.stoplossClOrdID}, due recoverable error", exc)
            (ctx3, Some(effect))
          case _ => // ignore
            (ctx, None)
        }

      case (ctx2@ClosePositionCtx(dir, lifecycle, _, takeProfitClOrdID, stoplossClOrdID, ledger), WsEvent(data)) =>
        val ledger2 = ledger.record(data)
        // FIXME: repetition from RestEvent!!!
        val takeProfitOrder = ledger2.ledgerOrdersByClOrdID.get(takeProfitClOrdID)
        val stoplossOrder = ledger2.ledgerOrdersByClOrdID.get(stoplossClOrdID)
        (takeProfitOrder, stoplossOrder) match {
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Canceled =>
            throw ExternalCancelError(s"Close $dir: unexpected (external?) cancels on both takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Rejected || sOrd.ordStatus == Rejected =>
            throw OrderRejectedError(s"Close $dir: rejections on either takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && sOrd.ordStatus == Canceled =>
            log.info(s"Close $dir: ✔✔✔ filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} and cancelled stoploss: ${sOrd.fullOrdID} ✔✔✔")
            (IdleCtx(ledger2), None)
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Filled =>
            log.info(s"Close $dir: ✗✗✗ cancelled takeProfit: ${tOrd.fullOrdID} and filled stoploss: ${sOrd.fullOrdID} @ ${sOrd.price} ✗✗✗")
            (IdleCtx(ledger2), None)
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == PostOnlyFailure || sOrd.ordStatus == PostOnlyFailure =>
            // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
            throw IrrecoverableError(s"PostOnlyFailure on closing position... need to deal?\ntakeProfitOrder: $takeProfitOrder\nstoplossOrder = $stoplossOrder", null)
          case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && lifecycle != IssuingStoplossCancel =>
            val effect = CancelOrder(sOrd.clOrdID)
            log.info(s"Close $dir: filled takeProfit: ${tOrd.fullOrdID}, issuing cancel on stoploss: ${sOrd.fullOrdID}")
            (ctx2.copy(ledger = ledger2, lifecycle = IssuingStoplossCancel), Some(effect))
          case (Some(tOrd), Some(sOrd)) if sOrd.ordStatus == Filled && lifecycle != IssuingTakeProfitCancel =>
            val effect = CancelOrder(tOrd.clOrdID)
            log.info(s"Close $dir: filled stoploss: ${sOrd.fullOrdID}, issuing cancel on takeProfit: ${tOrd.fullOrdID}")
            (ctx2.copy(ledger = ledger2, lifecycle = IssuingTakeProfitCancel), Some(effect))
          case (Some(tOrd), Some(sOrd)) =>
            // some other combinations of states - keep going
            if (log.isDebugEnabled) log.debug(s"Close $dir: new state of takeProfitOrder: $tOrd, stoplossOrder: $sOrd, in lifecycle: $lifecycle, data: $data")
            (ctx2.copy(ledger = ledger2), None)
          case other =>
            // if not our orders or non Order(s)
            if (log.isDebugEnabled) log.debug(s"Close $dir: catchall: $other in lifecycle: $lifecycle, data: $data")
            (ctx2.copy(ledger = ledger2), None)
        }

      // catchall errors
      case (_, RestEvent(Failure(exc))) => throw IrrecoverableError(s"Failed with ctx:\n$ctx", exc)
    }

    tick
  }

  def asLiveBehavior(restGateway: IRestGateway, metrics: Option[Metrics]=None, flushSessionOnRestart: Boolean=true, behaviorDsl: (Ctx, ActorEvent, org.slf4j.Logger) => (Ctx, Option[SideEffect]))(implicit execCtx: ExecutionContext): Behavior[ActorEvent] = {
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(SendMetrics(None), 1.minute)

      Behaviors.setup { actorCtx =>
        if (flushSessionOnRestart) {
          actorCtx.log.info("init: Bootstrapping via closePosition/orderCancels...")
          for {
            _ <- restGateway.closePositionAsync()
            _ <- restGateway.cancelAllOrdersAsync()
          } yield ()
        }

        def loop(ctx: Ctx): Behavior[ActorEvent] =
          Behaviors.receiveMessage { event =>
            val (ctx2, effect) = behaviorDsl(ctx, event, actorCtx.log)
            effect.foreach {
              case PublishMetrics(gauges, now) =>
                metrics.foreach(_.gauge(gauges, now))
              case CancelOrder(clOrdID) =>
                val fut = restGateway.cancelOrderAsync(clOrdIDs = Seq(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case AmendOrder(clOrdID, price) =>
                val fut = restGateway.amendOrderAsync(origClOrdID = Some(clOrdID), price = price)
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case OpenInitOrder(side, Limit, clOrdID, qty, price) =>
                val fut = restGateway.placeLimitOrderAsync(qty=qty, price=price.get, side=side, isReduceOnly=false, clOrdID=Some(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case OpenInitOrder(side, Market, clOrdID, qty, price) =>
                val fut = restGateway.placeMarketOrderAsync(qty=qty, side=side, clOrdID=Some(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case x:OpenInitOrder =>
                throw new Exception(s"Do not cater for non Limit/Market order: $x")
              case OpenTakeProfitStoplossOrders(side, qty, takeProfitClOrdID, takeProfitLimit, stoplossClOrdID, stoplossMarginOpt, stoplossPegOpt) =>
                val fut = restGateway.placeBulkOrdersAsync(OrderReqs(
                  Seq(OrderReq.asLimitOrder(side, qty, takeProfitLimit, true, clOrdID = Some(takeProfitClOrdID))) ++
                    stoplossMarginOpt.map(stoplossMargin => OrderReq.asStopOrder(side, qty, stoplossMargin, true, clOrdID = Some(stoplossClOrdID))).toSeq ++
                    stoplossPegOpt.map(stoplossPeg => OrderReq.asTrailingStopOrder(side, qty, stoplossPeg, true, clOrdID = Some(stoplossClOrdID))).toSeq
                ))
                fut onComplete (res => actorCtx.self ! RestEvent(res))

            }
            loop(ctx2)
          }
        loop(InitCtx(Ledger()))
      }
    }
  }

  def asDryBehavior(behaviorDsl: (Ctx, ActorEvent, org.slf4j.Logger) => (Ctx, Option[SideEffect]), metrics: Option[Metrics]=None): Behavior[ActorEvent] = {
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(SendMetrics(None), 1.minute)

      Behaviors.setup { actorCtx =>
        def loop(ctx: Ctx, exchangeCtx: ExchangeCtx): Behavior[ActorEvent] =
          Behaviors.receiveMessage { event =>
            val (ctx2, effect) = behaviorDsl(ctx, event, actorCtx.log)
            val (exchangeCtx2, newEvents) = effect.map { e =>
              paperExchangeEffectHandler(exchangeCtx, e, metrics, actorCtx.log, publishMetrics=false)
            }.getOrElse((exchangeCtx, Nil))

            newEvents.foreach(e => actorCtx.self ! e)

            loop(ctx2, exchangeCtx2)
          }

        loop(InitCtx(Ledger()), ExchangeCtx())
      }
    }
  }

  case class ExchangeCtx(orders: Seq[Order]=Vector.empty, bid: Double=0, ask: Double=0, timestampMs: Long=0, lastMetricsMs: Long=0, lastCtx: Ctx=null)

  def paperExchangeEffectHandler(exchangeCtx: ExchangeCtx, effect: SideEffect, metrics: Option[Metrics], log: org.slf4j.Logger, publishMetrics: Boolean): (ExchangeCtx, Seq[ActorEvent]) = ???
}

case class IrrecoverableError(msg: String, cause: Throwable) extends Exception(msg, cause)
case class ExternalCancelError(msg: String) extends Exception(msg)
case class OrderRejectedError(msg: String) extends Exception(msg)
