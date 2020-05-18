package rcb

import rcb.OrderLifecycle.OrderLifecycle
import rcb.OrderSide.OrderSide

case class LedgerOrder(id: String, price: BigDecimal, qty: BigDecimal, lifecycle: OrderLifecycle, side: OrderSide, exchangeFee: BigDecimal=0)

case class Ledger(minTradeVol: BigDecimal, ema: Int=20, emaSmoothing: BigDecimal=2.0, orderBook: OrderBook=null, trades: List[Trade]=Nil, myOrders: Seq[LedgerOrder]=Nil) {
  def record(o: WsModel): Ledger = ???
  def record(o: UpsertOrder): Ledger = ???
  def record(o: Order): Ledger = ???
  def record(o: OrderBook): Ledger = copy(orderBook = o)
  def record(t: Trade): Ledger = copy(trades = (t +: trades).take(ema))
  def byOrderID(oID: String): Option[LedgerOrder] = ???
  lazy val isMinimallyFilled: Boolean = orderBook != null && trades.nonEmpty
  lazy val canOpenLong: Boolean = {
    val enoughOrderVol = orderBook.data.head.bids.head(1) > minTradeVol
    // val (tradeBulls, tradeBears) = trades.data.partition(_.side == "Buy")
    ???
  }
  lazy val canOpenShort: Boolean = {
    val enoughOrderVol = orderBook.data.head.asks.head(1) > minTradeVol
    ???
  }
  lazy val bidPrice: BigDecimal = orderBook.data.head.bids.head.head
  lazy val askPrice: BigDecimal = orderBook.data.head.asks.head.head

  // http://stackoverflow.com/questions/24705011/how-to-optimise-a-exponential-moving-average-algorithm-in-php
  private def ema(n: Int, ls: Seq[BigDecimal]): BigDecimal = {
    val k = emaSmoothing / (n + 1)
    val mean = ls.sum / ls.length
    ls.takeRight(n).foldLeft(mean)(
      (last, s) => (1 - k) * last + k * s
    )
  }
}

object Ledger {
  def init(minTradeVol: BigDecimal) = Ledger(minTradeVol = minTradeVol)
}
