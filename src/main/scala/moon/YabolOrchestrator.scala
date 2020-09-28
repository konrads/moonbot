package moon

import moon.Dir._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.Sentiment._

import scala.util.{Failure, Success}


object YabolOrchestrator {
  def asDsl(strategy: Strategy, tradeQty: Int, takerFee: Double = .00075, consoleDriven: Boolean = false): (YabolCtx, ActorEvent, org.slf4j.Logger) => (YabolCtx, Option[SideEffect]) = {

    def tick(ctx: YabolCtx, event: ActorEvent, log: org.slf4j.Logger): (YabolCtx, Option[SideEffect]) = (ctx, event) match {
      // Common states/events
      case (_, On1m(nowMs)) =>
        if (ctx.ledger.isMinimallyFilled) {
          val ledger2 = ctx.ledger.withMetrics(strategy = strategy, takerFee = takerFee)
          val effect = PublishMetrics(ledger2.ledgerMetrics.metrics, nowMs)
          (ctx.withLedger(ledger2), Some(effect))
        } else
          (ctx, None)
      case (_, RestEvent(Failure(_: IgnorableError))) =>
        (ctx, None)

      // idle state
      case (YabolIdleCtx(ledger), WsEvent(data)) =>
        if (log.isDebugEnabled) log.debug(s"Idle: WsEvent: $data")
        val ledger2 = ledger.record(data)
        (ctx.withLedger(ledger2), None)

      case (YabolIdleCtx(ledger), On1h(_ts)) =>
        val strategyRes = strategy.strategize(ledger, true)
        if (log.isDebugEnabled) log.debug(s"Idle: Sentiment is ${strategyRes.sentiment}")
        if (strategyRes.sentiment == Bull) {
          val effect = OpenInitOrder(Buy, Market, uuid, tradeQty)
          log.info(s"Idle: starting afresh with $LongDir order: ${effect.clOrdID} @~ ${ledger.tradeRollups.latestPrice}...")
          (YabolOpenPositionCtx(dir = LongDir, ledger = ledger, qty = tradeQty, clOrdID = effect.clOrdID), Some(effect))
        } else if (strategyRes.sentiment == Bear) {
          val effect = OpenInitOrder(Sell, Market, uuid, tradeQty)
          log.info(s"Idle: starting afresh with $ShortDir order: ${effect.clOrdID} @~ ${ledger.tradeRollups.latestPrice}...")
          (YabolOpenPositionCtx(dir = ShortDir, ledger = ledger, qty = tradeQty, clOrdID = effect.clOrdID), Some(effect))
        } else
          (ctx, None)

      // in position
      case (ctx2@YabolOpenPositionCtx(dir, clOrdID, qty, ledger), e@(RestEvent(Success(_)) | WsEvent(_))) =>
        val (ledger2, clOrdIDMatch) = e match {
          case RestEvent(Success(o:Order))   => (ledger.record(o), o.clOrdID.contains(clOrdID))
          case RestEvent(Success(os:Orders)) => (ledger.record(os), os.containsClOrdIDs(clOrdID))
          case RestEvent(Success(x))         => (ledger.record(x), false)
          case WsEvent(o:UpsertOrder)        => (ledger.record(o), o.containsClOrdIDs(clOrdID))
          case WsEvent(x)                    => (ledger.record(x), false)
          case _                             => ???
        }
        if (clOrdIDMatch) {
          val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
          order.ordStatus match {
            case Filled =>
              log.info(s"Open $dir: filled orderID: ${order.fullOrdID} @ ${order.price} :: ${order.timestamp}")
              (YabolWaitingCtx(dir = dir, qty = qty, openPrice = order.price, ledger = ledger2), None)
//              // wait for strategy to signal exit
//              val strategyRes = strategy.strategize(ledger2)
//              if (dir == LongDir && strategyRes.sentiment != Bull) {
//                log.info(s"...Closing $dir: $qty @~ ${ledger2.tradeRollups.latestPrice} :: ${order.timestamp}")
//                val effect = OpenInitOrder(Sell, Market, uuid, qty)
//                val ctx3 = YabolClosePositionCtx(dir, qty, order.price, effect.clOrdID, ledger2)
//                (ctx3, Some(effect))
//              } else if (dir == ShortDir && strategyRes.sentiment != Bear) {
//                log.info(s"...Closing $dir: $qty @~ ${ledger2.tradeRollups.latestPrice} :: ${order.timestamp}")
//                val effect = OpenInitOrder(Buy, Market, uuid, qty)
//                val ctx3 = YabolClosePositionCtx(dir, qty, order.price, effect.clOrdID, ledger2)
//                (ctx3, Some(effect))
//              } else
//                (YabolWaitingCtx(dir = dir, qty = qty, openPrice = order.price, ledger = ledger2), None)
            case _ =>
              if (log.isDebugEnabled) log.debug(s"Open $dir: catchall: ${order.ordStatus}, order: $order")
              (ctx2.copy(ledger = ledger2), None)
          }
        } else
          (ctx2.copy(ledger = ledger2), None)

      // waiting for change in strategy
      case (ctx2@YabolWaitingCtx(_, _, _, ledger), e@(RestEvent(Success(_)) | WsEvent(_))) =>
        val ledger2 = e match {
          case RestEvent(Success(x)) => ledger.record(x)
          case WsEvent(x)            => ledger.record(x)
          case _                     => ???
        }
        (ctx2.copy(ledger = ledger2), None)

      case (ctx2@YabolWaitingCtx(dir, qty, openPrice, ledger), On1h(_ts)) =>
        val strategyRes = strategy.strategize(ledger, true)
        if (dir == LongDir && strategyRes.sentiment != Bull) {
          log.info(s"Closing $dir: $qty @~ ${ledger.tradeRollups.latestPrice}")
          val effect = OpenInitOrder(Sell, Market, uuid, qty)
          val ctx3 = YabolClosePositionCtx(dir, qty, openPrice, effect.clOrdID, ledger)
          (ctx3, Some(effect))
        } else if (dir == ShortDir && strategyRes.sentiment != Bear) {
          log.info(s"Closing $dir: $qty @~ ${ledger.tradeRollups.latestPrice}")
          val effect = OpenInitOrder(Buy, Market, uuid, qty)
          val ctx3 = YabolClosePositionCtx(dir, qty, openPrice, effect.clOrdID, ledger)
          (ctx3, Some(effect))
        } else {
          (ctx2.copy(ledger = ledger), None)
        }

      case (ctx2@YabolClosePositionCtx(dir, qty, openPrice, clOrdID, ledger), e@(RestEvent(Success(_)) | WsEvent(_))) =>
        val (ledger2, clOrdIDMatch) = e match {
          case RestEvent(Success(o:Order))   => (ledger.record(o), o.clOrdID.contains(clOrdID))
          case RestEvent(Success(os:Orders)) => (ledger.record(os), os.containsClOrdIDs(clOrdID))
          case RestEvent(Success(x))         => (ledger.record(x), false)
          case WsEvent(o:UpsertOrder)        => (ledger.record(o), o.containsClOrdIDs(clOrdID))
          case WsEvent(x)                    => (ledger.record(x), false)
          case _                             => ???
        }
        if (clOrdIDMatch) {
          val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
          order.ordStatus match {
            case Filled =>
              // FIXME: consider flipping if sentiment reverses, not just going to idle
              val priceDelta = if (dir == LongDir) order.price - openPrice else openPrice - order.price
              if ((dir == LongDir && openPrice < order.price) || (dir == ShortDir && openPrice > order.price))
                log.info(pretty(s"Close $dir: ✔✔✔ filled with price delta: $openPrice => ${order.price} ... $priceDelta ✔✔✔ :: ${order.timestamp}", Bull, consoleDriven))
              else
                log.info(pretty(s"Close $dir: ✗✗✗ filled with price delta: $openPrice => ${order.price} ... $priceDelta ✗✗✗ :: ${order.timestamp}", Bear, consoleDriven))
              (YabolIdleCtx(ledger2), None)
            case _ =>
              if (log.isDebugEnabled) log.debug(s"Open $dir: catchall: ${order.ordStatus}, order: $order")
              (ctx2.copy(ledger = ledger2), None)
          }
        } else
          (ctx2.copy(ledger = ledger2), None)

      // catchalls
      case (ctx, On1h(_)) =>
        (ctx, None)
      case (ctx, WsEvent(data)) =>
        (ctx.withLedger(ctx.ledger.record(data)), None)
      case (ctx, RestEvent(Success(data))) =>
        (ctx.withLedger(ctx.ledger.record(data)), None)
       case (ctx, RestEvent(Failure(exc: RecoverableError))) =>
        log.warn(s"Ignoring recoverable error", exc)
        (ctx, None)
      case (_, RestEvent(Failure(exc))) =>
        throw IrrecoverableError(s"Failed with ctx:\n$ctx", exc)
    }

    tick
  }
}
