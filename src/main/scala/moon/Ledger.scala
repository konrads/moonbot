package moon

import com.github.nscala_time.time.Imports._
import moon.OrderSide.OrderSide
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.TickDirection._
import org.joda.time.DateTime

import scala.collection.SortedSet


case class LedgerOrder(orderID: String, clOrdID: String=null, price: BigDecimal, qty: BigDecimal, ordStatus: OrderStatus, side: OrderSide, ordType: OrderType.Value=null, ordRejReason: Option[String]=None, timestamp: DateTime, myOrder: Boolean=false) extends Ordered[LedgerOrder] {
  import scala.math.Ordered.orderingToOrdered
  override def compare(that: LedgerOrder): Int = -((this.timestamp, this.orderID) compare (that.timestamp, that.orderID))
  lazy val fullOrdID = s"$orderID / $clOrdID"
}

case class LedgerMetrics(metrics: Map[String, Any]=Map.empty, lastOrderTimestamp: DateTime=new DateTime(0), runningPandl: BigDecimal=0)

case class Ledger(emaWindow: Int=20, emaSmoothing: BigDecimal=2.0,
                  orderBook: OrderBook=null, trades: Seq[Trade]=Nil,
                  ledgerOrders: SortedSet[LedgerOrder]=SortedSet.empty[LedgerOrder], ledgerOrdersByID: Map[String, LedgerOrder]=Map.empty, ledgerOrdersByClOrdID: Map[String, LedgerOrder]=Map.empty,
                  ledgerMetrics: LedgerMetrics=LedgerMetrics()) {
  // rest
  def record(data: RestModel): Ledger = data match {
    case os:Orders =>
      os.orders.foldLeft(this)((soFar, o) => soFar.record(o))
    case o: Order =>
      ledgerOrdersByID.get(o.orderID) match {
//        case Some(existing) if existing.ordStatus == Filled || existing.ordStatus == Canceled =>
//          // mark as "mine" only
//          val existing2 = existing.copy(myOrder = true)
//          val ledgerOrdersByClOrdID2 = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2)
//          copy(ledgerOrders=ledgerOrders-existing+existing2, ledgerOrdersByID=ledgerOrdersByID + (existing2.orderID -> existing2), ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
        case Some(existing) =>
          val existing2 = existing.copy(myOrder=true, clOrdID=o.clOrdID.getOrElse(existing.clOrdID), ordStatus=o.ordStatus.getOrElse(existing.ordStatus), price=o.stopPx.getOrElse(o.price.getOrElse(existing.price)), timestamp=o.timestamp)
          val ledgerOrdersByClOrdID2 = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2)
          copy(ledgerOrders=ledgerOrders-existing+existing2, ledgerOrdersByID=ledgerOrdersByID + (existing2.orderID -> existing2), ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
        case None =>
          val lo = LedgerOrder(orderID=o.orderID, clOrdID=o.clOrdID.orNull, qty=o.orderQty, price=o.stopPx.getOrElse(o.price.orNull), side=o.side, ordType=o.ordType, timestamp=o.timestamp, ordStatus=o.ordStatus.getOrElse(New), ordRejReason=o.ordRejReason, myOrder=true)
          val ledgerOrdersByClOrdID2 = if (lo.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (lo.clOrdID -> lo)
          copy(ledgerOrders=ledgerOrders+lo, ledgerOrdersByID=ledgerOrdersByID + (lo.orderID -> lo), ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
      }
    case _ =>
      this
  }
  // ws
  def record(data: WsModel): Ledger = data match {
    case o: UpsertOrder => {
      // if doesn't exist, insert new ledger order, else update the lifecycle (maybe more...)
      val (ledgerOrders2, ledgerOrdersByID2, ledgerOrdersByClOrdID2) = o.data.foldLeft((ledgerOrders, ledgerOrdersByID, ledgerOrdersByClOrdID)) {
        case ((ls, lsById, lsByClOrdID), od) =>
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
                case (_, Some(s@Rejected)) =>
                  lo.copy(
                    ordStatus=s,
                    ordRejReason=od.ordRejReason,
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
              val lsByClOrdID2 = if (lo2.clOrdID == null) lsByClOrdID else lsByClOrdID + (lo2.clOrdID -> lo2)
              (ls - lo + lo2, lsById + (lo2.orderID -> lo2), lsByClOrdID2)
            case None =>
              // expecting REST to fill in the initial order...
              val lo = LedgerOrder(orderID=od.orderID, clOrdID=od.clOrdID.orNull, price=od.stopPx.getOrElse(od.avgPx.getOrElse(od.price.getOrElse(-1))), qty=od.orderQty.orNull, side=od.side.orNull, ordType=od.ordType.orNull, timestamp=od.timestamp, ordStatus=od.ordStatus.getOrElse(New), ordRejReason=od.ordRejReason)
              val lsByClOrdID2 = if (lo.clOrdID == null) lsByClOrdID else lsByClOrdID + (lo.clOrdID -> lo)
              (ls + lo, lsById + (lo.orderID -> lo), lsByClOrdID2)
          }
      }
      copy(ledgerOrders=ledgerOrders2, ledgerOrdersByID=ledgerOrdersByID2, ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
    }
    case o: OrderBook => copy(orderBook = o)
    case t: Trade => copy(trades = (t +: trades).take(emaWindow))
    case _ => this
  }
  lazy val myOrders = ledgerOrders.filter(_.myOrder)
  lazy val isMinimallyFilled: Boolean = orderBook != null && trades.nonEmpty
  lazy val sentimentScore = {
    if (trades.isEmpty)
      // should not get in here...
      BigDecimal(0)
    else {
      val (bullTrades, bearTrades) = trades.flatMap(_.data).partition(_.side == Buy)
      val bullVolume = ema(emaSmoothing)(bullTrades.map(_.size))
      val bearVolume = ema(emaSmoothing)(bearTrades.map(_.size))
      val volumeScore = (bullVolume - bearVolume) / (bullVolume + bearVolume)
      volumeScore
    }
  }
  lazy val orderBookHeadVolume: BigDecimal = orderBook.data.headOption.map(_.bids.headOption.map(_(1)).getOrElse(BigDecimal(0))).getOrElse(0)
  lazy val bidPrice: BigDecimal = orderBook.data.headOption.map(_.bids.head.head).getOrElse(0)
  lazy val askPrice: BigDecimal = orderBook.data.headOption.map(_.asks.head.head).getOrElse(0)

  def withMetrics(makerRebate: BigDecimal=.00025, takerFee: BigDecimal=.00075): Ledger = {
    val volume = trades.flatMap(_.data.map(_.size)).sum
    val tickDirs = trades.flatMap(_.data.map(_.tickDirection))
    val tickDirScore = if (tickDirs.isEmpty) 0.0 else tickDirs.map {
      case MinusTick     => -1
      case ZeroMinusTick => -.5
      case ZeroPlusTick  =>  .5
      case PlusTick      =>  1
    }.sum / tickDirs.length
    val metricsVals = Map(
      "data.price"           -> (bidPrice + askPrice) / 2,
      "data.volume"          -> volume,
      "data.tickDir.score"   -> tickDirScore,
      "data.sentiment.score" -> sentimentScore,
      "data.myTradeCnt"      -> myOrders.count(_.ordStatus == Filled),
      // following will be updated if have filled myOrders since last withMetrics()
      "data.pandl.pandl"     -> ledgerMetrics.runningPandl,
      "data.pandl.delta"     -> 0,
    )

    val currOrders = ledgerOrders.filter(o => o.myOrder && o.ordStatus == Filled)
    if (currOrders.isEmpty)
      copy(ledgerMetrics=ledgerMetrics.copy(metrics=metricsVals), trades=Nil)
    else {
      val firstSide = currOrders.last.side // note: in descending
      val currOrders2 = currOrders.dropWhile(_.side == firstSide) // eliminate unfinished buy/sell legs
      val currOrders3 = currOrders2.takeWhile(_.timestamp > ledgerMetrics.lastOrderTimestamp)
      // used for debugging ledger's pandl/delta:
      // import Ledger.log; log.debug(s"Ledger.withMetrics: lastOrderTimestamp: ${ledgerMetrics.lastOrderTimestamp}\nledgerOrders:${ledgerOrders.toSeq.map(o => s"\n- $o").mkString}\ncurrOrders3:${currOrders3.toSeq.map(o => s"\n- $o").mkString}")
      if (currOrders3.isEmpty)
        copy(ledgerMetrics=ledgerMetrics.copy(metrics=metricsVals), trades=Nil)
      else {
        val pandlDelta = currOrders3.map {
          case LedgerOrder(_, _, price, qty, _, Buy, Limit, _, _, true)  =>  qty / price * (1 + makerRebate)
          case LedgerOrder(_, _, price, qty, _, Buy, _, _, _, true)      =>  qty / price * (1 - takerFee)
          case LedgerOrder(_, _, price, qty, _, Sell, Limit, _, _, true) => -qty / price * (1 - makerRebate)
          case LedgerOrder(_, _, price, qty, _, Sell, _, _, _, true)     => -qty / price * (1 + takerFee)
          case _                                                         =>  BigDecimal(0)
        }.sum

        val runningPandl2 = ledgerMetrics.runningPandl + pandlDelta
        val ledgerMetrics2 = ledgerMetrics.copy(
          metrics = metricsVals + ("data.pandl.pandl" -> runningPandl2) + ("data.pandl.delta" -> pandlDelta),
          lastOrderTimestamp = currOrders3.head.timestamp,
          runningPandl = runningPandl2
        )
        copy(ledgerMetrics=ledgerMetrics2, trades=Nil)
      }
    }
  }
}

object Ledger {
  import com.typesafe.scalalogging.Logger
  private val log = Logger[Ledger]
}
