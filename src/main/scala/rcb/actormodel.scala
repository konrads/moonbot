package rcb

// Event
sealed trait ActorEvent

case class WsEvent(data: WsModel) extends ActorEvent

// case class RestEvent(data: RestModel) extends ActorEvent

case object OpenTakeProfit extends ActorEvent
case object Expiry extends ActorEvent
case object Instrument extends ActorEvent


// Context
sealed trait ActorCtx
case class InitCtx(ledger: Ledger) extends ActorCtx
case class IdleCtx(ledger: Ledger) extends ActorCtx
case class OpenPositionCtx(ledger: Ledger, orderID: String, openPrice: Option[BigDecimal]=None, markupRetry: Int=0) extends ActorCtx
case class ClosePositionCtx(ledger: Ledger, relevantOrderIDs: Seq[String]=Nil) extends ActorCtx
