package moon

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import moon.OrderSide._
import moon.OrderStatus.{Canceled, Filled, New}
import moon.OrderType.{Limit, Market, Stop}
import org.joda.time.DateTime

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success


object Behaviour {
  def asLiveBehavior[T <: LedgerAwareCtx](restGateway: IRestGateway, metrics: Option[Metrics]=None, flushSessionOnRestart: Boolean=true, behaviorDsl: (T, ActorEvent, org.slf4j.Logger) => (T, Option[SideEffect]), initCtx: T)(implicit execCtx: ExecutionContext): Behavior[ActorEvent] = {
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(SendMetrics(None), 1.minute)

      Behaviors.setup { actorCtx =>
        if (flushSessionOnRestart) {
          actorCtx.log.info("init: Bootstrapping via closePosition/orderCancels...")
          for {
            _ <- restGateway.closePositionAsync()
            _ <- restGateway.cancelAllOrdersAsync()
          } yield ()
        }

        def loop(ctx: T): Behavior[ActorEvent] =
          Behaviors.receiveMessage { event =>
            val (ctx2, effect) = behaviorDsl(ctx, event, actorCtx.log)
            effect.foreach {
              case PublishMetrics(gauges, now) =>
                metrics.foreach(_.gauge(gauges, now))
              case CancelOrder(clOrdID) =>
                val fut = restGateway.cancelOrderAsync(clOrdIDs = Seq(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case AmendOrder(clOrdID, price) =>
                val fut = restGateway.amendOrderAsync(origClOrdID = Some(clOrdID), price = price)
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case OpenInitOrder(side, Limit, clOrdID, qty, price) =>
                val fut = restGateway.placeLimitOrderAsync(qty=qty, price=price.get, side=side, isReduceOnly=false, clOrdID=Some(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case OpenInitOrder(side, Market, clOrdID, qty, price) =>
                val fut = restGateway.placeMarketOrderAsync(qty=qty, side=side, clOrdID=Some(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case x:OpenInitOrder =>
                throw new Exception(s"Do not cater for non Limit/Market order: $x")
              case OpenTakeProfitStoplossOrders(side, qty, takeProfitClOrdID, takeProfitLimit, stoplossClOrdID, stoplossMarginOpt, stoplossPegOpt) =>
                val fut = restGateway.placeBulkOrdersAsync(OrderReqs(
                  Seq(OrderReq.asLimitOrder(side, qty, takeProfitLimit, true, clOrdID = Some(takeProfitClOrdID))) ++
                    stoplossMarginOpt.map(stoplossMargin => OrderReq.asStopOrder(side, qty, stoplossMargin, true, clOrdID = Some(stoplossClOrdID))).toSeq ++
                    stoplossPegOpt.map(stoplossPeg => OrderReq.asTrailingStopOrder(side, qty, stoplossPeg, true, clOrdID = Some(stoplossClOrdID))).toSeq
                ))
                fut onComplete (res => actorCtx.self ! RestEvent(res))

            }
            loop(ctx2)
          }
        loop(initCtx)
      }
    }
  }

  def asDryBehavior[T <: LedgerAwareCtx](metrics: Option[Metrics]=None, behaviorDsl: (T, ActorEvent, org.slf4j.Logger) => (T, Option[SideEffect]), initCtx: T): Behavior[ActorEvent] = {
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(SendMetrics(None), 1.minute)

      Behaviors.setup { actorCtx =>
        def loop(ctx: T, exchangeCtx: ExchangeCtx): Behavior[ActorEvent] =
          Behaviors.receiveMessage { event =>
            val (ctx2, exchangeCtx2) = paperExchangeSideEffectHandler(behaviorDsl, ctx, exchangeCtx, metrics, actorCtx.log, false, event)
            loop(ctx2, exchangeCtx2)
          }

        loop(initCtx, ExchangeCtx())
      }
    }
  }

  case class ExchangeOrder(orderID: String, clOrdID: String, qty: Double, side: OrderSide.Value, ordType: OrderType.Value, status: OrderStatus.Value, price: Option[Double]=None, trailingPeg: Option[Double]=None, longHigh: Option[Double]=None, shortLow: Option[Double]=None, timestamp: DateTime) {
    def toRest: Order = Order(orderID=orderID, clOrdID=Some(clOrdID), symbol="...", timestamp=timestamp, ordType=ordType, ordStatus=Some(status), side=side, orderQty=qty, price=price)
    def toWs: OrderData = OrderData(orderID=orderID, clOrdID=Some(clOrdID), timestamp=timestamp, ordType=Some(ordType), ordStatus=Some(status), side=Some(side), orderQty=Some(qty), price=price)
  }
  case class ExchangeCtx(orders: Map[String, ExchangeOrder]=Map.empty, bid: Double=0, ask: Double=0, nextMetricsTs: Long=0, lastTs: Long=0)

  @tailrec def paperExchangeSideEffectHandler[T <: LedgerAwareCtx](behaviorDsl: (T, ActorEvent, org.slf4j.Logger) => (T, Option[SideEffect]), ctx: T, exchangeCtx: ExchangeCtx, metrics: Option[Metrics], log: org.slf4j.Logger, triggerMetrics: Boolean, events: ActorEvent*): (T, ExchangeCtx) = {
    val ev +: evs = events
    val (eCtx, preEvents) = paperExchangePreHandler(exchangeCtx, ev, log, triggerMetrics)
    if (preEvents.nonEmpty) log.debug(s"paperExch:: adding preEvents: ${preEvents.mkString(", ")}")
    val (ctx2, effects) = behaviorDsl(ctx, ev, log)
    if (log.isDebugEnabled) log.debug(s"paperExch:: handling event $ev, result ctx2: ${ctx2.getClass.getSimpleName}")
    if (effects.nonEmpty) log.debug(s"paperExch:: adding effects: ${effects.mkString(", ")}")
    val (eCtx2, postEvents) = effects.foldLeft((eCtx, Seq.empty[ActorEvent])) {
      case ((eCtx, pEvs), eff) =>
        val (eCtx2, evs) = paperExchangePostHandler(eCtx, eff, metrics, log)
        (eCtx2, pEvs ++ evs)
    }
    if (postEvents.nonEmpty) log.debug(s"paperExch:: adding postEvents: ${postEvents.mkString(", ")}")
    val events2 =  evs ++ preEvents ++ postEvents
    if (events2.isEmpty)
      (ctx2, eCtx2)
    else
      paperExchangeSideEffectHandler(behaviorDsl, ctx2, eCtx2, metrics, log, triggerMetrics, events2:_*)
  }

  def paperExchangePreHandler(exchangeCtx: ExchangeCtx, event: ActorEvent, log: org.slf4j.Logger, triggerMetrics: Boolean): (ExchangeCtx, Seq[ActorEvent]) = {
    // handle timestamp based events, ie. SendMetrics
    val timestampMsOpt = (event match {
      case WsEvent(x:Trade)            => Some(x.data.head.timestamp)
      case WsEvent(x:OrderBookSummary) => Some(x.timestamp)
      case WsEvent(x:OrderBook)        => Some(x.data.head.timestamp)
      case WsEvent(x:Info)             => Some(x.timestamp)
      case WsEvent(x:Funding)          => Some(x.data.head.timestamp)
      case WsEvent(x:UpsertOrder)      => Some(x.data.head.timestamp)
      case _                           => None
    }).map(_.getMillis)

    val (exchangeCtx2, tsEvents) = timestampMsOpt match {
      case Some(ts) =>
        if (triggerMetrics && ts > exchangeCtx.nextMetricsTs) {
          val currMetricsTs = if (exchangeCtx.nextMetricsTs > 0)
            exchangeCtx.nextMetricsTs
          else
            ts
          (exchangeCtx.copy(nextMetricsTs = currMetricsTs + 60000, lastTs = ts), Seq(SendMetrics(Some(currMetricsTs))))
        } else
          (exchangeCtx.copy(lastTs=ts), Nil)
      case _ =>
        (exchangeCtx, Nil)
    }

    // handle price based events, ie. fills
    val (askOpt, bidOpt) = event match {
      case WsEvent(x:OrderBookSummary) => (Some(x.ask), Some(x.bid))
      case WsEvent(x:OrderBook)        => (Some(x.summary.ask), Some(x.summary.bid))
      case _                           => (None, None)
    }
    // update trailing highs/lows
    val exchangeCtx3 = (askOpt, bidOpt) match {
      case (Some(ask), Some(bid)) =>
        if (log.isDebugEnabled) log.debug(s"paperExch:: updates to ask: ${exchangeCtx2.ask} -> $ask, bid: ${exchangeCtx2.bid} -> $bid")
        val orders2 = exchangeCtx2.orders map {
          case (k, v:ExchangeOrder) if v.longHigh.exists(bid > _) => k -> v.copy(longHigh=Some(bid))
          case (k, v:ExchangeOrder) if v.shortLow.exists(ask < _) => k -> v.copy(shortLow=Some(ask))
          case kv => kv
        }
        exchangeCtx2.copy(orders=orders2)
      case _ => exchangeCtx2
    }
    val (exchangeCtx4, filledEvents) = (askOpt, bidOpt) match {
      case (Some(ask), Some(bid)) =>
        val filledOrders = exchangeCtx3.orders.values.map(o => maybeFill(o, ask, bid)).collect { case Some(o) => o }
        if (filledOrders.isEmpty)
          (exchangeCtx3.copy(ask=ask, bid=bid), Nil)
        else {
          log.info(s"paperExch:: Filled orders: ${filledOrders.map(o => s"${o.clOrdID} @ ${o.price.get} : ${formatDateTime(o.timestamp)}").mkString(", ")}")
          val wsOrders = filledOrders.map(_.toWs)
          val exchangeCtx4 = exchangeCtx3.copy(ask=ask, bid=bid, orders=exchangeCtx3.orders ++ filledOrders.map(x => x.clOrdID -> x))
          (exchangeCtx4, Seq(WsEvent(UpsertOrder(Some("update"), wsOrders.toSeq))))
        }
      case _ => (exchangeCtx3, Nil)
    }
    (exchangeCtx4, tsEvents ++ filledEvents)
  }

  def paperExchangePostHandler(exchangeCtx: ExchangeCtx, effect: SideEffect, metrics: Option[Metrics], log: org.slf4j.Logger): (ExchangeCtx, Seq[ActorEvent]) = {
    effect match {
      case PublishMetrics(gauges, now) =>
        metrics.foreach(_.gauge(gauges, now))
        (exchangeCtx, Nil)
      case CancelOrder(clOrdID) =>
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders.map {
          case (k, v) if k == clOrdID && v.status != Canceled && v.status != Filled =>
            val v2 = v.copy(status=Canceled)
            k -> v2
          case other => other
        })
        val event = RestEvent(Success(Orders(Seq(exchangeCtx2.orders(clOrdID).toRest))))
        (exchangeCtx2, Seq(event))
      case AmendOrder(clOrdID, price) =>
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders.map {
          case (k, v) if k == clOrdID && v.status != Canceled && v.status != Filled =>
            val v2 = v.copy(price=Some(price))
            val v3 = maybeFill(v2, exchangeCtx.ask, exchangeCtx.bid).getOrElse(v2)
            k -> v3
          case other => other
        })
        val event = RestEvent(Success(exchangeCtx2.orders(clOrdID).toRest))
        (exchangeCtx2, Seq(event))
      case OpenInitOrder(side, ordType, clOrdID, qty, price) =>
        val o = ExchangeOrder(orderID=uuid, clOrdID=clOrdID, qty=qty, price=price, side=side, status=New, ordType=ordType, timestamp=new DateTime(exchangeCtx.lastTs))
        val o2 = maybeFill(o, exchangeCtx.ask, exchangeCtx.bid).getOrElse(o)
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders + (clOrdID -> o2))
        val event = RestEvent(Success(o2.toRest))
        (exchangeCtx2, Seq(event))
      case OpenTakeProfitStoplossOrders(side, qty, takeProfitClOrdID, takeProfitLimit, stoplossClOrdID, stoplossMargin, stoplossPeg) =>
        val to = ExchangeOrder(orderID=uuid, clOrdID=takeProfitClOrdID, qty=qty, price=Some(takeProfitLimit), side=side, status=New, ordType=Limit, timestamp=new DateTime(exchangeCtx.lastTs))
        val so = ExchangeOrder(orderID=uuid, clOrdID=stoplossClOrdID, qty=qty, price=stoplossMargin, side=side, status=New, ordType=Stop, timestamp=new DateTime(exchangeCtx.lastTs),
          longHigh=if (stoplossPeg.isDefined && side == Sell) Some(exchangeCtx.bid) else None,
          shortLow=if (stoplossPeg.isDefined && side == Buy)  Some(exchangeCtx.ask) else None,
          trailingPeg=stoplossPeg.map(math.abs)
        )
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders ++ Map(to.clOrdID -> to, so.clOrdID -> so))
        val event = RestEvent(Success(Orders(Seq(to.toRest, so.toRest))))
        (exchangeCtx2, Seq(event))
    }
  }

  def maybeFill(o: ExchangeOrder, ask: Double, bid: Double): Option[ExchangeOrder] =
  // if filled/canceled - noop
    if (o.status == Filled || o.status == Canceled)
      None
    // market - fill at current ask/bid
    else if (o.ordType == Market && o.side == Buy)
      Some(o.copy(price=Some(ask), status=Filled))
    else if (o.ordType == Market && o.side == Sell)
      Some(o.copy(price=Some(bid), status=Filled))
    // trailing stoploss
    else if (o.shortLow.isDefined && o.trailingPeg.isDefined && o.shortLow.get + o.trailingPeg.get <= ask) // buy
    Some(o.copy(status=Filled, price=Some(o.shortLow.get + o.trailingPeg.get)))
      else if (o.longHigh.isDefined && o.trailingPeg.isDefined && o.longHigh.get - o.trailingPeg.get >= bid) // sell
    Some(o.copy(status=Filled, price=Some(o.longHigh.get - o.trailingPeg.get)))
      // vanilla stoploss
      else if (o.ordType == Stop && o.side == Buy && o.price.exists(_ <= ask))
      Some(o.copy(status=Filled))
    else if (o.ordType == Stop && o.side == Sell && o.price.exists(_ >= bid))
      Some(o.copy(status=Filled))
    // limit
    else if (o.ordType == Limit && o.side == Buy && o.price.exists(_ >= ask))
      Some(o.copy(status=Filled))
    else if (o.ordType == Limit && o.side == Sell && o.price.exists(_ <= bid))
      Some(o.copy(status=Filled))
    else
      None
}


case class IrrecoverableError(msg: String, cause: Throwable) extends Exception(msg, cause)
case class ExternalCancelError(msg: String) extends Exception(msg)
case class OrderRejectedError(msg: String) extends Exception(msg)
