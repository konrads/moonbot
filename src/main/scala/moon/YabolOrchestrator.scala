package moon

import moon.Dir._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.Sentiment._

import scala.util.{Failure, Success}


object YabolOrchestrator {
  def asDsl(strategy: Strategy, tradeQty: Int): (YabolCtx, ActorEvent, org.slf4j.Logger) => (YabolCtx, Option[SideEffect]) = {

    def tick(ctx: YabolCtx, event: ActorEvent, log: org.slf4j.Logger): (YabolCtx, Option[SideEffect]) = (ctx, event) match {
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

      // idle state
      case (YabolIdleCtx(ledger), WsEvent(data)) =>
        if (log.isDebugEnabled) log.debug(s"idle: WsEvent: $data")
        val ledger2 = ledger.record(data)
        val strategyRes = strategy.strategize(ledger2)
        if (log.isDebugEnabled) log.debug(s"idle: Sentiment is ${strategyRes.sentiment}")
        if (strategyRes.sentiment == Bull) {
          val effect = OpenInitOrder(Buy, Market, uuid, tradeQty)
          log.info(s"idle: starting afresh with $LongDir order: ${effect.clOrdID} @~ ${ledger.tradeRollups.latestPrice}...")
          (YabolOpenPositionCtx(dir = LongDir, ledger = ledger2, qty = tradeQty, clOrdID = effect.clOrdID), Some(effect))
        } else if (strategyRes.sentiment == Bear) {
          val effect = OpenInitOrder(Sell, Market, uuid, tradeQty)
          log.info(s"idle: starting afresh with $ShortDir order: ${effect.clOrdID} @~ ${ledger.tradeRollups.latestPrice}...")
          (YabolOpenPositionCtx(dir = ShortDir, ledger = ledger2, qty = tradeQty, clOrdID = effect.clOrdID), Some(effect))
        } else
          (ctx.withLedger(ledger2), None)

      // in position
      case (ctx2@YabolOpenPositionCtx(dir, clOrdID, qty, ledger), e@(RestEvent(Success(_)) | WsEvent(_))) =>
        val (ledger2, clOrdIDMatch) = e match {
          case RestEvent(Success(o:Order))   => (ledger.record(o), o.clOrdID.contains(clOrdID))
          case RestEvent(Success(os:Orders)) => (ledger.record(os), os.containsClOrdIDs(clOrdID))
          case RestEvent(Success(x))         => (ledger.record(x), false)
          case WsEvent(o:UpsertOrder)        => (ledger.record(o), o.containsClOrdIDs(clOrdID))
          case WsEvent(x)                    => (ledger.record(x), false)
        }
        if (clOrdIDMatch) {
          val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
          order.ordStatus match {
            case Filled =>
              log.info(s"Open $dir: filled orderID: ${order.fullOrdID} @ ${order.price}")
              // wait for strategy to signal exit
              val strategyRes = strategy.strategize(ledger2)
              if (dir == LongDir && strategyRes.shouldExitLong) {
                log.info(s"Open $dir: $qty @ ${ledger2.tradeRollups.latestPrice}")
                val effect = OpenInitOrder(Sell, Market, uuid, qty)
                val ctx3 = YabolClosePositionCtx(dir, effect.clOrdID, ledger2)
                (ctx3, Some(effect))
              } else if (dir == ShortDir && strategyRes.shouldExitShort) {
                log.info(s"Open $dir: $qty @ ${ledger2.tradeRollups.latestPrice}")
                val effect = OpenInitOrder(Buy, Market, uuid, qty)
                val ctx3 = YabolClosePositionCtx(dir, effect.clOrdID, ledger2)
                (ctx3, Some(effect))
              } else {
                (YabolWaitingCtx(dir = dir, qty = qty, ledger = ledger2), None)
              }
            case _ =>
              if (log.isDebugEnabled) log.debug(s"Open $dir: catchall: ${order.ordStatus}, order: $order")
              (ctx2.copy(ledger = ledger2), None)
          }
        } else
          (ctx2.copy(ledger = ledger2), None)

      // waiting for change in strategy
      case (ctx2@YabolWaitingCtx(dir, qty, ledger), e@(RestEvent(Success(_)) | WsEvent(_))) =>
        val ledger2 = e match {
          case RestEvent(Success(x)) => ledger.record(x)
          case WsEvent(x)            => ledger.record(x)
        }
        val strategyRes = strategy.strategize(ledger2)
        if (dir == LongDir && strategyRes.shouldExitLong) {
          log.info(s"Close $dir: $qty @ ${ledger2.tradeRollups.latestPrice}")
          val effect = OpenInitOrder(Sell, Market, uuid, qty)
          val ctx3 = YabolClosePositionCtx(dir, effect.clOrdID, ledger2)
          (ctx3, Some(effect))
        } else if (dir == ShortDir && strategyRes.shouldExitShort) {
          log.info(s"Close $dir: $qty @ ${ledger2.tradeRollups.latestPrice}")
          val effect = OpenInitOrder(Buy, Market, uuid, qty)
          val ctx3 = YabolClosePositionCtx(dir, effect.clOrdID, ledger2)
          (ctx3, Some(effect))
        } else {
          (ctx2.copy(ledger = ledger2), None)
        }

      case (ctx2@YabolClosePositionCtx(dir, clOrdID, ledger), e@(RestEvent(Success(_)) | WsEvent(_))) =>
        val (ledger2, clOrdIDMatch) = e match {
          case RestEvent(Success(o:Order))   => (ledger.record(o), o.clOrdID.contains(clOrdID))
          case RestEvent(Success(os:Orders)) => (ledger.record(os), os.containsClOrdIDs(clOrdID))
          case RestEvent(Success(x))         => (ledger.record(x), false)
          case WsEvent(o:UpsertOrder)        => (ledger.record(o), o.containsClOrdIDs(clOrdID))
          case WsEvent(x)                    => (ledger.record(x), false)
        }
        if (clOrdIDMatch) {
          val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
          order.ordStatus match {
            case Filled =>
              log.info(s"Close $dir: filled orderID: ${order.fullOrdID} @ ${order.price}")
              (YabolIdleCtx(ledger2), None)
            case _ =>
              if (log.isDebugEnabled) log.debug(s"Open $dir: catchall: ${order.ordStatus}, order: $order")
              (ctx2.copy(ledger = ledger2), None)
          }
        } else
          (ctx2.copy(ledger = ledger2), None)

      // catchalls
      case (ctx, WsEvent(data)) =>
        (ctx.withLedger(ctx.ledger.record(data)), None)
      case (ctx, RestEvent(Success(data))) =>
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
