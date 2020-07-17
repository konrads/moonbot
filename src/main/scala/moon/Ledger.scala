package moon

import com.github.nscala_time.time.Imports._
import moon.OrderSide.{OrderSide, _}
import moon.OrderStatus._
import moon.OrderType._
import org.joda.time.DateTime

import scala.collection.SortedSet


case class LedgerOrder(orderID: String, clOrdID: String=null, price: Double, qty: Double, ordStatus: OrderStatus, side: OrderSide, ordType: OrderType.Value=null, ordRejReason: Option[String]=None, timestamp: DateTime, myOrder: Boolean=false) extends Ordered[LedgerOrder] {
  import scala.math.Ordered.orderingToOrdered
  override def compare(that: LedgerOrder): Int = -((this.timestamp, this.orderID) compare (that.timestamp, that.orderID))
  lazy val fullOrdID = s"$orderID / $clOrdID"
}

case class LedgerMetrics(metrics: Map[String, Any]=Map.empty, lastOrderTimestamp: DateTime=new DateTime(0), lastTradeData: TradeData=null, runningPandl: Double=0)

case class Ledger(orderBookSummary: OrderBookSummary=null, tradeDatas: Seq[TradeData]=Vector.empty,
                  ledgerOrders: SortedSet[LedgerOrder]=SortedSet.empty[LedgerOrder], ledgerOrdersByID: Map[String, LedgerOrder]=Map.empty, ledgerOrdersByClOrdID: Map[String, LedgerOrder]=Map.empty,
                  ledgerMetrics: LedgerMetrics=LedgerMetrics()) {
  // rest
  def record(data: RestModel): Ledger = data match {
    case os:Orders =>
      os.orders.foldLeft(this)((soFar, o) => soFar.record(o))
    case o: Order =>
      ledgerOrdersByID.get(o.orderID) match {
        case Some(existing) if existing.ordStatus == Filled || existing.ordStatus == Canceled =>
          // copy all except for ordStatus
          val existing2 = existing.copy(myOrder=true, clOrdID=o.clOrdID.getOrElse(existing.clOrdID), price=o.stopPx.getOrElse(o.price.getOrElse(existing.price)), side=o.side, ordType=o.ordType, timestamp=o.timestamp)
          val ledgerOrdersByClOrdID2 = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2)
          copy(ledgerOrders=ledgerOrders-existing+existing2, ledgerOrdersByID=ledgerOrdersByID + (existing2.orderID -> existing2), ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
        case Some(existing) =>
          val existing2 = existing.copy(myOrder=true, clOrdID=o.clOrdID.getOrElse(existing.clOrdID), ordStatus=o.ordStatus.getOrElse(existing.ordStatus), price=o.stopPx.getOrElse(o.price.getOrElse(existing.price)), timestamp=o.timestamp)
          val ledgerOrdersByClOrdID2 = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2)
          copy(ledgerOrders=ledgerOrders-existing+existing2, ledgerOrdersByID=ledgerOrdersByID + (existing2.orderID -> existing2), ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
        case None =>
          val lo = LedgerOrder(orderID=o.orderID, clOrdID=o.clOrdID.orNull, qty=o.orderQty, price=o.stopPx.getOrElse(o.price.get), side=o.side, ordType=o.ordType, timestamp=o.timestamp, ordStatus=o.ordStatus.getOrElse(New), ordRejReason=o.ordRejReason, myOrder=true)
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
              val lo = LedgerOrder(orderID=od.orderID, clOrdID=od.clOrdID.orNull, price=od.stopPx.getOrElse(od.avgPx.getOrElse(od.price.getOrElse(-1))), qty=od.cumQty.getOrElse(od.orderQty.getOrElse(0.0)), side=od.side.orNull, ordType=od.ordType.orNull, timestamp=od.timestamp, ordStatus=od.ordStatus.getOrElse(New), ordRejReason=od.ordRejReason)
              val lsByClOrdID2 = if (lo.clOrdID == null) lsByClOrdID else lsByClOrdID + (lo.clOrdID -> lo)
              (ls + lo, lsById + (lo.orderID -> lo), lsByClOrdID2)
          }
      }
      copy(ledgerOrders=ledgerOrders2, ledgerOrdersByID=ledgerOrdersByID2, ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
    }
    case o: OrderBookSummary => copy(orderBookSummary = o)
    case o: OrderBook => copy(orderBookSummary = o.summary)  // deprecated...
    case t: Trade => copy(tradeDatas = tradeDatas ++ t.data)
    case td: TradeData => copy(tradeDatas = tradeDatas :+ td)
    case _ => this
  }
  lazy val myOrders: SortedSet[LedgerOrder] = ledgerOrders.filter(_.myOrder)
  lazy val isMinimallyFilled: Boolean = orderBookSummary != null && tradeDatas.nonEmpty
  lazy val bidPrice: Double = orderBookSummary.bid
  lazy val askPrice: Double = orderBookSummary.ask

  def withMetrics(makerRebate: Double=.00025, takerFee: Double=.00075, strategy: Strategy): Ledger = {
    val currTradeDatas = if (ledgerMetrics.lastTradeData == null)
      tradeDatas
    else
      tradeDatas.dropWhile(_ != ledgerMetrics.lastTradeData).drop(1)
    val volume = currTradeDatas.map(_.size).sum
    val strategyRes = strategy.strategize(this)
    val (s, m, l) = (strategyRes.sentiment, strategyRes.metrics, strategyRes.ledger)
    val metricsVals = Map(
      "data.price"           -> (l.bidPrice + l.askPrice) / 2,
      "data.volume"          -> volume,
      "data.sentiment"       -> s.id,
      "data.myTradeCnt"      -> l.myOrders.count(_.ordStatus == Filled),
      // following will be updated if have filled myOrders since last withMetrics()
      "data.pandl.pandl"     -> l.ledgerMetrics.runningPandl,
      "data.pandl.delta"     -> 0.0,
    ) ++ m

    val currOrders = l.ledgerOrders.filter(o => o.myOrder && o.ordStatus == Filled)
    // FIXME: *BIG* assumption - counting filled order pairs. Probably should instead use a concept of legs/roundtrips
    val currOrders2 = currOrders.takeWhile(_.timestamp > l.ledgerMetrics.lastOrderTimestamp)
    val currOrders3 = if (currOrders2.size % 2 == 1) currOrders2.drop(1) else currOrders2
    if (currOrders3.isEmpty)
      l.copy(ledgerMetrics=l.ledgerMetrics.copy(metrics=metricsVals, lastTradeData=currTradeDatas.lastOption.orNull))
    else {
      // import Ledger.log; log.debug(s"Ledger.withMetrics: lastOrderTimestamp: ${ledgerMetrics.lastOrderTimestamp}\nledgerOrders:${ledgerOrders.toVector.map(o => s"\n- $o").mkString}\ncurrOrders3:${currOrders3.toVector.map(o => s"\n- $o").mkString}")
      val pandlDelta = currOrders3.map {
        case LedgerOrder(_, _, price, qty, _, Buy, Limit, _, _, true)  =>  qty / price * (1 + makerRebate)
        case LedgerOrder(_, _, price, qty, _, Buy, _, _, _, true)      =>  qty / price * (1 - takerFee)
        case LedgerOrder(_, _, price, qty, _, Sell, Limit, _, _, true) => -qty / price * (1 - makerRebate)
        case LedgerOrder(_, _, price, qty, _, Sell, _, _, _, true)     => -qty / price * (1 + takerFee)
        case _                                                         =>  0.0
      }.sum

      val runningPandl2 = l.ledgerMetrics.runningPandl + pandlDelta
      val ledgerMetrics2 = l.ledgerMetrics.copy(
        metrics = metricsVals + ("data.pandl.pandl" -> runningPandl2) + ("data.pandl.delta" -> pandlDelta),
        lastOrderTimestamp = currOrders3.head.timestamp,
        runningPandl = runningPandl2,
        lastTradeData=currTradeDatas.lastOption.orNull
      )
      l.copy(ledgerMetrics=ledgerMetrics2)
    }
  }
}

object Ledger {
  import com.typesafe.scalalogging.Logger
  private val log = Logger[Ledger]
}
