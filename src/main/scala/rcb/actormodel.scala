package rcb

import rcb.OrderSide.OrderSide

// Event
sealed trait ActorEvent

case class WsEvent(data: WsModel) extends ActorEvent

// case class RestEvent(data: RestModel) extends ActorEvent

case object TriggerReq extends ActorEvent

case object TriggerRereq extends ActorEvent

case object TriggerCancel extends ActorEvent

case object Expiry extends ActorEvent


// Context
// FIXME: add ledger, orderbook, trade stats, encountered orders
sealed trait ActorCtx

case class IdleCtx(ledger: Ledger) extends ActorCtx

case class OpenOrderCtx(ledger: Ledger, orderID: String=null, retries: Int=0) extends ActorCtx

case class LongCtx(ledger: Ledger) extends ActorCtx

case class ShortCtx(ledger: Ledger) extends ActorCtx


//sealed trait ActorCtx[T] {
//  val ledger: Ledger
//  def withLedger(ledger: Ledger): T
//}
//
//case class IdleCtx(ledger: Ledger) extends ActorCtx[IdleCtx] { override def withLedger(ledger: Ledger): IdleCtx = copy(ledger=ledger) }
//
//case class OpenOrderCtx(ledger: Ledger, orderID: String, retries: Int) extends ActorCtx[OpenOrderCtx] { override def withLedger(ledger: Ledger): OpenOrderCtx = copy(ledger=ledger) }
//
//case class LongCtx(ledger: Ledger) extends ActorCtx[LongCtx] { override def withLedger(ledger: Ledger): LongCtx = copy(ledger=ledger) }
//
//case class ShortCtx(ledger: Ledger) extends ActorCtx[ShortCtx] { override def withLedger(ledger: Ledger): ShortCtx = copy(ledger=ledger) }
