package moon

import moon.DataFreq._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import org.joda.time.DateTime

import scala.collection.immutable.ListMap


case class LedgerOrder(orderID: String, clOrdID: String=null, price: Double, qty: Double, ordStatus: OrderStatus, side: OrderSide, ordType: OrderType.Value=null, ordRejReason: Option[String]=None, timestamp: DateTime, myOrder: Boolean=false, relatedClOrdID: String=null, tier: Option[Int]) {
  lazy val fullOrdID = s"$orderID / $clOrdID"
}

case class LedgerMetrics(metrics: Map[String, Any]=Map.empty, runningPandl: Double=0)

case class Ledger(orderBookSummary: OrderBookSummary=null, tradeRollups: Rollups=Rollups(1),
                  ledgerOrdersByID: ListMap[String, LedgerOrder]=ListMap.empty,
                  ledgerOrdersByClOrdID: ListMap[String, LedgerOrder]=ListMap.empty,
                  ledgerMetrics: LedgerMetrics=LedgerMetrics()) {

  // lazy val `_10s` = tradeRollups.withForecast(`10s`)
  // lazy val `_1m`  = tradeRollups.withForecast(`1m`)
  // lazy val `_1h`  = tradeRollups.withForecast(`1h`)
  // lazy val `_4h`  = tradeRollups.withForecast(`4h`)

  @deprecated lazy val ledgerOrders = ledgerOrdersByID.values

  lazy val summary = s"Ledger: isMinimallyFilled: $isMinimallyFilled, myOrders: ${myOrders.size}, myBuyTrades: ${myBuyTrades.size}, mySellTrades: ${mySellTrades.length}, bidPrice: $bidPrice, askPrice: $askPrice"

  // rest
  def recordRest(data: RestModel): Ledger = data match {
    case os:Orders =>
      os.orders.foldLeft(this)((soFar, o) => soFar.recordRest(o))
    case hos:HealthCheckOrders =>
      Ledger.log.info(s"Updating with healthcheck: ${if (hos.orders.isEmpty) "<empty>" else hos.orders.mkString("\n")}")
      hos.orders.foldLeft(this)((soFar, o) => soFar.recordRest(o))
    case o: Order =>
      ledgerOrdersByID.get(o.orderID) match {
        case Some(existing) if existing.ordStatus == Filled || existing.ordStatus == Canceled =>
          // copy all except for ordStatus
          val clOrdID = o.clOrdID.getOrElse(existing.clOrdID)
          val existing2 = existing.copy(myOrder=true, clOrdID=clOrdID, price=o.avgPx.getOrElse(o.price.getOrElse(existing.price)), qty=o.orderQty, side=o.side, ordType=o.ordType, timestamp=o.timestamp, relatedClOrdID=o.relatedClOrdID.getOrElse(existing.relatedClOrdID), tier=o.tier.orElse(existing.tier))
          copy(
            ledgerOrdersByID = ledgerOrdersByID + (existing2.orderID -> existing2),
            ledgerOrdersByClOrdID = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2))
        case Some(existing) =>
          val existing2 = existing.copy(myOrder=true, clOrdID=o.clOrdID.getOrElse(existing.clOrdID), ordStatus=o.ordStatus.getOrElse(existing.ordStatus), price=o.avgPx.getOrElse(o.price.getOrElse(existing.price)), qty=o.orderQty, timestamp=o.timestamp, relatedClOrdID=o.relatedClOrdID.getOrElse(existing.relatedClOrdID), tier=o.tier.orElse(existing.tier))
          copy(
            ledgerOrdersByID = ledgerOrdersByID + (existing2.orderID -> existing2),
            ledgerOrdersByClOrdID = if (existing2.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID + (existing2.clOrdID -> existing2))
        case None =>
          val lo = LedgerOrder(orderID=o.orderID, clOrdID=o.clOrdID.orNull, qty=o.orderQty, price=o.avgPx.getOrElse(o.price.getOrElse(-1.0)), side=o.side, ordType=o.ordType, timestamp=o.timestamp, ordStatus=o.ordStatus.getOrElse(New), ordRejReason=o.ordRejReason, myOrder=true, relatedClOrdID=o.relatedClOrdID.orNull, tier=o.tier)
          copy(
            ledgerOrdersByID = ledgerOrdersByID + (lo.orderID -> lo),
            ledgerOrdersByClOrdID = if (lo.clOrdID == null) ledgerOrdersByClOrdID else ledgerOrdersByClOrdID +(lo.clOrdID -> lo))
      }
    case _ =>
      this
  }
  // ws
  def recordWs(data: WsModel): Ledger = data match {
    case o: UpsertOrder => {
      // if doesn't exist, insert new ledger order, else update the lifecycle (maybe more...)
      val (ledgerOrdersByID2, ledgerOrdersByClOrdID2) = o.data.foldLeft(ledgerOrdersByID, ledgerOrdersByClOrdID) {
        case ((lsById, lsByClOrdID), od) =>
          lsById.get(od.orderID) match {
            case Some(lo) =>
              val lo2 = (lo.ordStatus, od.ordStatus) match {
                case (Filled | Canceled, _) =>
                  lo.copy(relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID), tier=od.tier.orElse(lo.tier))    // ignore, already set, eg. by REST
                case (_, Some(s@New)) =>
                  lo.copy(
                    price=od.avgPx.getOrElse(od.price.getOrElse(lo.price)),
                    qty=od.orderQty.getOrElse(lo.qty),
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType),
                    relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID),
                    tier=od.tier.orElse(lo.tier)
                  )
                case (_, Some(s@Canceled)) =>
                  lo.copy(
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType),
                    relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID),
                    tier=od.tier.orElse(lo.tier)
                  )
                case (_, Some(s@Rejected)) =>
                  lo.copy(
                    ordStatus=s,
                    ordRejReason=od.ordRejReason,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType),
                    relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID),
                    tier=od.tier.orElse(lo.tier)
                  )
                case (_, Some(s@PostOnlyFailure)) =>
                  lo.copy(
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType),
                    relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID),
                    tier=od.tier.orElse(lo.tier)
                  )
                case (_, Some(s@PartiallyFilled)) =>
                  lo.copy(
                    ordStatus=s,
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType),
                    relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID),
                    tier=od.tier.orElse(lo.tier)
                  )
                case (_, Some(s@Filled)) =>
                  lo.copy(
                    ordStatus=s,
                    qty=od.cumQty.getOrElse(lo.qty),
                    price=od.avgPx.getOrElse(od.price.getOrElse(lo.price)),
                    timestamp=od.timestamp,
                    ordType=od.ordType.getOrElse(lo.ordType),
                    relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID),
                    tier=od.tier.orElse(lo.tier)
                )
                case _ => lo.copy(relatedClOrdID=od.relatedClOrdID.getOrElse(lo.relatedClOrdID), tier=od.tier.orElse(lo.tier))
              }
              val lsByClOrdID2 = if (lo2.clOrdID == null) lsByClOrdID else lsByClOrdID + (lo2.clOrdID -> lo2)
              (lsById + (lo2.orderID -> lo2), lsByClOrdID2)
            case None =>
              // expecting REST to fill in the initial order...
              val lo = LedgerOrder(orderID=od.orderID, clOrdID=od.clOrdID.orNull, price=od.avgPx.getOrElse(od.avgPx.getOrElse(od.price.getOrElse(-1))), qty=od.cumQty.getOrElse(od.orderQty.getOrElse(0.0)), side=od.side.orNull, ordType=od.ordType.orNull, timestamp=od.timestamp, ordStatus=od.ordStatus.getOrElse(New), ordRejReason=od.ordRejReason, relatedClOrdID=od.relatedClOrdID.orNull, tier=od.tier)
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
  lazy val myBuyTrades: Seq[LedgerOrder] = myTrades.filter(_.side == Buy)
  lazy val mySellTrades: Seq[LedgerOrder] = myTrades.filter(_.side == Sell)
  lazy val isMinimallyFilled: Boolean = orderBookSummary != null
  lazy val bidPrice: Double = orderBookSummary.bid
  lazy val askPrice: Double = orderBookSummary.ask

  def withMetrics(makerRebate: Double=.00025, takerFee: Double=.00075, strategy: Strategy): Ledger = {
    val currPrice = (bidPrice + askPrice)/2
    val volume = tradeRollups.withForecast(`1m`).forecast.volume.lastOption.getOrElse(0)
    val strategyRes = strategy.strategize(this)
    val (s, m) = (strategyRes.sentiment, strategyRes.metrics)
    val myBuyTradesCnt = myBuyTrades.size
    val mySellTradesCnt = mySellTrades.size
    val buyLegClOrdIDs = mySellTrades.map(_.relatedClOrdID)
    val (matchedBuys, unmatchedBuys) = myBuyTrades.partition(o => buyLegClOrdIDs.contains(o.clOrdID))
    val tradePairs = mySellTrades ++ matchedBuys
    val runningPandl = tradePairs.map {
      case LedgerOrder(_, _, price, qty, _, Buy, Limit, _, _, true, _, _)  =>  qty / price * (1 + makerRebate)
      case LedgerOrder(_, _, price, qty, _, Buy, _, _, _, true, _, _)      =>  qty / price * (1 - takerFee)
      case LedgerOrder(_, _, price, qty, _, Sell, Limit, _, _, true, _, _) => -qty / price * (1 - makerRebate)
      case LedgerOrder(_, _, price, qty, _, Sell, _, _, _, true, _, _)     => -qty / price * (1 + takerFee)
      case _                                                               =>  0.0
    }.sum
    val positionOpen = unmatchedBuys.map(o => o.qty / o.price).sum
    val metricsVals = Map(
      "data.price"             -> currPrice,
      "data.volume"            -> volume,
      "data.sentiment"         -> s.id,
      "data.myTradeCnt"        -> myTrades.size,
      "data.myBuySellTradeCnt" -> (myBuyTradesCnt + mySellTradesCnt),
      "data.myBuyTradeCnt"     -> myBuyTradesCnt,
      "data.mySellTradeCnt"    -> mySellTradesCnt,
      "data.tier.depth"        -> unmatchedBuys.size, // aka how many open tiers have we right now
      "data.tier.positionOpen" -> positionOpen,
      "data.pandl.pandl"       -> runningPandl,
      "data.pandl.delta"       -> (runningPandl - ledgerMetrics.runningPandl),
    ) ++ m
    copy(ledgerMetrics=ledgerMetrics.copy(metrics = metricsVals, runningPandl = runningPandl))
  }
}

object Ledger {
  import com.typesafe.scalalogging.Logger
  private val log = Logger[Ledger]
}
