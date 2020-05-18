package rcb

import rcb.OrderLifecycle.OrderLifecycle
import rcb.OrderSide.OrderSide



case class LedgerOrder(id: String, price: BigDecimal, qty: BigDecimal, lifecycle: OrderLifecycle, side: OrderSide, exchangeFee: BigDecimal=0)

case class Ledger(minTradeVol: BigDecimal, emaWindow: Int, emaSmoothing: BigDecimal,
                  bullVolumeScoreThreshold: BigDecimal, bearVolumeScoreThreshold: BigDecimal,
                  orderBook: OrderBook=null, trades: List[Trade]=Nil, myOrders: Seq[LedgerOrder]=Nil) {
  // rest
  def record(os: Orders): Ledger = os.orders.foldLeft(this)((soFar, o) => soFar.record(o))
  def record(o: Order): Ledger = ???
  // ws
  def record(data: WsModel): Ledger = data match {
    case o: UpsertOrder => ???
    case o: OrderBook => copy(orderBook = o)
    case t: Trade => copy(trades = (t +: trades).take(emaWindow))
    case _ => this
  }
  // def record(o: UpsertOrder): Ledger = ???
  // def record(o: OrderBook): Ledger = copy(orderBook = o)
  // def record(t: Trade): Ledger = copy(trades = (t +: trades).take(emaWindow))
  def byOrderID(oID: String): Option[LedgerOrder] = ???
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
