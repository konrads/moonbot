package moon

import akka.actor.typed.ActorRef

import scala.util.Try

// Event
sealed trait ActorEvent
case class WsEvent(data: WsModel) extends ActorEvent
case class RestEvent(res: Try[RestModel]) extends ActorEvent
case class SendMetrics(nowMs: Option[Long]) extends ActorEvent

sealed trait SimEvent
case class EventProcessed() extends SimEvent
case class BulkOrders(orderReqs: OrderReqs, replyTo: ActorRef[Orders]) extends SimEvent
case class SingleOrder(orderReq: OrderReq, replyTo: ActorRef[Order]) extends SimEvent
case class AmendOrder(orderID: Option[String], origClOrdID: Option[String], price: Double, replyTo: ActorRef[Order]) extends SimEvent
case class CancelOrder(orderID: Seq[String], clOrdID: Seq[String], replyTo: ActorRef[Orders]) extends SimEvent

// Context
object TradeLifecycle extends Enumeration {
  type PositionLifecycle = Value
  val Waiting = Value                   // issued new order, awaiting confirmation of fill/postOnlyFailure(if in open)/(cancel if in close)
  val IssuingNew = Value                // awaiting order creation confirmation
  val IssuingOpenAmend = Value          // awaiting amend confirmation
  val IssuingOpenCancel = Value         // awaiting cancellation confirmation
  val IssuingTakeProfitCancel = Value   // awaiting takProfit cancel confirmation
  val IssuingStoplossCancel = Value     // awaiting stoploss cancel confirmation
}

object Dir extends Enumeration {
  type Dir = Value
  val Long, Short = Value
}

sealed trait ActorCtx
case class InitCtx(ledger: Ledger) extends ActorCtx
case class IdleCtx(ledger: Ledger) extends ActorCtx
case class OpenPositionCtx(ledger: Ledger, clOrdID: String=null, lifecycle: TradeLifecycle.Value=TradeLifecycle.Waiting) extends ActorCtx
case class ClosePositionCtx(ledger: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, lifecycle: TradeLifecycle.Value=TradeLifecycle.Waiting) extends ActorCtx
