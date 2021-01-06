package moon

import scala.util.Try

// Event
sealed trait ActorEvent
case class WsEvent(data: WsModel) extends ActorEvent
case class RestEvent(res: Try[RestModel]) extends ActorEvent
case class On30s(nowMs: Option[Long]) extends ActorEvent
case class On1m(nowMs: Option[Long]) extends ActorEvent
case class On5m(nowMs: Option[Long]) extends ActorEvent

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
case class OpenPositionCtx(clOrdID: String = null, ledger: Ledger, targetPrice: Double, lifecycle: TradeLifecycle.Value) extends Ctx { def withLedger(l: Ledger) = copy(ledger = l) }
case class ClosePositionCtx(openClOrdID: String, openPrice: Double, takeProfitClOrdID: String, ledger: Ledger, lifecycle: TradeLifecycle.Value) extends Ctx { def withLedger(l: Ledger) = copy(ledger = l) }

// External side effect, ie. communication with grafana/RestGateway
sealed trait SideEffect
case object HealthCheck extends SideEffect
case class PublishMetrics(gauges: Map[String, Any], now: Option[Long]) extends SideEffect
case class CancelOrder(clOrdID: String) extends SideEffect
case class AmendOrder(clOrdID: String, price: Double) extends SideEffect
case class OpenInitOrder(side: OrderSide.Value, ordType: OrderType.Value, clOrdID: String, qty: Double, price: Option[Double] = None) extends SideEffect
case class OpenTakeProfitOrder(side: OrderSide.Value, qty: Double, takeProfitClOrdID: String, takeProfitLimit: Double) extends SideEffect
