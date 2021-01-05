package moon

import moon.Dir._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.Sentiment._
import moon.TradeLifecycle._

import scala.util.{Failure, Success}


/**
 * Radbot with improvements:
 * - Sentiment strategy, probably will start off as per radbot, ie. bulls vs bears, zero/plus ticks...
 * - New order issuance, if have buy and price dips to another level, can re-issue
 * - if sentiment is bull - bid and keep amending order
 *   - if buy fills - record the price (level) in ctx, issue sell with price + takeProfitMargin
 *     - if sell fills, remove open order and repeat all of the above
 *       OR
 *     - if price dips to a *lower level*, record the current
 *
 */
object Orchestrator {
  def asDsl(strategy: Strategy,
            tierCalc: TierCalc,
            takeProfitPerc: Double,
            dir: Dir.Value,
            consoleDriven: Boolean = false): (Ctx, ActorEvent, org.slf4j.Logger) => (Ctx, Option[SideEffect]) = {
    def bestOpenPrice(l: Ledger): Double = dir match {
      case LongDir => l.bidPrice
      case ShortDir => l.askPrice
    }

    def openPositionOrder(l: Ledger): Option[OpenInitOrder] = {
      val openPrices = l.myOrders.filter(o => Seq(New, PartiallyFilled).contains(o.ordStatus)).map(_.price)
      tierCalc.canOpenWithQty(bestOpenPrice(l), openPrices).flatMap { qty =>
          val strategyRes = strategy.strategize(l)
          (dir, strategyRes.sentiment) match {
            case (LongDir,  Bull) => Some(OpenInitOrder(Buy,  Limit, uuid, qty, Some(l.bidPrice)))
            case (ShortDir, Bear) => Some(OpenInitOrder(Sell, Limit, uuid, qty, Some(l.askPrice)))
            case _                => None
          }
      }
    }

    // close
    def closePositionOrders(openPrice: Double, qty: Double): OpenTakeProfitOrder = {
      val takeProfit = round(math.max(openPrice * takeProfitPerc, 10), 0)
      dir match {
        case LongDir  => OpenTakeProfitOrder(Sell, qty, uuid, openPrice+takeProfit)
        case ShortDir => OpenTakeProfitOrder(Buy,  qty, uuid, openPrice-takeProfit)
      }
    }

    def tick(ctx: Ctx, event: ActorEvent, log: org.slf4j.Logger): (Ctx, Option[SideEffect]) = (ctx, event) match {
      // Init state
      case (InitCtx(ledger), WsEvent(data)) =>
        if (log.isDebugEnabled) log.debug(s"Init: WsEvent: $data")
        val ledger2 = ledger.record(data)
        if (ledger2.isMinimallyFilled) {
          log.info(
            """
              |.-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-.
              ||                                             |
              ||   Ledger minimally filled, ready to go!     |
              ||                                             |
              |`-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-'""".stripMargin)
          openPositionOrder(ledger2) match {
            case Some(effect) =>
              log.info(s"Idle: starting afresh with $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get}...")
              (OpenPositionCtx(ledger = ledger2, clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew), Some(effect))
            case None =>
              (ctx.withLedger(ledger2), None)
          }
        } else
          (ctx.withLedger(ledger2), None)
      case (InitCtx(ledger), RestEvent(Success(data))) =>
        if (log.isDebugEnabled) log.debug(s"Init: RestEvent: $data")
        (ctx.withLedger(ledger.record(data)), None)
      case (InitCtx(_), RestEvent(Failure(exc))) =>
        if (log.isDebugEnabled) log.debug(s"Init: unexpected failure: $exc", exc)
        (ctx, None)

      // idle state
      case (IdleCtx(ledger), WsEvent(wsData)) =>
        // FIXME: repeated from InitCtx...
        if (log.isDebugEnabled) log.debug(s"Idle: WsEvent: $wsData")
        val ledger2 = ledger.record(wsData)
        openPositionOrder(ledger2) match {
          case Some(effect) =>
            log.info(s"Idle: starting afresh with $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get}...")
            (OpenPositionCtx(ledger = ledger2, clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew), Some(effect))
          case None =>
            (ctx.withLedger(ledger2), None)
        }
      case (IdleCtx(_), RestEvent(Success(data))) =>
        if (log.isDebugEnabled) log.debug(s"Idle: unexpected RestEvent: $data")
        (ctx.withLedger(ctx.ledger.record(data)), None)
      case (IdleCtx(_), RestEvent(Failure(exc))) =>
        if (log.isDebugEnabled) log.debug(s"Idle: unexpected Rest failure: $exc", exc)
        (ctx, None)

      // open position
      case (ctx2@OpenPositionCtx(clOrdID, ledger, targetPrice, lifecycle), event@(WsEvent(_) | RestEvent(Success(_)))) =>
        val (ledger2, clOrdIDMatch, isRestReply) = event match {
          case WsEvent(o: UpsertOrder) =>
            val ledger2 = ledger.record(o)
            (ledger2, o.containsClOrdIDs(clOrdID), false)
          case WsEvent(data) =>
            (ledger.record(data), false, false)
          case RestEvent(Success(data)) =>
            val ledger2 = ledger.record(data)
            val clOrdIDMatch = data match {
              case o: Order   => o.clOrdID.contains(clOrdID)
              case os: Orders => os.containsClOrdIDs(clOrdID)
              case          _ => false
            }
            (ledger2, clOrdIDMatch, true)
          case _ => ???  // should never happen
        }
        val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
        val ordStatusOpt = orderOpt.map(_.ordStatus)

        (ordStatusOpt, clOrdIDMatch) match {
          case (Some(Filled), true) =>
            log.info(s"Open $dir: filled orderID: ${orderOpt.get.fullOrdID} @ ${orderOpt.get.price}")
            val effect = closePositionOrders(orderOpt.get.price, orderOpt.get.qty)
            val ctx3 = ClosePositionCtx(openPrice = orderOpt.get.price, takeProfitClOrdID = effect.takeProfitClOrdID, ledger = ledger2)
            (ctx3, Some(effect))
          case (Some(PostOnlyFailure), true) =>
            openPositionOrder(ledger2) match {
              case Some(effect) =>
                log.info(s"Open $dir: PostOnlyFailure for orderID: ${orderOpt.get.fullOrdID}, re-issuing order: clOrdID: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get}")
                val ctx3 = ctx2.copy(clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew)
                (ctx3, Some(effect))
              case None =>
                log.info(s"Open $dir: PostOnlyFailure, won't re-issue the order...")
                (IdleCtx(ledger2), None)
            }
          case (Some(Canceled), true) =>
            log.info(s"Open $dir: cancelled orderID: ${orderOpt.get.fullOrdID}")
            (IdleCtx(ledger2), None)
          case (Some(Rejected), true) =>
            throw OrderRejectedError(s"Unexpected rejection of $dir opening clOrdID: ${orderOpt.get.clOrdID}")
          case _ if isRestReply || lifecycle == Awaiting =>
            val strategyRes = strategy.strategize(ledger2)
            if (log.isDebugEnabled) log.debug(s"Open: Sentiment is ${strategyRes.sentiment}")
            (dir, strategyRes.sentiment) match {
              case (LongDir, Bull) | (ShortDir, Bear) =>
                val bestPrice = bestOpenPrice(ledger2)
                if (targetPrice != bestPrice &&
                    (dir == LongDir && bestPrice > targetPrice) || (dir == ShortDir && bestPrice < targetPrice)) {
                  log.info(s"Open $dir: best price moved, will change: $targetPrice -> $bestPrice, $event")
                  val effect = AmendOrder(clOrdID, bestPrice)
                  val ctx3 = ctx2.copy(ledger = ledger2, targetPrice = bestPrice, lifecycle = IssuingAmend)
                  (ctx3, Some(effect))
                } else {
                  if (log.isDebugEnabled) log.debug(s"Open $dir: noop, sentiment matches dir @ orderID: ${orderOpt.get.fullOrdID}, event: $event")
                  val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = Awaiting)
                  (ctx3, None)
                }
              case _ =>
                log.info(s"Open $dir: sentiment changed to ${strategyRes.sentiment}, canceling ${clOrdID}")
                val effect = CancelOrder(clOrdID)
                val ctx3 = ctx2.copy(clOrdID = null, ledger = ledger2, lifecycle = IssuingCancel)
                (ctx3, Some(effect))
            }
          case _ =>
            (ctx2.withLedger(ledger2), None)
        }

      case (ctx2@OpenPositionCtx(clOrdID, ledger, targetPrice, lifecycle), RestEvent(Failure(e@(TemporarilyUnavailableError(_)|TemporarilyUnavailableOnPostError(_))))) =>
        lifecycle match {
          case IssuingNew =>
            openPositionOrder(ledger) match {
              case Some(effect) =>
                log.info(s"Open: re-issuing $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get}...")
                (ctx2.copy(clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew), Some(effect))
              case None =>
                (IdleCtx(ledger), None)
            }
          case IssuingAmend =>
            val bestPrice = bestOpenPrice(ledger)
            if (targetPrice != bestPrice) {
              val effect = AmendOrder(clOrdID, bestPrice)
              log.info(s"Open: re-amending $dir order: $clOrdID @ $bestPrice...")
              val ctx3 = ctx2.copy(lifecycle = IssuingAmend)
              (ctx3, Some(effect))
            } else {
              log.info(s"Open: no need to re-amend $dir order: $clOrdID, priced not moved...")
              (ctx2, None)
            }
          case IssuingCancel =>
            log.info(s"Open: re-cancelling $dir order: $clOrdID...")
            val effect = CancelOrder(clOrdID)
            (ctx2, Some(effect))
        }

      // Closing position - dealing with open of bulk orders, or cancel
      case (ctx2@ClosePositionCtx(_, clOrdID, ledger), event@(WsEvent(_) | RestEvent(Success(_)))) =>
        val (ledger2, orderOpt, ordStatusOpt, clOrdIDMatch) = event match {
          case WsEvent(o: UpsertOrder) =>
            val ledger2 = ledger.record(o)
            val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
            (ledger2, orderOpt, orderOpt.map(_.ordStatus), o.containsClOrdIDs(clOrdID))
          case WsEvent(data) =>
            (ledger.record(data), None, None, false)
          case RestEvent(Success(data)) =>
            val ledger2 = ledger.record(data)
            val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
            val clOrdIDMatch = data match {
              case o: Order   => o.clOrdID.contains(clOrdID)
              case os: Orders => os.containsClOrdIDs(clOrdID)
              case _          => false
            }
            (ledger2, orderOpt, orderOpt.map(_.ordStatus), clOrdIDMatch)
          case _ => ???  // should never happen
        }

        (orderOpt, ordStatusOpt, clOrdIDMatch) match {
          case (Some(tOrd), Some(Canceled), true) =>
            throw ExternalCancelError(s"Close $dir: unexpected (external?) cancels on takeProfit: ${tOrd.fullOrdID}, event: $event")
          case (Some(tOrd), Some(Rejected), true) =>
            throw OrderRejectedError(s"Close $dir: unexpected rejections on takeProfit: ${tOrd.fullOrdID}, event: $event")
          case (Some(tOrd), Some(Filled), true) =>
            log.info(pretty(s"Close $dir: ✔✔✔ filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} ✔✔✔", Bull, consoleDriven))
            (IdleCtx(ledger2), None)
          case (Some(ord), Some(PostOnlyFailure), true) =>
            // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
            throw IrrecoverableError(s"Close: PostOnlyFailure on closing position $ord... need to deal?\ntakeProfitOrder: $orderOpt", null)
          case _ =>
            (ctx2.withLedger(ledger2), None)
        }

      // Common states/events
      // potentially drop down to another tier once holding position
      case (ctx2@ClosePositionCtx(_, _, ledger), On30s(_)) =>
        openPositionOrder(ledger) match {
          case Some(effect) =>
            log.info(s"Close: issuing new tier $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get}...")
            (OpenPositionCtx(ledger = ledger, clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew), Some(effect))
          case None =>
            (ctx2.withLedger(ledger), None)
        }
      case (_, On30s(_)) =>
        (ctx, None)
      case (_, On1m(nowMs)) =>
        // if (log.isDebugEnabled) log.debug(s".....dbg ctx: $ctx")
        log.info(s".....dbg ctx: $ctx")
        if (ctx.ledger.isMinimallyFilled) {
          val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
          val effect = PublishMetrics(ledger2.ledgerMetrics.metrics, nowMs)
          (ctx.withLedger(ledger2), Some(effect))
        } else
          (ctx, None)
      case (_, RestEvent(Failure(e: IgnorableError))) =>
        log.warn(s"Ignoring error", e)
        (ctx, None)


      // catchall errors
      case (_, RestEvent(Failure(exc))) => throw IrrecoverableError(s"Failed with ctx:\n$ctx", exc)
    }

    tick
  }
}
