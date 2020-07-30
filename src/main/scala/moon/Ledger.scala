package moon

import moon.DataFreq._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import org.joda.time.DateTime

import scala.collection.immutable.ListMap


case class LedgerOrder(orderID: String, clOrdID: String=null, price: Double, qty: Double, ordStatus: OrderStatus, side: OrderSide, ordType: OrderType.Value=null, ordRejReason: Option[String]=None, timestamp: DateTime, myOrder: Boolean=false) {
  lazy val fullOrdID = s"$orderID / $clOrdID"
}

case class LedgerMetrics(metrics: Map[String, Any]=Map.empty, lastOrderID: String=null, runningPandl: Double=0)

case class Ledger(orderBookSummary: OrderBookSummary=null, tradeRollups: Rollups=Rollups(14*24),
                  ledgerOrdersByID: ListMap[String, LedgerOrder]=ListMap.empty,
                  ledgerOrdersByClOrdID: ListMap[String, LedgerOrder]=ListMap.empty,
                  ledgerMetrics: LedgerMetrics=LedgerMetrics()) {

  @deprecated lazy val ledgerOrders = ledgerOrdersByID.values

  // rest
  def record(data: RestModel): Ledger = data match {
    case os:Orders =>
      os.orders.foldLeft(this)((soFar, o) => soFar.record(o))
    case o: Order =>
      ledgerOrdersByID.get(o.orderID) match {
        case Some(existing) if existing.ordStatus == Filled || existing.ordStatus == Canceled =>
          // copy all except for ordStatus
          val existing2 = existing.copy(myOrder=true, clOrdID=o.clOrdID.getOrElse(existing.clOrdID), price=o.stopPx.getOrElse(o.price.getOrElse(existing.price)), side=o.side, ordType=o.ordType, timestamp=o.timestamp)
          copy(
            ledgerOrdersByID = ledgerOrdersByID + (existing2.orderID -> existing2),
            ledgerOrdersByClOrdID = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2))
        case Some(existing) =>
          val existing2 = existing.copy(myOrder=true, clOrdID=o.clOrdID.getOrElse(existing.clOrdID), ordStatus=o.ordStatus.getOrElse(existing.ordStatus), price=o.stopPx.getOrElse(o.price.getOrElse(existing.price)), timestamp=o.timestamp)
          copy(
            ledgerOrdersByID = ledgerOrdersByID + (existing2.orderID -> existing2),
            ledgerOrdersByClOrdID = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2))
        case None =>
          val lo = LedgerOrder(orderID=o.orderID, clOrdID=o.clOrdID.orNull, qty=o.orderQty, price=o.stopPx.getOrElse(o.price.getOrElse(-1.0)), side=o.side, ordType=o.ordType, timestamp=o.timestamp, ordStatus=o.ordStatus.getOrElse(New), ordRejReason=o.ordRejReason, myOrder=true)
          copy(
            ledgerOrdersByID = ledgerOrdersByID + (lo.orderID -> lo),
            ledgerOrdersByClOrdID = if (lo.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID +(lo.clOrdID -> lo))
      }
    case _ =>
      this
  }
  // ws
  def record(data: WsModel): Ledger = data match {
    case o: UpsertOrder => {
      // if doesn't exist, insert new ledger order, else update the lifecycle (maybe more...)
      val (ledgerOrdersByID2, ledgerOrdersByClOrdID2) = o.data.foldLeft(ledgerOrdersByID, ledgerOrdersByClOrdID) {
        case ((lsById, lsByClOrdID), od) =>
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
              (lsById + (lo2.orderID -> lo2), lsByClOrdID2)
            case None =>
              // expecting REST to fill in the initial order...
              val lo = LedgerOrder(orderID=od.orderID, clOrdID=od.clOrdID.orNull, price=od.stopPx.getOrElse(od.avgPx.getOrElse(od.price.getOrElse(-1))), qty=od.cumQty.getOrElse(od.orderQty.getOrElse(0.0)), side=od.side.orNull, ordType=od.ordType.orNull, timestamp=od.timestamp, ordStatus=od.ordStatus.getOrElse(New), ordRejReason=od.ordRejReason)
              val lsByClOrdID2 = if (lo.clOrdID == null) lsByClOrdID else lsByClOrdID + (lo.clOrdID -> lo)
              (lsById + (lo.orderID -> lo), lsByClOrdID2)
          }
      }
      copy(ledgerOrdersByID=ledgerOrdersByID2, ledgerOrdersByClOrdID=ledgerOrdersByClOrdID2)
    }
    case o: OrderBookSummary => copy(orderBookSummary = o)
    case o: OrderBook => copy(orderBookSummary = o.summary)  // deprecated...
    case t: Trade => copy(tradeRollups = t.data.foldLeft(tradeRollups) { case (soFar, td) => soFar.add(td.timestamp.getMillis, td.price, td.size)})
    case td: TradeData => copy(tradeRollups = tradeRollups.add(td.timestamp.getMillis, td.price, td.size))
    case _ => this
  }
  lazy val myOrders: Seq[LedgerOrder] = ledgerOrdersByID.values.filter(_.myOrder).toVector
  lazy val myTrades: Seq[LedgerOrder] = myOrders.filter(_.ordStatus == Filled)
  lazy val isMinimallyFilled: Boolean = orderBookSummary != null && tradeRollups.nonEmpty
  lazy val bidPrice: Double = orderBookSummary.bid
  lazy val askPrice: Double = orderBookSummary.ask

  def withMetrics(makerRebate: Double=.00025, takerFee: Double=.00075, strategy: Strategy): Ledger = {
    val volume = tradeRollups.forBucket(`1m`).forecast.volume.lastOption.getOrElse(0)
    val strategyRes = strategy.strategize(this)
    val (s, m) = (strategyRes.sentiment, strategyRes.metrics)
    val metricsVals = Map(
      "data.price"           -> (bidPrice + askPrice) / 2,
      "data.volume"          -> volume,
      "data.sentiment"       -> s.id,
      "data.myTradeCnt"      -> myTrades.size,
      // following will be updated if have filled myOrders since last withMetrics()
      "data.pandl.pandl"     -> ledgerMetrics.runningPandl,
      "data.pandl.delta"     -> 0.0,
    ) ++ m

    val currOrders = ledgerOrdersByID.values.filter(o => o.myOrder && o.ordStatus == Filled)
    // FIXME: *BIG* assumption - counting filled order pairs. Probably should instead use a concept of legs/roundtrips
    val currOrders2 = if (ledgerMetrics.lastOrderID == null)
      currOrders
    else
      currOrders.dropWhile(_.orderID != ledgerMetrics.lastOrderID).drop(1)
    val currOrders3 = if (currOrders2.size % 2 == 1) currOrders2.dropRight(1) else currOrders2
    if (currOrders3.isEmpty)
      copy(ledgerMetrics=ledgerMetrics.copy(metrics=metricsVals))
    else {
      // import Ledger.log; log.debug(s"Ledger.withMetrics: lastOrderTimestamp: ${ledgerMetrics.lastOrderTimestamp}\nledgerOrders:${ledgerOrders.toVector.map(o => s"\n- $o").mkString}\ncurrOrders3:${currOrders3.toVector.map(o => s"\n- $o").mkString}")
      val pandlDelta = currOrders3.map {
        case LedgerOrder(_, _, price, qty, _, Buy, Limit, _, _, true)  =>  qty / price * (1 + makerRebate)
        case LedgerOrder(_, _, price, qty, _, Buy, _, _, _, true)      =>  qty / price * (1 - takerFee)
        case LedgerOrder(_, _, price, qty, _, Sell, Limit, _, _, true) => -qty / price * (1 - makerRebate)
        case LedgerOrder(_, _, price, qty, _, Sell, _, _, _, true)     => -qty / price * (1 + takerFee)
        case _                                                         =>  0.0
      }.sum

      val runningPandl2 = ledgerMetrics.runningPandl + pandlDelta
      val ledgerMetrics2 = ledgerMetrics.copy(
        metrics = metricsVals + ("data.pandl.pandl" -> runningPandl2) + ("data.pandl.delta" -> pandlDelta),
        lastOrderID = currOrders3.last.orderID,
        runningPandl = runningPandl2
      )
      copy(ledgerMetrics=ledgerMetrics2)
    }
  }
}

object Ledger {
  import com.typesafe.scalalogging.Logger
  private val log = Logger[Ledger]
}
