package rcb

import rcb.OrderSide.OrderSide

// Event
sealed trait ActorEvent

case class WsEvent(data: WsModel) extends ActorEvent

// case class RestEvent(data: RestModel) extends ActorEvent

case object OpenOrder extends ActorEvent
case object CancelOrder extends ActorEvent
case object OpenTakeProfit extends ActorEvent
case object CancelTakeProfit extends ActorEvent
case object OpenStoploss extends ActorEvent
case object CancelStoploss extends ActorEvent
case object Expiry extends ActorEvent


// Context
// FIXME: add ledger, orderbook, trade stats, encountered orders
sealed trait ActorCtx

case class IdleCtx(ledger: Ledger) extends ActorCtx
case class OpenPositionCtx(ledger: Ledger, orderID: String=null, retries: Int=0) extends ActorCtx
case class ClosePositionCtx(ledger: Ledger, takeProfitOrderID: String=null, stoplossOrderID: String, retries: Int=0) extends ActorCtx
