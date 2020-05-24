package rcb

import play.api.libs.json.Json

// Event
sealed trait ActorEvent

case class WsEvent(data: WsModel) extends ActorEvent

// case class RestEvent(data: RestModel) extends ActorEvent

case object OpenTakeProfit extends ActorEvent
case object Expiry extends ActorEvent
case object Issue extends ActorEvent
case class Cancel(orderID: String*) extends ActorEvent
case object Instrument extends ActorEvent


// Context
object TradeLifecycle extends Enumeration {
  type PositionLifecycle = Value
  val Issuing, Cancelling, IssueBackOff, CancelBackOff = Value
}

sealed trait ActorCtx
case class InitCtx(ledger: Ledger) extends ActorCtx
case class IdleCtx(ledger: Ledger) extends ActorCtx
case class OpenPositionCtx(ledger: Ledger, orderID: String=null, lifecycle: TradeLifecycle.Value=TradeLifecycle.Issuing, markupRetry: Int=0) extends ActorCtx
case class ClosePositionCtx(ledger: Ledger, orderIDs: Seq[String]=Nil, lifecycle: TradeLifecycle.Value=TradeLifecycle.Issuing) extends ActorCtx
