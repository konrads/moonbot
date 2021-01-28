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
  val relatedClOrdIds: Map[String, String]
  val tiers: Map[String, Int]
  def copyBasic(l: Ledger, relatedClOrdIds: Map[String, String], tiers: Map[String, Int]): LedgerAwareCtx
}
sealed trait Ctx extends LedgerAwareCtx {
  def copyBasic(l: Ledger, relatedClOrdIds: Map[String, String], tiers: Map[String, Int]): Ctx
}
case class InitCtx(ledger: Ledger, relatedClOrdIds: Map[String, String]=Map.empty, tiers: Map[String, Int]=Map.empty) extends Ctx { def copyBasic(l: Ledger, r: Map[String, String], t: Map[String, Int]) = copy(ledger = l, relatedClOrdIds = r, tiers = t) }  // entry state, ledger not warm yet
case class IdleCtx(ledger: Ledger, relatedClOrdIds: Map[String, String], tiers: Map[String, Int]) extends Ctx { def copyBasic(l: Ledger, r: Map[String, String], t: Map[String, Int]) = copy(ledger = l, relatedClOrdIds = r, tiers = t) }  // with warm (filled) ledger
case class OpenPositionCtx(clOrdID: String = null, ledger: Ledger, targetPrice: Double, lifecycle: TradeLifecycle.Value, relatedClOrdIds: Map[String, String], tiers: Map[String, Int]) extends Ctx { def copyBasic(l: Ledger, r: Map[String, String], t: Map[String, Int]) = copy(ledger = l, relatedClOrdIds = r, tiers = t) }
case class ClosePositionCtx(openClOrdID: String, openPrice: Double, openQty: Double, takeProfitClOrdID: String, ledger: Ledger, lifecycle: TradeLifecycle.Value, relatedClOrdIds: Map[String, String], tiers: Map[String, Int]) extends Ctx { def copyBasic(l: Ledger, r: Map[String, String], t: Map[String, Int]) = copy(ledger = l, relatedClOrdIds = r, tiers = t) }

// External side effect, ie. communication with grafana/RestGateway
sealed trait SideEffect
case object HealthCheck extends SideEffect
case class PublishMetrics(gauges: Map[String, Any], now: Option[Long]) extends SideEffect
case class CancelOrder(clOrdID: String) extends SideEffect
case class AmendOrder(clOrdID: String, price: Double, qty: Double) extends SideEffect
case class OpenInitOrder(symbol: String, side: OrderSide.Value, ordType: OrderType.Value, clOrdID: String, qty: Double, price: Option[Double] = None) extends SideEffect
case class OpenTakeProfitOrder(symbol: String, side: OrderSide.Value, qty: Double, takeProfitClOrdID: String, takeProfitLimit: Double) extends SideEffect
