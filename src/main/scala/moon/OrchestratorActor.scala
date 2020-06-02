package moon

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

import scala.collection.Set
import scala.concurrent.duration.{Duration, MILLISECONDS, _}
import scala.util.{Failure, Success, Try}
import moon.TradeLifecycle._
import moon.OrderStatus._


// FIXME: need to deal with partial fills (via WS), on both opening and closing position... initially may give it enough margin and not care?
// {"table":"order","action":"update","data":[{"orderID":"50c037f7-e511-a595-626a-21bc8a0ff30d","ordStatus":"PartiallyFilled","leavesQty":20,"cumQty":10,"avgPx":9419,"timestamp":"2020-05-29T01:24:53.824Z","clOrdID":"","account":299045,"symbol":"XBTUSD"}]}
// FIXME: need to amend order not cancel & reissue. Need to be aware of eg. sentiment to either: noop, keep amending, or cancel and back to idle


object OrchestratorActor {
  trait PositionOpener {
    val desc: String
    def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent]             // desired outcome - order filled
    def onChangeOfHeart(l: Ledger): Behavior[ActorEvent]                            // once we decide to stop opening (via shouldKeepGoing())
    def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent]           // unexpected cancel (not from bot)
    def onRejection(l: Ledger, orderID: String, rejectionReason: Option[String]): Behavior[ActorEvent]  // unexpected rejection
    def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent]  // coding error???
    def bestPrice(l: Ledger): BigDecimal
    def shouldKeepGoing(l: Ledger): Boolean
    def openOrder(l: Ledger): Try[Order]                                             // how to open order
    def cancelOrder(orderID: String): Try[Orders]                                    // how to cancel order
    def amendOrder(orderID: String, newPrice: BigDecimal): Try[Order]               // how to open order
  }

  trait PositionCloser {
    val desc: String
    def onDone(l: Ledger): Behavior[ActorEvent]                                      // takeProfit or stoploss filled
    def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent]    // unexpected cancel (not from bot)
    def onRejections(l: Ledger, orderIDsRejections: Seq[(String, OrderStatus.Value, Option[String])]): Behavior[ActorEvent]  // unexpected rejection
    def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent]  // coding error???
    def openOrders(l: Ledger): Try[Orders]                                           // how to open orders
    def cancelOrders(orderIDs: Seq[String]): Try[Orders]                             // how to cancel orders
    def backoffStrategy(retry: Int): Int = Array(0, 100, 200, 500, 1000, 2000, 5000, 1000)(math.max(retry, 7))
  }

  def openPosition(initLedger: Ledger, positionOpener: PositionOpener, metrics: Option[Metrics]=None)(implicit log: Logger): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] { case (actorCtx, wsEvent) =>
          (ctx, wsEvent) match {
            case (_, SendMetrics) =>
              val ledger2 = ctx.ledger.withMetrics()
              metrics.foreach(_.gauge(ledger2.ledgerMetrics.map(_.metrics).getOrElse(Map.empty)))
              loop(ctx.copy(ledger2))
            case (_, Issue) => // re-issue due to PostOnly err
              positionOpener.openOrder(ctx.ledger) match {
                case Success(o) =>
                  val ledger2 = ctx.ledger.record(o)
                  loop(ctx.copy(ledger=ledger2))
//                  val order = ledger2.ledgerOrdersById(o.orderID)
//                  order.ordStatus match {
//                    case New =>
//                      loop(ctx.copy(ledger=ledger2, orderID=order.orderID, orderPrice=order.price, lifecycle=Issuing))
//                    case Filled =>
//                      actorCtx.log.info(s"${positionOpener.desc}: Filled straight away! orderID: ${order.orderID}")
//                      positionOpener.onFilled(ledger2, openPrice=order.price)
//                    case PostOnlyFailure =>
//                      actorCtx.log.info(s"${positionOpener.desc}: PostOnlyFailure on orderID: ${order.orderID}, need to re-issue")
//                      actorCtx.self ! Issue
//                      loop(ctx.copy(ledger=ledger2, orderID=null, orderPrice=null, lifecycle=Issuing))
//                    case Rejected =>
//                      actorCtx.log.info(s"${positionOpener.desc}: Unexpected rejection on orderID: ${order.orderID}")
//                      positionOpener.onRejection(ledger2, order.orderID, order.ordRejReason)
//                    case _ =>
//                      loop(ctx.copy(ledger=ledger2, orderID=null, orderPrice=null, lifecycle=Issuing))
//                  }
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Issue
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Unexpected failure of issuing of orders", exc)
                  timers.cancel(Expiry)
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(ctx.orderID), exc)
              }
            case (_, c@Cancel(orderID)) => // due to change of heart
              positionOpener.cancelOrder(orderID) match {
                case Success(os) =>
                  val ledger2 = ctx.ledger.record(os)
                  loop(ctx.copy(ledger=ledger2))
//                  val order = ledger2.ledgerOrdersById(orderID)
//                  order.ordStatus match {
//                    case Filled => // race condition
//                      actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: ${order.orderID} @ ${order.price} (even though Cancelled on changed of heart)")
//                      positionOpener.onFilled(ledger2, order.price)
//                    case Canceled =>
//                      actorCtx.log.info(s"${positionOpener.desc}: Canceled orderID: ${order.orderID} due to change of heart")
//                      positionOpener.onChangeOfHeart(ledger2)
//                    case _ =>
//                      loop(ctx.copy(ledger=ledger2, lifecycle=Cancelling))
//                  }
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! c
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Unexpected failure of cancellation of orderID: $orderID", exc)
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(orderID), exc)
              }
            case (_, a@Amend(orderID, newPrice)) => // due to bestPrice moving away from current price
              positionOpener.amendOrder(orderID, newPrice) match {
                case Success(o) =>
                  val ledger2 = ctx.ledger.record(o)
                  loop(ctx.copy(ledger=ledger2))
//                  val order = ledger2.ledgerOrdersById(o.orderID)
//                  order.ordStatus match {
//                    case PostOnlyFailure =>
//                      actorCtx.log.info(s"${positionOpener.desc}: PostOnlyFailure on orderID: ${order.orderID}, need to re-issue")
//                      actorCtx.self ! Issue
//                      loop(ctx.copy(ledger=ledger2, orderID=null, orderPrice=null, lifecycle=Issuing))
//                    case _ =>
//                      loop(ctx.copy(ledger=ledger2, orderID=null, orderPrice=null, lifecycle=Issuing))
//                  }
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! a
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Unexpected failure of issuing of orders", exc)
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(ctx.orderID), exc)
              }
            case (OpenPositionCtx(ledger, orderID, orderPrice, lifecycle), WsEvent(data:UpsertOrder)) =>
              val ledger2 = ledger.record(data)
              val order = ledger2.ledgerOrdersById(orderID)
              (order.ordStatus, lifecycle) match {
                case (Filled, _) =>
                  actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: $orderID @ ${order.price}")
                  positionOpener.onFilled(ledger2, order.price)
                case (PostOnlyFailure, _) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got PostOnlyFailure for orderID: ${order.orderID}, re-issuing...")
                  actorCtx.self ! Issue
                  loop(ctx.copy(ledger=ledger2, lifecycle=Issuing))
                case (Canceled, Cancelling) =>
                  actorCtx.log.info(s"${positionOpener.desc}: Canceled due to change of heart, orderID: ${order.orderID}")
                  positionOpener.onChangeOfHeart(ledger2)
                case (Canceled, Issuing | Amending) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: ${order.orderID}")
                  positionOpener.onExternalCancel(ledger2, order.orderID)
                case (Rejected, _) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected rejection of orderID: ${order.orderID}")
                  positionOpener.onRejection(ledger2, order.orderID, order.ordRejReason)
                case (New, Amending) if data.containsOrderIDs(orderID) =>
                  actorCtx.log.info(s"${positionOpener.desc}: Done amending: orderID: ${order.orderID}, price: $orderPrice -> ${order.price}")
                  loop(ctx.copy(ledger=ledger2, lifecycle=Issuing))
                case (_, Issuing) =>
                  if (! positionOpener.shouldKeepGoing(ledger2)) {
                    actorCtx.self ! Cancel(orderID)
                    loop(ctx.copy(ledger=ledger2, lifecycle=Cancelling))
                  } else {
                    val bestPrice = positionOpener.bestPrice(ledger2)
                    if (orderPrice != bestPrice) {
                      actorCtx.self ! Amend(orderID, bestPrice)
                      loop(ctx.copy(ledger=ledger2, lifecycle=Amending))
                    } else
                      loop(ctx.copy(ledger=ledger2))
                  }
                case _ =>
                  loop(ctx.copy(ledger=ledger2))
              }
          }
      }

      // initial request
      positionOpener.openOrder(initLedger) match {
        case Success(o) if o.ordStatus.contains(OrderStatus.New) =>
          val ledger2 = initLedger.record(o)
          o.ordStatus match {
            case Some(OrderStatus.New) =>
              log.info(s"${positionOpener.desc}: Initial position opening, orderID: ${o.orderID}")
              val ctx = OpenPositionCtx(ledger2, o.orderID, o.price.get)
              loop(ctx)
            case Some(OrderStatus.PostOnlyFailure) =>
              log.info(s"${positionOpener.desc}: Initial position opening, PostOnlyFailure, orderID: ${o.orderID}")
              val ctx = OpenPositionCtx(ledger2)
              loop(ctx)
            case Some(OrderStatus.Rejected) =>
              log.warn(s"${positionOpener.desc}: Initial position opening, Rejected, orderID: ${o.orderID}")
              positionOpener.onRejection(ledger2, o.orderID, o.ordRejReason)
            case _ =>
              val ctx = OpenPositionCtx(ledger2, o.orderID)
              loop(ctx)
          }
        case Failure(exc:RecoverableError) =>
          log.warn(s"${positionOpener.desc}: Initial position opening recoverable error", exc) // FIXME inelegant way of re-starting failed request
          timers.startSingleTimer(Issue, 0.microseconds)
          loop(OpenPositionCtx(initLedger, lifecycle=Issuing))
        case Failure(exc) =>
          log.info(s"${positionOpener.desc}: Initial position opening failure!", exc)
          positionOpener.onIrrecoverableError(initLedger, None, exc)
      }
    }

  def closePosition(initLedger: Ledger, positionCloser: PositionCloser, metrics: Option[Metrics]=None)(implicit log: Logger): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: ClosePositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] { case (actorCtx, wsEvent) =>
          (ctx, wsEvent) match {
            case (_, SendMetrics) =>
              val ledger2 = ctx.ledger.withMetrics()
              metrics.foreach(_.gauge(ledger2.ledgerMetrics.map(_.metrics).getOrElse(Map.empty)))
              loop(ctx.copy(ledger2))
            case (_, Cancel(orderIDs@_*)) => // no limit on retries
              positionCloser.cancelOrders(orderIDs) match {
                case Success(os) =>
                  val ledger2 = ctx.ledger.record(os)
                  val orders = orderIDs.map(ledger2.ledgerOrdersById)
                  val orderStatuses = orders.map(_.ordStatus).toSet
                  if (orderStatuses == Set(Filled, Canceled)) {
                    val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                    actorCtx.log.info(s"${positionCloser.desc}: Got a fill and cancel in orderIDs: ${orderDescs.mkString(", ")}")
                    positionCloser.onDone(ledger2)
                  } else
                    loop(ctx.copy(ledger=ledger2, lifecycle=Cancelling))
//                case Failure(_: BackoffRequiredError) =>
//                  val backoff = positionCloser.backoffStrategy(ctx.retry)
//                  timers.startSingleTimer(Cancel(orderIDs:_*), Duration(backoff, MILLISECONDS))
//                  Behaviors.same
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Cancel(orderIDs:_*)
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionCloser.desc}: Unexpected failure of cancellation of orderIDs: ${orderIDs.mkString(", ")}", exc)
                  positionCloser.onIrrecoverableError(ctx.ledger, ctx.orderIDs, exc)
              }
            case (_, Issue) =>
              positionCloser.openOrders(ctx.ledger) match {
                case Success(os) =>
                  // FIXME: not checking the status here, relying on WS. Some statuses eg. Filled, PostOnlyFailure are only delivered via WS
                  val ledger2 = ctx.ledger.record(os)
                  val orderIDs = os.orders.map(_.orderID)
                  loop(ctx.copy(ledger=ledger2, orderIDs=orderIDs, lifecycle=Issuing))
//                case Failure(_: BackoffRequiredError) =>
//                  val backoff = positionCloser.backoffStrategy(ctx.retry)
//                  timers.startSingleTimer(Issue, Duration(backoff, MILLISECONDS))
//                  Behaviors.same
                case Failure(_: RecoverableError) =>
                  actorCtx.self ! Issue
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.error(s"${positionCloser.desc}: Unexpected failure of issuing of orders", exc)
                  positionCloser.onIrrecoverableError(ctx.ledger, ctx.orderIDs, exc)
              }
            // FIXME: similar or the same Issuing == Cancelling
            case (ClosePositionCtx(ledger, orderIDs, Issuing), WsEvent(data:UpsertOrder)) if data.containsOrderIDs(orderIDs:_*) =>
              val ledger2 = ledger.record(data)
              val orders = orderIDs.map(ledger2.ledgerOrdersById)
              val orderStatuses = orders.map(_.ordStatus).toSet
              if (orderStatuses == Set(Canceled)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                actorCtx.log.warn(s"${positionCloser.desc}: Got an external cancel on orderIDs: ${orderDescs.mkString(", ")} - unexpected!")
                positionCloser.onExternalCancels(ledger, orderIDs)
              } else if (orderStatuses.contains(Rejected)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}:${o.ordRejReason.getOrElse("<no_rejection_reason>")}")
                val orderIDsRejections = orders.map(o => (o.orderID, o.ordStatus, o.ordRejReason))
                actorCtx.log.warn(s"${positionCloser.desc}: Got a rejection on orderIDs: ${orderDescs.mkString(", ")} - unexpected!")
                positionCloser.onRejections(ledger, orderIDsRejections)
              } else if (orderStatuses.contains(Filled)) {
                actorCtx.self ! Cancel(orderIDs:_*)  // FIXME: hack, canceling even the filled order
                loop(ctx.copy(ledger=ledger2))
              } else
                loop(ctx.copy(ledger=ledger2))
            case (ClosePositionCtx(ledger, orderIDs, Cancelling), WsEvent(data:UpsertOrder)) if data.containsOrderIDs(orderIDs:_*) =>
              val ledger2 = ledger.record(data)
              val orders = orderIDs.map(ledger2.ledgerOrdersById)
              val orderStatuses = orders.map(_.ordStatus).toSet
              if (orderStatuses == Set(Canceled)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                actorCtx.log.warn(s"${positionCloser.desc}: Got cancel only on orderIDs: ${orderDescs.mkString(", ")} - unexpected!")
                positionCloser.onExternalCancels(ledger, orderIDs)
              } else if (orderStatuses.contains(Rejected)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}:${o.ordRejReason.getOrElse("<no_rejection_reason>")}")
                val orderIDsRejections = orders.map(o => (o.orderID, o.ordStatus, o.ordRejReason))
                actorCtx.log.warn(s"${positionCloser.desc}: Got a rejection on orderIDs: ${orderDescs.mkString(", ")} - unexpected!")
                positionCloser.onRejections(ledger, orderIDsRejections)
              } else if (orderStatuses == Set(Filled, Canceled)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                actorCtx.log.info(s"${positionCloser.desc}: Got a fill and cancel in orderIDs: ${orderDescs.mkString(", ")} - closing position!")
                positionCloser.onDone(ledger2)
              } else
                loop(ctx.copy(ledger=ledger2))
            case (_, WsEvent(data)) =>
              val ledger2 = ctx.ledger.record(data)
              loop(ctx.copy(ledger=ledger2))
//            case (ctx, data) =>
//              actorCtx.log.error(s"${positionCloser.desc}: Unexpected combo of ctx:\n$ctx\ndata:\n$data")
//              Behaviors.same
          }
        }

      // initial request
      positionCloser.openOrders(initLedger) match {
        case Success(os) =>
          val orderIDs = os.orders.map(_.orderID)
          val orderDescs = os.orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price.getOrElse("???")} = ${o.ordStatus.get}")
          log.info(s"${positionCloser.desc}: Initial position closing, orderID: ${orderDescs.mkString(", ")}")
          val ctx = ClosePositionCtx(initLedger.record(os), orderIDs)
          loop(ctx)
//        case Failure(_: BackoffRequiredError) =>
//          val backoff = positionCloser.backoffStrategy(ctx.retry)
//          timers.startSingleTimer(Issue, Duration(backoff, MILLISECONDS))
//          Behaviors.same
        case Failure(exc: RecoverableError) =>
          log.warn(s"${positionCloser.desc}: Initial position closing recoverable error", exc) // FIXME inelegant way of re-starting failed request
          timers.startSingleTimer(Issue, 0.microseconds)
          loop(ClosePositionCtx(initLedger, null))
        case Failure(exc) =>
          log.info(s"${positionCloser.desc}: Initial position closing failure!", exc)
          positionCloser.onIrrecoverableError(initLedger, Nil, exc)
      }
    }

  def apply(restGateway: IRestGateway,
            tradeQty: Int, minTradeVol: BigDecimal,
            openPositionExpiryMs: Long, backoffMs: Long = 500,
            bullScoreThreshold: BigDecimal=0.5, bearScoreThreshold: BigDecimal= -0.5,
            reqRetries: Int, markupRetries: Int,
            takeProfitMargin: BigDecimal, stoplossMargin: BigDecimal, postOnlyPriceAdj: BigDecimal,
            metrics: Option[Metrics]=None)(implicit log: Logger): Behavior[ActorEvent] = {

    assert(bullScoreThreshold > bearScoreThreshold, s"bullScoreThreshold ($bullScoreThreshold) <= bearScoreThreshold ($bearScoreThreshold)")

    /**
     * Gather enough WS data to trade, then switch to idle
     */
    def init(ctx: InitCtx): Behavior[ActorEvent] = Behaviors.withTimers[ActorEvent] { timers =>
      Behaviors.receivePartial[ActorEvent] {
        case (actorCtx, WsEvent(wsData)) =>
          actorCtx.log.debug(s"init, received: $wsData")
          val ledger2 = ctx.ledger.record(wsData)
          if (ledger2.isMinimallyFilled) {
            timers.startTimerAtFixedRate(SendMetrics, 1.minute)
            // border from: https://www.asciiart.eu/art-and-design/borders
            actorCtx.log.info(
              """
                |.-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-.
                ||                                             |
                ||   Ledger minimally filled, ready to go!     |
                ||                                             |
                |`-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-'""".stripMargin)
            idle(IdleCtx(ledger2))
          } else
            init(ctx.copy(ledger2))
      }
    }

    /**
     * Waiting for market conditions to change to volumous bull or bear
     */
    def idle(ctx: IdleCtx): Behavior[ActorEvent] = Behaviors.receivePartial[ActorEvent] {
      case (_, SendMetrics) =>
        val ledger2 = ctx.ledger.withMetrics()
        metrics.foreach(_.gauge(ledger2.ledgerMetrics.map(_.metrics).getOrElse(Map.empty)))
        idle(ctx.copy(ledger2))
      case (actorCtx, WsEvent(wsData)) =>
        actorCtx.log.debug(s"idle, received: $wsData")
        val ledger2 = ctx.ledger.record(wsData)
        if (ledger2.orderBookHeadVolume > minTradeVol && ledger2.sentimentScore >= bullScoreThreshold)
          openLong(ledger2)
        else if (ledger2.orderBookHeadVolume > minTradeVol && ledger2.sentimentScore <= bearScoreThreshold)
          openShort(ledger2)
        else {
          actorCtx.log.debug(s"Ledger suggests to hold back, orderBookHeadVolume: ${ledger2.orderBookHeadVolume}, sentimentScore: ${ledger2.sentimentScore}...")
          idle(ctx.copy(ledger = ledger2))
        }
    }

    def openLong(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val desc = "Long Buy"
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeLong(l, openPrice)
        override def onChangeOfHeart(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $orderID")
        override def onRejection(l: Ledger, orderID: String, rejectionReason: Option[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $orderID, reason: $rejectionReason")
        override def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $orderID", exc)
        override def bestPrice(l: Ledger): BigDecimal = l.bidPrice
        override def openOrder(l: Ledger): Try[Order] = {
          val price = l.bidPrice
          log.info(s"$desc: opening @ $price")
          restGateway.placeLimitOrderSync(tradeQty, price, false, OrderSide.Buy)
        }
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
        override def amendOrder(orderID: String, newPrice: BigDecimal): Try[Order] = restGateway.amendOrderSync(Some(orderID), None, newPrice)
        override def shouldKeepGoing(l: Ledger): Boolean = l.orderBookHeadVolume > minTradeVol && l.sentimentScore >= bullScoreThreshold
      }, metrics)

    def closeLong(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val desc = "Long Sell"
        override def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDs.mkString(", ")}")
        override def onRejections(l: Ledger, orderIDsRejections: Seq[(String, OrderStatus.Value, Option[String])]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDsRejections.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDs.mkString(", ")}", exc)
        override def openOrders(l: Ledger): Try[Orders] = restGateway.placeBulkOrdersSync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Sell, tradeQty, openPrice + takeProfitMargin, true),
          OrderReq.asStopOrder(OrderSide.Sell, tradeQty, openPrice - stoplossMargin, true)))
        )
        override def cancelOrders(orderIDs: Seq[String]): Try[Orders] = restGateway.cancelOrderSync(Some(orderIDs), None)
      }, metrics)

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val desc = "Short Sell"
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeLong(l, openPrice)
        override def onChangeOfHeart(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID")
        override def onRejection(l: Ledger, orderID: String, rejectionReason: Option[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID, reason: $rejectionReason")
        override def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID", exc)
        override def bestPrice(l: Ledger): BigDecimal = l.askPrice
        override def openOrder(l: Ledger): Try[Order] = {
          val price = l.askPrice
          log.info(s"$desc: opening @ $price")
          restGateway.placeLimitOrderSync(tradeQty, price, false, OrderSide.Sell)
        }
        override def cancelOrder(orderID: String): Try[Orders] = restGateway.cancelOrderSync(Some(Seq(orderID)), None)
        override def amendOrder(orderID: String, newPrice: BigDecimal): Try[Order] = restGateway.amendOrderSync(Some(orderID), None, newPrice)
        override def shouldKeepGoing(l: Ledger): Boolean = l.orderBookHeadVolume > minTradeVol && l.sentimentScore <= bearScoreThreshold
      }, metrics)

    def closeShort(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val desc = "Short Buy"
        override def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing orderID: ${orderIDs.mkString(", ")}")
        override def onRejections(l: Ledger, orderIDsRejections: Seq[(String, OrderStatus.Value, Option[String])]): Behavior[ActorEvent] = throw new Exception(s"Unexpected rejection of long closing orderID: ${orderIDsRejections.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing orderID: ${orderIDs.mkString(", ")}", exc)
        override def openOrders(l: Ledger): Try[Orders] = restGateway.placeBulkOrdersSync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Buy, tradeQty, openPrice - takeProfitMargin, true),
          OrderReq.asStopOrder(OrderSide.Buy, tradeQty, openPrice + stoplossMargin, true)))
        )
        override def cancelOrders(orderIDs: Seq[String]): Try[Orders] = restGateway.cancelOrderSync(Some(orderIDs), None)
      }, metrics)

    init(InitCtx(ledger = Ledger()))
  }
}
