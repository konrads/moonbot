package moon

import moon.TradeLifecycle.IssuingNew

import scala.util.Try

// Event
sealed trait ActorEvent
case class WsEvent(data: WsModel) extends ActorEvent
case class RestEvent(res: Try[RestModel]) extends ActorEvent
case class SendMetrics(nowMs: Option[Long]) extends ActorEvent

// Context
sealed trait LedgerAwareCtx {
  val ledger: Ledger
  def withLedger(l: Ledger): LedgerAwareCtx
}
sealed trait Ctx extends LedgerAwareCtx {
  def withLedger(l: Ledger): Ctx
}
case class InitCtx(ledger: Ledger) extends Ctx { def withLedger(l: Ledger): InitCtx = copy(ledger = l) }  // entry state, ledger not warm yet
case class IdleCtx(ledger: Ledger) extends Ctx { def withLedger(l: Ledger): IdleCtx = copy(ledger = l) }  // with warm (filled) ledger
case class OpenPositionCtx(dir: Dir.Value, lifecycle: TradeLifecycle.Value = IssuingNew, clOrdID: String = null, ledger: Ledger) extends Ctx { def withLedger(l: Ledger) = copy(ledger = l) }
case class ClosePositionCtx(dir: Dir.Value, lifecycle: TradeLifecycle.Value = IssuingNew, openPrice: Double, takeProfitClOrdID: String, stoplossClOrdID: String, ledger: Ledger) extends Ctx { def withLedger(l: Ledger) = copy(ledger = l) }

sealed trait YabolCtx extends LedgerAwareCtx {
  val ledger: Ledger
  def withLedger(l: Ledger): YabolCtx
}

case class YabolIdleCtx(ledger: Ledger) extends YabolCtx { def withLedger(l: Ledger) = copy(ledger = l) }
case class YabolOpenPositionCtx(dir: Dir.Value, clOrdID: String, qty: Double, ledger: Ledger) extends YabolCtx { def withLedger(l: Ledger) = copy(ledger = l) }
case class YabolWaitingCtx(dir: Dir.Value, qty: Double, ledger: Ledger) extends YabolCtx { def withLedger(l: Ledger) = copy(ledger = l) }
case class YabolClosePositionCtx(dir: Dir.Value, clOrdID: String, ledger: Ledger) extends YabolCtx { def withLedger(l: Ledger) = copy(ledger = l) }


// External side effect, ie. communication with grafana/RestGateway
sealed trait SideEffect
case class PublishMetrics(gauges: Map[String, Any], now: Option[Long]) extends SideEffect
case class CancelOrder(clOrdID: String) extends SideEffect
case class AmendOrder(clOrdID: String, price: Double) extends SideEffect
case class OpenInitOrder(side: OrderSide.Value, ordType: OrderType.Value, clOrdID: String, qty: Double, price: Option[Double] = None) extends SideEffect
case class OpenTakeProfitStoplossOrders(side: OrderSide.Value, qty: Double, takeProfitClOrdID: String, takeProfitLimit: Double, stoplossClOrdID: String, stoplossMargin: Option[Double] = None, stoplossPeg: Option[Double] = None) extends SideEffect
