package moon

import scala.util.Try

// Event
sealed trait ActorEvent

case class WsEvent(data: WsModel) extends ActorEvent

case class RestEvent(res: Try[RestModel]) extends ActorEvent

case object SendMetrics extends ActorEvent


// Context
object TradeLifecycle extends Enumeration {
  type PositionLifecycle = Value
  val Waiting, IssuingNew, IssuingCancel, IssuingAmend = Value
}

sealed trait ActorCtx
case class InitCtx(ledger: Ledger) extends ActorCtx
case class IdleCtx(ledger: Ledger) extends ActorCtx
case class OpenPositionCtx(ledger: Ledger, clOrdID: String=null, lifecycle: TradeLifecycle.Value=TradeLifecycle.Waiting) extends ActorCtx
case class ClosePositionCtx(ledger: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, lifecycle: TradeLifecycle.Value=TradeLifecycle.Waiting) extends ActorCtx
