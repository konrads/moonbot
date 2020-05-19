package rcb

import rcb.OrderLifecycle.OrderLifecycle
import rcb.OrderSide.OrderSide

import scala.collection.SortedSet



case class LedgerOrder(orderID: String, price: BigDecimal, qty: BigDecimal, lifecycle: OrderLifecycle, side: OrderSide, timestamp: String, exchangeFee: BigDecimal=0, myOrder: Boolean=false) extends Ordered[LedgerOrder] {
  import scala.math.Ordered.orderingToOrdered
  override def compare(that: LedgerOrder): Int = -((this.timestamp, this.orderID) compare (that.timestamp, that.orderID))
}

case class Ledger(minTradeVol: BigDecimal, emaWindow: Int, emaSmoothing: BigDecimal,
                  bullVolumeScoreThreshold: BigDecimal, bearVolumeScoreThreshold: BigDecimal,
                  orderBook: OrderBook=null, trades: Seq[Trade]=Nil,
                  ledgerOrders: SortedSet[LedgerOrder]=SortedSet.empty[LedgerOrder], ledgerOrdersById: Map[String, LedgerOrder]=Map.empty,
                  tick: Long=0) {
  // rest
  def record(os: Orders): Ledger = os.orders.foldLeft(this)((soFar, o) => soFar.record(o))
  def record(o: Order): Ledger =
    ledgerOrdersById.get(o.orderID) match {
      case Some(existing) =>
        val existing2 = existing.copy(myOrder=true)
        copy(ledgerOrders=ledgerOrders-existing2+existing2, ledgerOrdersById=ledgerOrdersById + (existing2.orderID -> existing2), tick=tick+1)
      case None =>
        val lo = LedgerOrder(orderID=o.orderID, price=o.price.get, qty=o.orderQty, side=o.side, timestamp=o.timestamp, lifecycle=o.lifecycle, myOrder=true)
        copy(ledgerOrders=ledgerOrders+lo, ledgerOrdersById=ledgerOrdersById + (lo.orderID -> lo), tick=tick+1)
    }
  // ws
  def record(data: WsModel): Ledger = data match {
    case o: UpsertOrder => {
      // if doesn't exist, insert new ledger order, else update the lifecycle (maybe more...)
      val (ledgerOrders2, ledgerOrdersById2) = o.data.foldLeft((ledgerOrders, ledgerOrdersById)) {
        case ((ls, lsById), od) =>
          lsById.get(od.orderID) match {
            case Some(lo) if od.ordStatus.isDefined =>
              // ??? Update just the lifecycle only if have orderStatus ???
              val lo2 = lo.copy(lifecycle=od.lifecycle)
              (ls - lo2 + lo2, lsById + (lo2.orderID -> lo2))
            case None =>
              val lo = LedgerOrder(orderID=od.orderID, price=od.price.get, qty=od.orderQty.orNull, side=od.side.orNull, timestamp=od.timestamp, lifecycle=od.lifecycle)
              (ls + lo, lsById + (lo.orderID -> lo))
          }
      }
      copy(ledgerOrders=ledgerOrders2, ledgerOrdersById=ledgerOrdersById2)
    }
    case o: OrderBook => copy(orderBook = o)
    case t: Trade => copy(trades = (t +: trades).take(emaWindow))
    case _ => this
  }
  // def record(o: UpsertOrder): Ledger = ???
  // def record(o: OrderBook): Ledger = copy(orderBook = o)
  // def record(t: Trade): Ledger = copy(trades = (t +: trades).take(emaWindow))
  lazy val isMinimallyFilled: Boolean = orderBook != null && trades.nonEmpty
  lazy val sentiment: Sentiment.Value = {
    val (bullTrades, bearTrades) = trades.flatMap(_.data).partition(_.side == "Buy")
    val bullVolume = ema(bullTrades.map(_.size))
    val bearVolume = ema(bearTrades.map(_.size))
    val volumeScore = (bullVolume - bearVolume) / (bullVolume + bearVolume)
    if (volumeScore > bullVolumeScoreThreshold)
      Sentiment.Bull
    else if (volumeScore < bearVolumeScoreThreshold)
      Sentiment.Bull
    else
      Sentiment.Neutral
  }
  lazy val canOpenLong: Boolean = {
    val enoughOrderVol = orderBook.data.head.bids.head(1) > minTradeVol
    enoughOrderVol && sentiment == Sentiment.Bull
  }
  lazy val canOpenShort: Boolean = {
    val enoughOrderVol = orderBook.data.head.asks.head(1) > minTradeVol
    enoughOrderVol && sentiment == Sentiment.Bear
  }
  lazy val bidPrice: BigDecimal = orderBook.data.head.bids.head.head
  lazy val askPrice: BigDecimal = orderBook.data.head.asks.head.head

  // http://stackoverflow.com/questions/24705011/how-to-optimise-a-exponential-moving-average-algorithm-in-php
  private def ema(vals: Seq[BigDecimal]): BigDecimal = {
    val k = emaSmoothing / (vals.length + 1)
    val mean = vals.sum / vals.length
    vals.foldLeft(mean)(
      (last, s) => (1 - k) * last + k * s
    )
  }
}

object Ledger {
  def init(minTradeVol: BigDecimal, emaWindow: Int=20, emaSmoothing: BigDecimal=2.0, bullVolumeScoreThreshold: BigDecimal=0.25, bearVolumeScoreThreshold: BigDecimal=0.25) =
    Ledger(minTradeVol=minTradeVol, emaWindow=emaWindow, emaSmoothing=emaSmoothing, bullVolumeScoreThreshold=bullVolumeScoreThreshold, bearVolumeScoreThreshold=bearVolumeScoreThreshold)
}
