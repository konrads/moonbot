package moon

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import moon.OrderSide._
import moon.OrderStatus.{Canceled, Filled, New}
import moon.OrderType.{Limit, Market, Stop}
import org.joda.time.DateTime

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.Success


object Behaviour {
  def asLiveBehavior[T <: LedgerAwareCtx](restGateway: IRestGateway, metrics: Option[Metrics]=None, symbol: String, namespace: String, behaviorDsl: (T, ActorEvent, org.slf4j.Logger, String) => (T, Option[SideEffect]), initCtx: T, setup: => Unit = ())(implicit execCtx: ExecutionContext): Behavior[ActorEvent] = {
    Behaviors.withTimers { timers =>
      // NOTE: setting up timers externally, to ensure delay starts from start of minute/hour
      // timers.startTimerAtFixedRate(On30s(None), 30.seconds)
      // timers.startTimerAtFixedRate(On1m(None),  1.minute)

      Behaviors.setup { actorCtx =>
        setup

        def loop(ctx: T): Behavior[ActorEvent] =
          Behaviors.receiveMessage { event =>
            val (ctx2, effect) = behaviorDsl(ctx, event, actorCtx.log, symbol)
            effect.foreach {
              case HealthCheck =>
                val fut = restGateway.getOrdersAsync(symbol, Some("open"))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case PublishMetrics(gauges, now) =>
                metrics.foreach(_.gauge(namespace, gauges, now))
              case CancelOrder(clOrdID) =>
                val fut = restGateway.cancelOrderAsync(clOrdIDs = Seq(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case AmendOrder(clOrdID, price, qty) =>
                val fut = restGateway.amendOrderAsync(origClOrdID = Some(clOrdID), price = price, qty = qty)
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case o@OpenInitOrder(symbol, side, Limit, clOrdID, qty, price) =>
                val fut = restGateway.placeLimitOrderAsync(symbol=symbol, qty=qty, price=price.get, side=side, isReduceOnly=false, clOrdID=Some(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case o@OpenInitOrder(symbol, side, Market, clOrdID, qty, price) =>
                val fut = restGateway.placeMarketOrderAsync(symbol=symbol, qty=qty, side=side, clOrdID=Some(clOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
              case x:OpenInitOrder =>
                throw new Exception(s"Do not cater for non Limit/Market order: $x")
              case OpenTakeProfitOrder(symbol, side, qty, takeProfitClOrdID, takeProfitLimit) =>
                val fut = restGateway.placeLimitOrderAsync(symbol, qty, takeProfitLimit, true, side, clOrdID = Some(takeProfitClOrdID))
                fut onComplete (res => actorCtx.self ! RestEvent(res))
            }
            loop(ctx2)
          }
        loop(initCtx)
      }
    }
  }

  def asDryBehavior[T <: LedgerAwareCtx](metrics: Option[Metrics]=None, symbol: String, namespace: String, behaviorDsl: (T, ActorEvent, org.slf4j.Logger, String) => (T, Option[SideEffect]), initCtx: T, askBidFromTrades: Boolean): Behavior[ActorEvent] = {
    Behaviors.withTimers { timers =>
      // NOTE: setting up timers externally, to ensure delay starts from start of minute/hour
      // timers.startTimerAtFixedRate(On30s(None), 30.seconds)
      // timers.startTimerAtFixedRate(On1m(None),  1.minute)

      Behaviors.setup { actorCtx =>
        def loop(ctx: T, exchangeCtx: ExchangeCtx): Behavior[ActorEvent] =
          Behaviors.receiveMessage { event =>
            val (ctx2, exchangeCtx2) = paperExchangeSideEffectHandler(behaviorDsl, ctx, exchangeCtx, metrics, symbol, namespace, actorCtx.log, false, askBidFromTrades, event)
            loop(ctx2, exchangeCtx2)
          }

        loop(initCtx, ExchangeCtx())
      }
    }
  }

  case class ExchangeOrder(symbol: String, orderID: String, clOrdID: String, qty: Double, side: OrderSide.Value, ordType: OrderType.Value, status: OrderStatus.Value, price: Option[Double]=None, trailingPeg: Option[Double]=None, longHigh: Option[Double]=None, shortLow: Option[Double]=None, timestamp: DateTime) {
    def toRest: Order = Order(orderID=orderID, clOrdID=Some(clOrdID), symbol="...", timestamp=timestamp, ordType=ordType, ordStatus=Some(status), side=side, orderQty=qty, price=price)
    def toWs: OrderData = OrderData(symbol=symbol, orderID=orderID, clOrdID=Some(clOrdID), timestamp=timestamp, ordType=Some(ordType), ordStatus=Some(status), side=Some(side), orderQty=Some(qty), price=price)
  }
  case class ExchangeCtx(orders: Map[String, ExchangeOrder]=Map.empty, bid: Double=0, ask: Double=0, next1mTs: Long=0, next5mTs: Long=0, next30sTs: Long=0, lastTs: Long=0)

  /**
   * Getting bit complex, need to model better... Imagine:
   * - trade X @ price 10k arrives at exchange
   * - 10k triggers a limit fill of my order L - not relevant for market...
   * - trade X's timestamp triggers On1h/On1m
   *   - as a result client might:
   *     - publish metrics, use pre-X prices
   *     - re-strategize
   *       - might issue market order Y, should fill with pre-X's price?
   *       - exchange does *NOT* issue a trade on Y (hence not re-recording price of Y)
   * - client records trade X
   *
   * - I expect:
   *   - exchange generate tsEvents: On1m/On1h
   *     - loop client: events <-> exchange_monitor_fills: effects - till empty
   *   - exchange deal with trade X's price, generate fillEvents:
   *     - loop client: events <-> exchange_monitor_fills: effects - till empty
   */
  def paperExchangeSideEffectHandler[T <: LedgerAwareCtx](behaviorDsl: (T, ActorEvent, org.slf4j.Logger, String) => (T, Option[SideEffect]), ctx: T, eCtx: ExchangeCtx, metrics: Option[Metrics], symbol: String, namespace: String, log: org.slf4j.Logger, triggerMetrics: Boolean, askBidFromTrades: Boolean, event: ActorEvent): (T, ExchangeCtx) = {
    @tailrec def handleEventsAndDownstreamEffects(ctx: T, eCtx: ExchangeCtx, events: ActorEvent*): (T, ExchangeCtx) = {
      // get effects for events, loop through each, generating newEvents
      if (events.isEmpty)
        (ctx, eCtx)
      else {
        val (ctx2, effects) = events.foldLeft((ctx, Vector.empty[SideEffect])) {
          case ((ctx_, effs_), ev) =>
            val (ctx2_, effOpt) = behaviorDsl(ctx_, ev, log, symbol)
            (ctx2_, effs_ ++ effOpt.toSeq)
        }
        if (effects.isEmpty)
          (ctx2, eCtx)
        else {
          val (eCtx2, events2) = effects.foldLeft((eCtx, Vector.empty[ActorEvent])) {
            case ((eCtx_, evs_), ef) =>
              val (eCtx2_, evs) = paperExchangePostHandler(eCtx_, ef, metrics, namespace)
              (eCtx2_, evs_ ++ evs)
          }
          handleEventsAndDownstreamEffects(ctx2, eCtx2, events2:_*)
        }
      }
    }

    val (eCtx2, tsEvents) = paperExchangeTsHandler(eCtx, event, triggerMetrics)
    val (ctx3, eCtx3) = handleEventsAndDownstreamEffects(ctx, eCtx2, tsEvents:_*)
    val (eCtx4, fillEvents) = paperExchangeFillHandler(eCtx3, event, log, askBidFromTrades)
    val (ctx5, eCtx5) = handleEventsAndDownstreamEffects(ctx3, eCtx4, fillEvents:_*)
    val (ctx6, eCtx6) = handleEventsAndDownstreamEffects(ctx5, eCtx5, event)
    (ctx6, eCtx6)
  }

  def paperExchangeTsHandler(exchangeCtx: ExchangeCtx, event: ActorEvent, triggerTimers: Boolean): (ExchangeCtx, Seq[ActorEvent]) = {
    // handle timestamp based events, ie. On1m, On30s
    val timestampMsOpt = (event match {
      case WsEvent(x:Trade)            => Some(x.data.head.timestamp)
      case WsEvent(x:OrderBookSummary) => Some(x.timestamp)
      case WsEvent(x:OrderBook)        => Some(x.data.head.timestamp)
      case WsEvent(x:Info)             => Some(x.timestamp)
      case WsEvent(x:Funding)          => Some(x.data.head.timestamp)
      case WsEvent(x:UpsertOrder)      => Some(x.data.head.timestamp)
      case _                           => None
    }).map(_.getMillis)

    timestampMsOpt match {
      case Some(ts) =>
        if (triggerTimers && exchangeCtx.next30sTs <= 0) {
          val init30sTs = ts/30000 * 30000
          val init1mTs = ts/60000 * 60000
          val init5mTs = ts/(5*60000) * (5*60000)
          (exchangeCtx.copy(next30sTs = init30sTs + 30000, next1mTs = init1mTs + 60000, next5mTs = init5mTs + 5*60000, lastTs = ts), Nil)
        } else if (triggerTimers && ts >= exchangeCtx.next5mTs)
          (exchangeCtx.copy(next30sTs = exchangeCtx.next5mTs + 30000, next1mTs = exchangeCtx.next5mTs + 60000, next5mTs = exchangeCtx.next5mTs + 5*60000, lastTs = ts), Seq(On5m(Some(exchangeCtx.next5mTs)), On30s(Some(exchangeCtx.next30sTs)), On1m(Some(exchangeCtx.next1mTs))))
        else if (triggerTimers && ts >= exchangeCtx.next1mTs)
          (exchangeCtx.copy(next30sTs = exchangeCtx.next1mTs + 30000, next1mTs = exchangeCtx.next1mTs + 60000, lastTs = ts), Seq(On30s(Some(exchangeCtx.next30sTs)), On1m(Some(exchangeCtx.next1mTs))))
        else if (triggerTimers && ts >= exchangeCtx.next30sTs)
          (exchangeCtx.copy(next30sTs = exchangeCtx.next30sTs + 30000, lastTs = ts), Seq(On30s(Some(exchangeCtx.next30sTs))))
        else
          (exchangeCtx.copy(lastTs=ts), Nil)
      case _ =>
        (exchangeCtx, Nil)
    }
  }

  def paperExchangeFillHandler(eCtx: ExchangeCtx, event: ActorEvent, log: org.slf4j.Logger, askBidFromTrade: Boolean): (ExchangeCtx, Seq[ActorEvent]) = {
    val (askOpt, bidOpt) = event match {
      case WsEvent(x:OrderBookSummary)         => (Some(x.ask), Some(x.bid))
      case WsEvent(x:OrderBook)                => (Some(x.summary.ask), Some(x.summary.bid))
      case WsEvent(x:Trade) if askBidFromTrade => (Some(x.data.head.price), Some(x.data.head.price))
      case _                                   => (None, None)
    }
    // update trailing highs/lows
    val eCtx2 = (askOpt, bidOpt) match {
      case (Some(ask), Some(bid)) =>
        if (log.isDebugEnabled) log.debug(s"paperExch:: updates to ask: ${eCtx.ask} -> $ask, bid: ${eCtx.bid} -> $bid")
        val orders2 = eCtx.orders map {
          case (k, v:ExchangeOrder) if v.longHigh.exists(bid > _) => k -> v.copy(longHigh=Some(bid))
          case (k, v:ExchangeOrder) if v.shortLow.exists(ask < _) => k -> v.copy(shortLow=Some(ask))
          case kv => kv
        }
        eCtx.copy(orders=orders2)
      case _ => eCtx
    }
    val (eCtx3, filledEvents) = (askOpt, bidOpt) match {
      case (Some(ask), Some(bid)) =>
        val filledOrders = eCtx2.orders.values.map(o => maybeFill(o, ask, bid)).collect { case Some(o) => o }
        if (filledOrders.isEmpty)
          (eCtx2.copy(ask=ask, bid=bid), Nil)
        else {
          log.info(s"paperExch:: Filled orders: ${filledOrders.map(o => s"${o.clOrdID} @ ${o.price.get} : ${formatDateTime(o.timestamp)}").mkString(", ")}")
          val wsOrders = filledOrders.map(_.toWs)
          val eCtx4 = eCtx2.copy(ask=ask, bid=bid, orders=eCtx2.orders ++ filledOrders.map(x => x.clOrdID -> x))
          (eCtx4, Seq(WsEvent(UpsertOrder(Some("update"), wsOrders.toSeq))))
        }
      case _ => (eCtx2, Nil)
    }
    (eCtx3, filledEvents)
  }

  def paperExchangePostHandler(exchangeCtx: ExchangeCtx, effect: SideEffect, metrics: Option[Metrics], namespace: String): (ExchangeCtx, Seq[ActorEvent]) = {
    effect match {
      case HealthCheck =>
        // noop
        (exchangeCtx, Nil)
      case PublishMetrics(gauges, now) =>
        metrics.foreach(_.gauge(namespace, gauges, now))
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
      case AmendOrder(clOrdID, price, qty) =>
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders.map {
          case (k, v) if k == clOrdID && v.status != Canceled && v.status != Filled =>
            val v2 = v.copy(price=Some(price), qty=qty)
            val v3 = maybeFill(v2, exchangeCtx.ask, exchangeCtx.bid).getOrElse(v2)
            k -> v3
          case other => other
        })
        val event = RestEvent(Success(exchangeCtx2.orders(clOrdID).toRest))
        (exchangeCtx2, Seq(event))
      case org@OpenInitOrder(symbol, side, ordType, clOrdID, qty, price) =>
        val o = ExchangeOrder(symbol=symbol, orderID=uuid, clOrdID=clOrdID, qty=qty, price=price, side=side, status=New, ordType=ordType, timestamp=new DateTime(exchangeCtx.lastTs))
        val o2 = maybeFill(o, exchangeCtx.ask, exchangeCtx.bid).getOrElse(o)
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders + (clOrdID -> o2))
        val event = RestEvent(Success(o2.toRest))
        (exchangeCtx2, Seq(event))
      case OpenTakeProfitOrder(symbol, side, qty, takeProfitClOrdID, takeProfitLimit) =>
        val to = ExchangeOrder(symbol=symbol, orderID=uuid, clOrdID=takeProfitClOrdID, qty=qty, price=Some(takeProfitLimit), side=side, status=New, ordType=Limit, timestamp=new DateTime(exchangeCtx.lastTs))
        val exchangeCtx2 = exchangeCtx.copy(orders = exchangeCtx.orders ++ Map(to.clOrdID -> to))
        val event = RestEvent(Success(Orders(Seq(to.toRest))))
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
