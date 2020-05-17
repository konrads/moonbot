package rcb

import rcb.OrderLifecycle.OrderLifecycle
import rcb.OrderSide.OrderSide

case class LedgerOrder(id: String, price: BigDecimal, qty: BigDecimal, lifecycle: OrderLifecycle, side: OrderSide, exchangeFee: BigDecimal=0)

class Ledger {
  def record(o: WsModel): Ledger = ???
  def record(o: UpsertOrder): Ledger = ???
  def record(o: Order): Ledger = ???
  def record(o: OrderBook): Ledger = ???
  def record(o: Trade): Ledger = ???
  def byOrderID(oID: String): Option[LedgerOrder] = ???
  lazy val canOpenLong: Boolean = ???
  lazy val canOpenShort: Boolean = ???
  lazy val bidPrice: BigDecimal = ???
  lazy val askPrice: BigDecimal = ???
}
