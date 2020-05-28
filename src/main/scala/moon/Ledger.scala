package moon

import com.github.nscala_time.time.Imports._
import moon.OrderSide.OrderSide
import moon.OrderStatus.{OrderStatus, _}
import org.joda.time.DateTime

import scala.collection.SortedSet


case class LedgerOrder(orderID: String, price: BigDecimal, qty: BigDecimal, ordStatus: OrderStatus, side: OrderSide, ordType: OrderType.Value=null, timestamp: DateTime, myOrder: Boolean=false) extends Ordered[LedgerOrder] {
  import scala.math.Ordered.orderingToOrdered
  override def compare(that: LedgerOrder): Int = -((this.timestamp, this.orderID) compare (that.timestamp, that.orderID))
}

case class LedgerMetrics(metrics: Map[String, BigDecimal], lastOrderTimestamp: DateTime=new DateTime(0), prevPandl: BigDecimal=0)

case class Ledger(emaWindow: Int=20, emaSmoothing: BigDecimal=2.0,
                  orderBook: OrderBook=null, trades: Seq[Trade]=Nil,
                  ledgerOrders: SortedSet[LedgerOrder]=SortedSet.empty[LedgerOrder], ledgerOrdersById: Map[String, LedgerOrder]=Map.empty,
                  ledgerMetrics: Option[LedgerMetrics]=None) {
  // rest
  def record(os: Orders): Ledger = os.orders.foldLeft(this)((soFar, o) => soFar.record(o))
  def record(o: Order): Ledger =
    ledgerOrdersById.get(o.orderID) match {
      case Some(existing) =>
        val existing2 = existing.copy(myOrder=true, ordStatus=o.ordStatus.get, timestamp=o.timestamp)
        copy(ledgerOrders=ledgerOrders-existing+existing2, ledgerOrdersById=ledgerOrdersById + (existing2.orderID -> existing2))
      case None =>
        val lo = LedgerOrder(orderID=o.orderID, qty=o.orderQty, price=o.stopPx.getOrElse(o.price.orNull), side=o.side, ordType=o.ordType, timestamp=o.timestamp, ordStatus=o.ordStatus.getOrElse(OrderStatus.New), myOrder=true)
        copy(ledgerOrders=ledgerOrders+lo, ledgerOrdersById=ledgerOrdersById + (lo.orderID -> lo))
    }
  // ws
  def record(data: WsModel): Ledger = data match {
    case o: UpsertOrder => {
      // if doesn't exist, insert new ledger order, else update the lifecycle (maybe more...)
      val (ledgerOrders2, ledgerOrdersById2) = o.data.foldLeft((ledgerOrders, ledgerOrdersById)) {
        case ((ls, lsById), od) =>
          lsById.get(od.orderID) match {
            case Some(lo) =>
              val lo2 = (lo.ordStatus, od.ordStatus) match {
                case (Filled, _) => lo    // ignore, already set, eg. by REST
                case (Canceled, _) => lo  // ignore, already set, eg. by REST
                case (_, Some(s@New)) =>
                  lo.copy(
                    price=od.stopPx.getOrElse(od.price.getOrElse(lo.price)),
                    qty=od.orderQty.getOrElse(lo.qty),
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType)
                  )
                case (_, Some(s@Canceled)) =>
                  lo.copy(
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType)
                  )
                case (_, Some(s@PostOnlyFailure)) =>
                  lo.copy(
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType)
                  )
                case (_, Some(s@PartiallyFilled)) =>
                  lo.copy(
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType)
                  )
                case (_, Some(s@Filled)) =>
                  lo.copy(
                    ordStatus=s,
                    qty=od.cumQty.getOrElse(lo.qty),
                    price=od.avgPx.getOrElse(od.price.getOrElse(lo.price)),
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType)
                )
                case _ => lo
              }
              (ls - lo + lo2, lsById + (lo2.orderID -> lo2))
            case None =>
              // expecting REST to fill in the initial order...
              val lo = LedgerOrder(orderID=od.orderID, price=od.stopPx.getOrElse(od.avgPx.getOrElse(od.price.getOrElse(-1))), qty=od.orderQty.orNull, side=od.side.orNull, ordType=od.ordType.orNull, timestamp=od.timestamp, ordStatus=od.ordStatus.getOrElse(OrderStatus.New))
              (ls + lo, lsById + (lo.orderID -> lo))
          }
      }
      copy(ledgerOrders=ledgerOrders2, ledgerOrdersById=ledgerOrdersById2)
    }
    case o: OrderBook => copy(orderBook = o)
    case t: Trade => copy(trades = (t +: trades).take(emaWindow))
    case _ => this
  }
  lazy val myOrders = ledgerOrders.filter(_.myOrder)
  // def record(o: UpsertOrder): Ledger = ???
  // def record(o: OrderBook): Ledger = copy(orderBook = o)
  // def record(t: Trade): Ledger = copy(trades = (t +: trades).take(emaWindow))
  lazy val isMinimallyFilled: Boolean = orderBook != null && trades.nonEmpty
  lazy val sentimentScore = {
    if (trades.isEmpty)
      // should not get in here...
      BigDecimal(0)
    else {
      val (bullTrades, bearTrades) = trades.flatMap(_.data).partition(_.side == OrderSide.Buy)
      val bullVolume = ema(bullTrades.map(_.size))
      val bearVolume = ema(bearTrades.map(_.size))
      val volumeScore = (bullVolume - bearVolume) / (bullVolume + bearVolume)
      volumeScore
    }
  }
  lazy val orderBookHeadVolume: BigDecimal = orderBook.data.headOption.map(_.bids.headOption.map(_(1)).getOrElse(BigDecimal(0))).getOrElse(0)
  lazy val bidPrice: BigDecimal = orderBook.data.headOption.map(_.bids.head.head).getOrElse(0)
  lazy val askPrice: BigDecimal = orderBook.data.headOption.map(_.asks.head.head).getOrElse(0)

  def withMetrics(makerRebate: BigDecimal=.00025, takerFee: BigDecimal=.00075): Ledger = {
    val currOrders = ledgerOrders.filter(o => o.myOrder && o.ordStatus == OrderStatus.Filled)
    if (currOrders.isEmpty)
      this
    else {
      val firstSide = currOrders.last.side // note: in descending
      val currOrders2 = currOrders.dropWhile(_.side == firstSide) // eliminate unfinished buy/sell legs
      val currOrders3 = ledgerMetrics.map(m => currOrders2.takeWhile(_.timestamp > m.lastOrderTimestamp)).getOrElse(currOrders2)
      if (currOrders3.isEmpty)
        copy(ledgerMetrics=Some(LedgerMetrics(Map("price" -> (bidPrice + askPrice) / 2))))
      else {
        val pandl = currOrders3.map {
          case LedgerOrder(_, price, qty, _, OrderSide.Buy, OrderType.Limit, _, true)  => -qty * price * (1 - makerRebate)
          case LedgerOrder(_, price, qty, _, OrderSide.Buy, _, _, true)                => -qty * price * (1 + takerFee)
          case LedgerOrder(_, price, qty, _, OrderSide.Sell, OrderType.Limit, _, true) =>  qty * price * (1 + makerRebate)
          case LedgerOrder(_, price, qty, _, OrderSide.Sell, _, _, true)               =>  qty * price * (1 - takerFee)
          case _                                                                       =>  BigDecimal(0)
        }.sum

        val prevPandl = ledgerMetrics.map(_.prevPandl).getOrElse(BigDecimal(0))
        val metrics = LedgerMetrics(
          Map(
            "data.price"          -> (bidPrice + askPrice) / 2,
            "data.pandl"          -> pandl,
            "data.pandlDelta"     -> (pandl - prevPandl),
            "data.sentimentScore" -> sentimentScore,
            "data.myTradesCnt"    -> myOrders.count(_.ordStatus == OrderStatus.Filled)
          ),
          currOrders3.head.timestamp,
          pandl
        )
        copy(ledgerMetrics=Some(metrics))
      }
    }
  }

  // http://stackoverflow.com/questions/24705011/how-to-optimise-a-exponential-moving-average-algorithm-in-php
  private def ema(vals: Seq[BigDecimal]): BigDecimal = {
    if (vals.isEmpty)
      0
    else {
      val k = emaSmoothing / (vals.length + 1)
      val mean = vals.sum / vals.length
      vals.foldLeft(mean)(
        (last, s) => (1 - k) * last + k * s
      )
    }
  }
}
