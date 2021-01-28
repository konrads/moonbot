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
            consoleDriven: Boolean = false): (Ctx, ActorEvent, org.slf4j.Logger, String) => (Ctx, Option[SideEffect]) = {
    def annotateRest(data: RestModel, relatedClOrdIDs: Map[String, String], tiers: Map[String, Int]): RestModel = data match {
      case o:Order => o.clOrdID.map(clOrdID => o.copy(relatedClOrdID = relatedClOrdIDs.get(clOrdID), tier = tiers.get(clOrdID))).getOrElse(o)
      case os:Orders => os.copy(orders = os.orders.map(o => annotateRest(o, relatedClOrdIDs, tiers).asInstanceOf[Order]))
      case os:HealthCheckOrders => os.copy(orders = os.orders.map(o => annotateRest(o, relatedClOrdIDs, tiers).asInstanceOf[Order]))
      case other => other
    }

    def annotateWs(data: WsModel, relatedClOrdIDs: Map[String, String], tiers: Map[String, Int]): WsModel = data match {
      case o:OrderData => o.clOrdID.map(clOrdID => o.copy(relatedClOrdID = relatedClOrdIDs.get(clOrdID), tier = tiers.get(clOrdID))).getOrElse(o)
      case uo:UpsertOrder => uo.copy(data = uo.data.map(o => annotateWs(o, relatedClOrdIDs, tiers).asInstanceOf[OrderData]))
      case other => other
    }

    def bestOpenPrice(l: Ledger): Double = dir match {
      case LongDir => l.bidPrice
      case ShortDir => l.askPrice
    }

    def closestClosePrice(l: Ledger): Double = dir match {
      case LongDir => l.askPrice
      case ShortDir => l.bidPrice
    }

    def openPositionOrder(l: Ledger, symbol: String, inOpen: Boolean, log: org.slf4j.Logger): Either[String, (OpenInitOrder, Tier)] = {
      val closeSide = if (dir == LongDir) Sell else Bull
      val closeOrders = l.myOrders.filter(o => Seq(New, PartiallyFilled).contains(o.ordStatus) && o.side == closeSide)
      val closeOrderOpenClOrdIDs = closeOrders.map(_.relatedClOrdID)
      val openPricesAndTiers = l.myOrders.filter(o => closeOrderOpenClOrdIDs.contains(o.clOrdID)).map(o => (o.price, o.tier.get))
      if (log.isDebugEnabled) log.debug(s"...openPricesAndTiers: ${openPricesAndTiers.mkString("\n")}")
      val bestPrice = bestOpenPrice(l)
      tierCalc.canOpenWithQty(bestPrice, openPricesAndTiers, inOpen).flatMap { tier =>
          val strategyRes = strategy.strategize(l)
          (dir, strategyRes.sentiment) match {
            case (LongDir,  Bull) => Right((OpenInitOrder(symbol, Buy,  Limit, uuid, tier.qty, Some(bestPrice)), tier))
            case (ShortDir, Bear) => Right((OpenInitOrder(symbol, Sell, Limit, uuid, tier.qty, Some(bestPrice)), tier))
            case _                => Left(s"Failed to open order with unmatched dir: $dir, sentiment: ${strategyRes.sentiment}")
          }
      }
    }

    // close
    def closePositionOrders(closePrice: Double, qty: Double, symbol: String): OpenTakeProfitOrder = {
      dir match {
        case LongDir  => OpenTakeProfitOrder(symbol, Sell, qty, uuid, closePrice)
        case ShortDir => OpenTakeProfitOrder(symbol, Buy,  qty, uuid, closePrice)
      }
    }

    def tick(ctx: Ctx, event: ActorEvent, log: org.slf4j.Logger, symbol: String): (Ctx, Option[SideEffect]) = (ctx, event) match {
      // Init state
      case (InitCtx(ledger, relatedClOrdIds, tiers), WsEvent(data)) =>
        if (log.isDebugEnabled) log.debug(s"Init: WsEvent: $data")
        val ledger2 = ledger.recordWs(annotateWs(data, relatedClOrdIds, tiers))
        if (ledger2.isMinimallyFilled) {
          log.info(
            s"""
              |.-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-.
              ||                                                     |
              ||    $symbol ledger minimally filled, ready to go!     |
              ||                                                     |
              |`-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-==-=-=--=-'""".stripMargin)
          openPositionOrder(ledger2, symbol, true, log) match {
            case Right((effect, tier)) =>
              log.info(s"$symbol Init: starting afresh with $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get} in tier: ${tier.displayFriendly}")
              val ctx2 = OpenPositionCtx(ledger = ledger2, clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew, relatedClOrdIds = relatedClOrdIds, tiers = tiers + (effect.clOrdID -> tier.tier))
              (ctx2, Some(effect))
            case Left(reason) =>
              if (log.isDebugEnabled) log.debug(s"$symbol Init: Skipping init order creation due to: $reason")
              (ctx.copyBasic(ledger2, relatedClOrdIds, tiers), None)
          }
        } else
          (ctx.copyBasic(ledger2, relatedClOrdIds, tiers), None)
      case (InitCtx(ledger, relatedClOrdIds, tiers), RestEvent(Success(data))) =>
        if (log.isDebugEnabled) log.debug(s"$symbol Init: RestEvent: $data")
        val ledger2 = ledger.recordRest(annotateRest(data, relatedClOrdIds, tiers))
        (ctx.copyBasic(ledger2, relatedClOrdIds, tiers), None)
      case (InitCtx(_, _, _), RestEvent(Failure(exc))) =>
        if (log.isDebugEnabled) log.debug(s"$symbol Init: unexpected failure: $exc", exc)
        (ctx, None)

      // idle state
      case (IdleCtx(ledger, relatedClOrdIds, tiers), WsEvent(wsData)) =>
        // FIXME: repeated from InitCtx...
        if (log.isDebugEnabled) log.debug(s"$symbol Idle: WsEvent: $wsData")
        val ledger2 = ledger.recordWs(annotateWs(wsData, relatedClOrdIds, tiers))
        openPositionOrder(ledger2, symbol, true, log) match {
          case Right((effect, tier)) =>
            log.info(s"$symbol Idle: starting afresh with $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get} in tier: ${tier.displayFriendly}")
            val ctx2 = OpenPositionCtx(ledger = ledger2, clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew, relatedClOrdIds = relatedClOrdIds, tiers = tiers + (effect.clOrdID -> tier.tier))
            (ctx2, Some(effect))
          case Left(reason) =>
            if (log.isDebugEnabled) log.debug(s"$symbol Idle: Skipping init order creation due to: $reason")
            (ctx.copyBasic(ledger2, relatedClOrdIds, tiers), None)
        }
      case (IdleCtx(ledger, relatedClOrdIds, tiers), RestEvent(Success(data))) =>
        if (log.isDebugEnabled) log.debug(s"$symbol Idle: unexpected RestEvent: $data")
        val ledger2 = ledger.recordRest(annotateRest(data, relatedClOrdIds, tiers))
        (ctx.copyBasic(ledger2, relatedClOrdIds, tiers), None)
      case (IdleCtx(_, _, _), RestEvent(Failure(exc))) =>
        if (log.isDebugEnabled) log.debug(s"$symbol Idle: unexpected Rest failure: $exc", exc)
        (ctx, None)

      // open position
      case (ctx2@OpenPositionCtx(clOrdID, ledger, targetPrice, lifecycle, relatedClOrdIds, tiers), event@(WsEvent(_) | RestEvent(Success(_)))) =>
        val (ledger2, clOrdIDMatch, isRestReply) = event match {
          case WsEvent(o: UpsertOrder) =>
            val ledger2 = ledger.recordWs(annotateWs(o, relatedClOrdIds, tiers))
            (ledger2, o.containsClOrdIDs(clOrdID), false)
          case WsEvent(data) =>
            (ledger.recordWs(annotateWs(data, relatedClOrdIds, tiers)), false, false)
          case RestEvent(Success(data)) =>
            val ledger2 = ledger.recordRest(annotateRest(data, relatedClOrdIds, tiers))
            val clOrdIDMatch = data match {
              case o: Order               => o.clOrdID.contains(clOrdID)
              case os: Orders             => os.containsClOrdIDs(clOrdID)
              case hos: HealthCheckOrders => hos.containsClOrdIDs(clOrdID)
              case _                      => false
            }
            (ledger2, clOrdIDMatch, true)
          case _ => ???  // should never happen
        }
        val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
        val ordStatusOpt = orderOpt.map(_.ordStatus)

        (ordStatusOpt, clOrdIDMatch) match {
          case (Some(Filled), true) =>
            val openPrice = round(orderOpt.get.price, 0)
            val takeProfitDelta = round(math.max(openPrice * takeProfitPerc, 10), 0)
            val closePrice = if (dir == LongDir) openPrice + takeProfitDelta else openPrice - takeProfitDelta
            val effect = closePositionOrders(closePrice, orderOpt.get.qty, symbol)
            val ctx3 = ClosePositionCtx(openClOrdID = clOrdID, openPrice = openPrice, openQty = effect.qty, takeProfitClOrdID = effect.takeProfitClOrdID, ledger = ledger2, lifecycle = IssuingNew,
                                        relatedClOrdIds = relatedClOrdIds + (effect.takeProfitClOrdID -> clOrdID), tiers = tiers)
            log.info(s"$symbol Open $dir: filled orderID: ${orderOpt.get.fullOrdID} @ ${orderOpt.get.price}, issuing close order: $effect")
            (ctx3, Some(effect))
          case (Some(PostOnlyFailure), true) =>
            openPositionOrder(ledger2, symbol, true, log) match {
              case Right((effect, tier)) =>
                log.info(s"$symbol Open $dir: PostOnlyFailure for orderID: ${orderOpt.get.fullOrdID}, re-issuing order: clOrdID: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get} in tier: ${tier.displayFriendly}")
                val ctx3 = ctx2.copy(clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew, tiers = tiers + (effect.clOrdID -> tier.tier))
                (ctx3, Some(effect))
              case Left(reason) =>
                log.info(s"$symbol Open $dir: PostOnlyFailure, won't re-issue the order due to: $reason")
                (IdleCtx(ledger2, relatedClOrdIds, tiers), None)
            }
          case (Some(Canceled), true) =>
            log.info(s"$symbol Open $dir: cancelled orderID: ${orderOpt.get.fullOrdID}")
            (IdleCtx(ledger2, relatedClOrdIds, tiers), None)
          case (Some(Rejected), true) =>
            throw OrderRejectedError(s"$symbol Unexpected rejection of $dir opening clOrdID: ${orderOpt.get.clOrdID}")
          case _ if isRestReply || lifecycle == Awaiting =>
            val strategyRes = strategy.strategize(ledger2)
            if (log.isDebugEnabled) log.debug(s"$symbol Open: Sentiment is ${strategyRes.sentiment}")
            (dir, strategyRes.sentiment) match {
              case (LongDir, Bull) | (ShortDir, Bear) =>
                val bestPrice = bestOpenPrice(ledger2)
                if (dir == LongDir && bestPrice > targetPrice || dir == ShortDir && bestPrice < targetPrice)
                  openPositionOrder(ledger2, symbol, true, log) match {
                    case Right((order, tier)) =>
                      log.info(s"$symbol Open $dir: best price moved, will change ${order.qty} @ price: $targetPrice → ${order.price.getOrElse("???")}, tier: ${tier.displayFriendly}")
                      val effect = AmendOrder(clOrdID, bestPrice, order.qty)
                      val ctx3 = ctx2.copy(ledger = ledger2, targetPrice = bestPrice, lifecycle = IssuingAmend)
                      (ctx3, Some(effect))
                    case Left(reason) =>
                      log.info(s"$symbol Open $dir: Cannot amend new order, moved into a tier with order, reason: $reason, canceling $clOrdID")
                      val effect = CancelOrder(clOrdID)
                      val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = IssuingCancel)
                      (ctx3, Some(effect))
                  }
                else {
                  if (log.isDebugEnabled) log.debug(s"$symbol Open $dir: noop, sentiment matches dir @ orderID: ${orderOpt.map(_.fullOrdID)}, event: $event")
                  val ctx3 = ctx2.copy(ledger = ledger2, lifecycle = Awaiting)
                  (ctx3, None)
                }
              case _ =>
                log.info(s"$symbol Open $dir: sentiment changed to ${strategyRes.sentiment}, canceling $clOrdID")
                val effect = CancelOrder(clOrdID)
                val ctx3 = ctx2.copy(clOrdID = null, ledger = ledger2, lifecycle = IssuingCancel)
                (ctx3, Some(effect))
            }
          case _ =>
            (ctx2.copyBasic(ledger2, relatedClOrdIds, tiers), None)
        }

      case (ctx2@OpenPositionCtx(clOrdID, ledger, targetPrice, lifecycle, relatedClOrdIds, tiers), RestEvent(Failure(e:RecoverableError))) =>
        log.warn(s"$symbol Encountered error", e)
        lifecycle match {
          case IssuingNew =>
            openPositionOrder(ledger, symbol, true, log) match {
              case Right((effect, tier)) =>
                log.info(s"$symbol Open: re-issuing $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get} due to error in tier: ${tier.displayFriendly}", e)
                val ctx3 = ctx2.copy(clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew, tiers = tiers + (effect.clOrdID -> tier.tier))
                (ctx3, Some(effect))
              case Left(reason) =>
                if (log.isDebugEnabled) log.debug(s"$symbol Open: Skipping init order creation due to: $reason, here due to error", e)
                (IdleCtx(ledger, relatedClOrdIds, tiers), None)
            }
          case IssuingAmend =>
            openPositionOrder(ledger, symbol, true, log) match {
              case Right((order, tier)) =>
                log.info(s"$symbol Open: re-amending $dir order: $clOrdID of ${order.qty} @ ${order.price} due to error, tier: ${tier.displayFriendly}", e)
                val effect = AmendOrder(clOrdID, order.price.get, order.qty)
                val ctx3 = ctx2.copy(targetPrice = order.price.get, lifecycle = IssuingAmend)
                (ctx3, Some(effect))
              case Left(reason) =>
                if (log.isDebugEnabled) log.debug(s"$symbol Open: skipping re-amending $dir order creation due to: $reason, here due to error", e)
                (IdleCtx(ledger, relatedClOrdIds, tiers), None)
            }
          case IssuingCancel =>
            log.info(s"$symbol Open: re-cancelling $dir order: $clOrdID due to error", e)
            val effect = CancelOrder(clOrdID)
            (ctx2, Some(effect))
        }

      // Closing position - dealing with open of bulk orders, or cancel
      case (ctx2@ClosePositionCtx(openClOrdID, _, openQty, clOrdID, ledger, lifecycle, relatedClOrdIds, tiers), event@(WsEvent(_) | RestEvent(Success(_)))) =>
        val (ledger2, orderOpt, ordStatusOpt, clOrdIDMatch, isRestReply) = event match {
          case WsEvent(o: UpsertOrder) =>
            val ledger2 = ledger.recordWs(annotateWs(o, relatedClOrdIds, tiers))
            val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
            (ledger2, orderOpt, orderOpt.map(_.ordStatus), o.containsClOrdIDs(clOrdID), false)
          case WsEvent(data) =>
            val ledger2 = ledger.recordWs(annotateWs(data, relatedClOrdIds, tiers))
            (ledger2, None, None, false, false)
          case RestEvent(Success(data)) =>
            val ledger2 = ledger.recordRest(annotateRest(data, relatedClOrdIds, tiers))
            val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
            val clOrdIDMatch = data match {
              case o: Order               => o.clOrdID.contains(clOrdID)
              case os: Orders             => os.containsClOrdIDs(clOrdID)
              case hos: HealthCheckOrders => hos.containsClOrdIDs(clOrdID)
              case _                      => false
            }
            (ledger2, orderOpt, orderOpt.map(_.ordStatus), clOrdIDMatch, true)
          case _ => ???  // should never happen
        }

        (orderOpt, ordStatusOpt, clOrdIDMatch, isRestReply) match {
          case (Some(tOrd), Some(Canceled), true, true) =>
            throw ExternalCancelError(s"$symbol Close $dir: unexpected (external?) cancels on takeProfitDelta: ${tOrd.fullOrdID}, event: $event")
          case (Some(tOrd), Some(Rejected), true, true) =>
            throw OrderRejectedError(s"$symbol Close $dir: unexpected rejections on takeProfitDelta: ${tOrd.fullOrdID}, event: $event")
          case (Some(tOrd), Some(Filled), true, true) =>
            log.info(pretty(s"$symbol Close $dir: ✔✔✔ filled takeProfitDelta: ${tOrd.fullOrdID} @ ${tOrd.price} ✔✔✔", Bull, consoleDriven))
            (IdleCtx(ledger2, relatedClOrdIds, tiers), None)
          case (Some(ord), Some(PostOnlyFailure), true, true) =>
            // re-issue
            val effect = closePositionOrders(closestClosePrice(ledger2), openQty, symbol)
            log.warn(s"$symbol Close $dir: re-issuing order due to PostOnlyFailure, clOrdID: ${effect.takeProfitClOrdID} @ ${effect.takeProfitLimit}, qty: ${effect.qty}")
            val ctx3 = ctx2.copy(ledger=ledger2, takeProfitClOrdID = effect.takeProfitClOrdID, lifecycle = IssuingAmend,
                                 relatedClOrdIds = relatedClOrdIds + (effect.takeProfitClOrdID -> openClOrdID))
            (ctx3, Some(effect))
          case (Some(ord), _, true, _) =>
            if (lifecycle != Awaiting) log.info(s"$symbol Close $dir: got $event update to order ${ord.fullOrdID}, setting lifecycle $lifecycle → $Awaiting")
            (ctx2.copy(ledger = ledger2, lifecycle = Awaiting), None)
          case _ =>
            (ctx2.copyBasic(ledger2, relatedClOrdIds, tiers), None)
        }

      // Common states/events
      // potentially drop down to another tier once holding position
      case (ctx2@ClosePositionCtx(_, _, _, _, ledger, lifecycle, relatedClOrdIds, tiers), On30s(_)) =>
        openPositionOrder(ledger, symbol, false, log) match {
          case Right((effect, tier)) =>
            if (lifecycle == Awaiting) {
              log.info(s"$symbol Close: issuing new tier $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get} in tier: ${tier.displayFriendly}")
              val ctx2 = OpenPositionCtx(ledger = ledger, clOrdID = effect.clOrdID, targetPrice = effect.price.get, lifecycle = IssuingNew, relatedClOrdIds = relatedClOrdIds, tiers = tiers + (effect.clOrdID -> tier.tier))
              (ctx2, Some(effect))
            } else {
              if (log.isDebugEnabled) log.debug(s"$symbol Close $dir: didn't issue new tier $dir order: ${effect.clOrdID}, ${effect.qty} @ ${effect.price.get} in tier: ${tier.displayFriendly}, due to lifecycle: $lifecycle")
              (ctx2, None)
            }
          case Left(reason) =>
            if (log.isDebugEnabled) log.debug(s"$symbol Close $dir: Skipping init order creation due to: $reason")
            (ctx2.copyBasic(ledger, relatedClOrdIds, tiers), None)
        }
      case (_, On5m(_)) =>
        if (log.isDebugEnabled) log.debug(s"5min summary...\n- ${ctx.ledger.summary}\n- relatedClOrdIds: ${ctx.relatedClOrdIds.size}\n- tiers: ${ctx.tiers.size}")
        (ctx, Some(HealthCheck))
      case (_, On30s(_)) =>
        (ctx, None)
      case (_, On1m(nowMs)) =>
        if (ctx.ledger.isMinimallyFilled) {
          if (log.isDebugEnabled) log.debug(s"...$symbol dbg ctx: ${ctx.getClass}, ask/bid prices: ${ctx.ledger.askPrice}/${ctx.ledger.bidPrice}, myOrders:\n${ctx.ledger.myOrders.map(_.toString).mkString("\n")}")
          val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
          val effect = PublishMetrics(ledger2.ledgerMetrics.metrics, nowMs)
          (ctx.copyBasic(ledger2, ctx.relatedClOrdIds, ctx.tiers), Some(effect))
        } else
          (ctx, None)
      case (_, RestEvent(Failure(e: IgnorableError))) =>
        log.warn(s"$symbol Ignoring error", e)
        (ctx, None)


      // catchall errors
      case (_, RestEvent(Failure(exc))) => throw IrrecoverableError(s"$symbol Failed with ctx:\n$ctx", exc)
    }

    tick
  }
}
