package moon

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger
import moon.OrderStatus._
import moon.TradeLifecycle._

import scala.collection.Set
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object OrchestratorActor {
  trait PositionOpener {
    val desc: String
    def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent]             // desired outcome - order filled
    def onChangeOfHeart(l: Ledger): Behavior[ActorEvent]                             // once we decide to stop opening (via shouldKeepGoing())
    def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent]           // unexpected cancel (not from bot)
    def onRejection(l: Ledger, orderID: String, rejectionReason: Option[String]): Behavior[ActorEvent]  // unexpected rejection
    def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent]  // coding error???
    def bestPrice(l: Ledger): BigDecimal
    def shouldKeepGoing(l: Ledger): Boolean
    def openOrder(l: Ledger): Future[Order]                                          // how to open order
    def cancelOrder(orderID: String): Future[Orders]                                 // how to cancel order
    def amendOrder(orderID: String, newPrice: BigDecimal): Future[Order]             // how to open order
  }

  trait PositionCloser {
    val desc: String
    def onDone(l: Ledger): Behavior[ActorEvent]                                      // takeProfit or stoploss filled
    def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent]    // unexpected cancel (not from bot)
    def onRejections(l: Ledger, orderIDsRejections: Seq[(String, OrderStatus.Value, Option[String])]): Behavior[ActorEvent]  // unexpected rejection
    def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent]  // coding error???
    def openOrders(l: Ledger): Future[Orders]                                        // how to open orders
    def cancelOrders(orderIDs: Seq[String]): Future[Orders]                          // how to cancel orders
    def backoffStrategy(retry: Int): Int = Array(0, 100, 200, 500, 1000, 2000, 5000, 1000)(math.max(retry, 7))
  }

  def openPosition(initLedger: Ledger, positionOpener: PositionOpener, metrics: Option[Metrics]=None)(implicit log: Logger, execCtx: ExecutionContext): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] { case (actorCtx, event) =>
          (ctx, event) match {
            case (_, SendMetrics) =>
              val ledger2 = ctx.ledger.withMetrics()
              metrics.foreach(_.gauge(ledger2.ledgerMetrics.map(_.metrics).getOrElse(Map.empty)))
              loop(ctx.copy(ledger2))
            case (OpenPositionCtx(ledger, _, IssuingNew), RestEvent(res)) =>
              res match {
                case Success(o:Order) =>
                  val ledger2 = ledger.record(o)
                  val order = ledger2.ledgerOrdersById(o.orderID)
                  order.ordStatus match {
                    case Filled =>
                      actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: ${order.orderID} @ ${order.price}")
                      positionOpener.onFilled(ledger2, order.price)
                    case PostOnlyFailure =>
                      actorCtx.log.warn(s"${positionOpener.desc}: Got PostOnlyFailure for orderID: ${order.orderID}, re-issuing...")
                      positionOpener.openOrder(ctx.ledger) onComplete (res => actorCtx.self ! RestEvent(res))
                      loop(ctx.copy(ledger = ledger2, orderID = null))
                    case Canceled =>
                      actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: ${order.orderID}")
                      positionOpener.onExternalCancel(ledger2, order.orderID)
                    case _ =>
                      loop(ctx.copy(ledger = ledger2, orderID = order.orderID, lifecycle = Waiting))
                  }
                case Success(os:Orders) => loop(ctx.copy(ledger = ledger.record(os))) // unexpected, ignore...
                case Success(_) => Behaviors.same
                case Failure(_: RecoverableError) =>
                  positionOpener.openOrder(ctx.ledger) onComplete (res => actorCtx.self ! RestEvent(res)) // FIXME: can I re-issue, no guarantees it's not gone through...
                  Behaviors.same
                case Failure(_: IgnorableError) =>
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Unexpected failure of issuing new order", exc)
                  positionOpener.onIrrecoverableError(ctx.ledger, null, exc)
              }
            case (OpenPositionCtx(ledger, orderID, IssuingCancel), RestEvent(res)) =>
              res match {
                case Success(os:Orders) if os.containsOrderIDs(orderID) =>
                  val ledger2 = ledger.record(os)
                  val order = ledger2.ledgerOrdersById(ctx.orderID)
                  order.ordStatus match {
                    case Canceled =>
                      actorCtx.log.info(s"${positionOpener.desc}: Canceled due to change of heart, orderID: ${order.orderID}")
                      positionOpener.onChangeOfHeart(ledger2)
                    case Filled =>
                      actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: ${ctx.orderID} @ ${order.price} (even though had change of heart)")
                      positionOpener.onFilled(ledger2, order.price)
                    case _ =>
                      loop(ctx.copy(ledger = ledger2, lifecycle = Waiting)) // unexpected, ignore...
                  }
                case Success(o:Order)   => loop(ctx.copy(ledger = ledger.record(o)))  // unexpected, ignore...
                case Success(os:Orders) => loop(ctx.copy(ledger = ledger.record(os))) // unexpected, ignore...
                case Failure(_: RecoverableError) =>
                  positionOpener.cancelOrder(orderID) onComplete (res => actorCtx.self ! RestEvent(res))
                  Behaviors.same
                case Failure(_: IgnorableError) =>
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Unexpected failure of cancellation of orderID: $orderID", exc)
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(orderID), exc)
              }
            case (OpenPositionCtx(ledger, orderID, IssuingAmend), RestEvent(res)) =>
              res match {
                case Success(o:Order) if orderID == o.orderID =>
                  val ledger2 = ledger.record(o)
                  val order = ledger2.ledgerOrdersById(orderID)
                  order.ordStatus match {
                    case Filled =>
                      actorCtx.log.info(s"${positionOpener.desc}: Filled (upon amend) orderID: $orderID @ ${order.price}")
                      positionOpener.onFilled(ledger2, order.price)
                    case PostOnlyFailure =>
                      actorCtx.log.warn(s"${positionOpener.desc}: Got PostOnlyFailure (upon amend) for orderID: $orderID, re-issuing...")
                      positionOpener.openOrder(ctx.ledger) onComplete (res => actorCtx.self ! RestEvent(res))
                      loop(ctx.copy(ledger = ledger2, orderID = null, lifecycle = IssuingNew))
                    case Canceled =>
                      actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: $orderID")
                      positionOpener.onExternalCancel(ledger2, order.orderID)
                    case _ =>  // presumingly amended
                      loop(ctx.copy(ledger = ledger2, lifecycle = Waiting))
                  }
                case Success(o:Order)   => loop(ctx.copy(ledger = ledger.record(o)))  // unexpected, ignore...
                case Success(os:Orders) => loop(ctx.copy(ledger = ledger.record(os))) // unexpected, ignore...
                case Failure(_: RecoverableError) =>
                  val bestPrice = positionOpener.bestPrice(ledger)
                  positionOpener.amendOrder(orderID, bestPrice) onComplete (res => actorCtx.self ! RestEvent(res))
                  Behaviors.same
                case Failure(_: IgnorableError) =>
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Unexpected failure of issuing of orders", exc)
                  positionOpener.onIrrecoverableError(ctx.ledger, Some(ctx.orderID), exc)
              }
            case (OpenPositionCtx(ledger, _, _), RestEvent(Success(o:Order)))   => loop(ctx.copy(ledger = ledger.record(o)))   // unexpected
            case (OpenPositionCtx(ledger, _, _), RestEvent(Success(os:Orders))) => loop(ctx.copy(ledger = ledger.record(os)))  // unexpected
            case (OpenPositionCtx(_, _, _), RestEvent(_))                       => Behaviors.same                              // unexpected
            case (OpenPositionCtx(ledger, null, _), WsEvent(data)) =>  // no orderID recorded yet - wait till we have 1
              loop(ctx.copy(ledger = ledger.record(data)))
            case (OpenPositionCtx(ledger, orderID, lifecycle), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              val order = ledger2.ledgerOrdersById(orderID)
              (order.ordStatus, lifecycle) match {
                case (Filled, _) =>
                  actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: $orderID @ ${order.price}")
                  positionOpener.onFilled(ledger2, order.price)
                case (Canceled, IssuingCancel) =>
                  actorCtx.log.info(s"${positionOpener.desc}: Canceled2 due to change of heart, orderID: ${order.orderID}")
                  positionOpener.onChangeOfHeart(ledger2)
                case (Canceled, _) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: ${order.orderID} in lifecycle: $lifecycle")
                  positionOpener.onExternalCancel(ledger2, order.orderID)
                case (Rejected, _) =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected rejection of orderID: ${order.orderID} in lifecycle: $lifecycle")
                  positionOpener.onRejection(ledger2, order.orderID, order.ordRejReason)
                case (_, Waiting) =>
                  // change of heart, but cancel first
                  if (!positionOpener.shouldKeepGoing(ledger2)) {
                    actorCtx.log.info(s"${positionOpener.desc}: having a change of heart, cancelling...")
                    positionOpener.cancelOrder(orderID) onComplete (res => actorCtx.self ! RestEvent(res))
                    loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
                  } else {
                    // or chase best price
                    val bestPrice = positionOpener.bestPrice(ledger2)
                    if (order.price != bestPrice) {
                      actorCtx.log.info(s"${positionOpener.desc}: Best price moved, will change: ${order.price} -> $bestPrice")
                      positionOpener.amendOrder(orderID, bestPrice) onComplete (res => actorCtx.self ! RestEvent(res))
                      loop(ctx.copy(ledger = ledger2, lifecycle = IssuingAmend))
                    } else {
                      actorCtx.log.debug(s"...Issuing noop @ orderID: $orderID, lifecycle: $lifecycle, data: $data")
                      loop(ctx.copy(ledger = ledger2))
                    }
                  }
                case _ => // for any other orderStatus/lifecycle, just record...
                  actorCtx.log.debug(s"...noop (lifecycle=$lifecycle) @ orderID: $orderID, lifecycle: $lifecycle, data: $data")
                  loop(ctx.copy(ledger = ledger2))
              }
          }
      }

      // initial request
      positionOpener.openOrder(initLedger) onComplete (res => timers.startSingleTimer(RestEvent(res), 0.nanoseconds))
      loop(OpenPositionCtx(ledger = initLedger, lifecycle = IssuingNew))
    }

  def closePosition(initLedger: Ledger, positionCloser: PositionCloser, metrics: Option[Metrics]=None)(implicit log: Logger, execCtx: ExecutionContext): Behavior[ActorEvent] =
    Behaviors.withTimers[ActorEvent] { timers =>

      def loop(ctx: ClosePositionCtx): Behavior[ActorEvent] =
        Behaviors.receivePartial[ActorEvent] { case (actorCtx, wsEvent) =>
          (ctx, wsEvent) match {
            case (_, SendMetrics) =>
              val ledger2 = ctx.ledger.withMetrics()
              metrics.foreach(_.gauge(ledger2.ledgerMetrics.map(_.metrics).getOrElse(Map.empty)))
              loop(ctx.copy(ledger2))
            case (ClosePositionCtx(ledger, _, IssuingNew), RestEvent(res)) =>
              res match {
                case Success(os: Orders) =>
                  // FIXME: not checking the status here, relying on WS, as it's highly unlikely we get a fill straight away
                  val ledger2 = ctx.ledger.record(os)
                  val orderIDs = os.orders.map(_.orderID)
                  loop(ctx.copy(ledger = ledger2, orderIDs = orderIDs, lifecycle = Waiting))
                case Success(o:Order) => loop(ctx.copy(ledger=ledger.record(o)))
                case Success(_) => Behaviors.same
                case Failure(_: RecoverableError) =>
                  positionCloser.openOrders(ledger) onComplete (res => actorCtx.self ! RestEvent(res))
                  Behaviors.same
                case Failure(_: IgnorableError) =>
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.error(s"${positionCloser.desc}: Unexpected failure of issuing of orders", exc)
                  positionCloser.onIrrecoverableError(ctx.ledger, ctx.orderIDs, exc)
              }
            case (ClosePositionCtx(ledger, orderIDs, IssuingCancel), RestEvent(res)) =>
              res match {
                case Success(os: Orders) =>
                  val ledger2 = ledger.record(os)
                  val orders = orderIDs.map(ledger2.ledgerOrdersById)
                  val orderStatuses = orders.map(_.ordStatus).toSet
                  if (orderStatuses == Set(Filled, Canceled)) {
                    val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                    actorCtx.log.info(s"${positionCloser.desc}: Got a fill and cancel in: ${orderDescs.mkString(", ")}")
                    positionCloser.onDone(ledger2)
                  } else if (orderStatuses == Set(Canceled)) {
                    val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                    actorCtx.log.info(s"${positionCloser.desc}: Got a unexpected cancel in: ${orderDescs.mkString(", ")}")
                    positionCloser.onExternalCancels(ledger2, orderIDs)
                  } else
                    loop(ctx.copy(ledger = ledger2)) // wait for Filled/Canceled out of WsEvents
                case Success(o: Order) => loop(ctx.copy(ledger = ledger.record(o))) // unexpected, ignore...
                case Success(_) => Behaviors.same
                case Failure(_: RecoverableError) =>
                  positionCloser.cancelOrders(orderIDs) onComplete (res => actorCtx.self ! RestEvent(res))
                  Behaviors.same
                case Failure(_: IgnorableError) =>
                  Behaviors.same
                case Failure(exc) =>
                  actorCtx.log.warn(s"${positionCloser.desc}: Unexpected failure of cancellation of orderIDs: ${orderIDs.mkString(", ")}", exc)
                  positionCloser.onIrrecoverableError(ctx.ledger, ctx.orderIDs, exc)
              }
            case (ClosePositionCtx(ledger, orderIDs, lifecycle), WsEvent(data: UpsertOrder)) if data.containsOrderIDs(orderIDs: _*) =>
              val ledger2 = ledger.record(data)
              val orders = orderIDs.map(ledger2.ledgerOrdersById)
              val ordStatuses = orders.map(_.ordStatus).toSet
              if (ordStatuses == Set(Canceled)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                actorCtx.log.warn(s"${positionCloser.desc}: Got cancel only on orderIDs: ${orderDescs.mkString(", ")} - unexpected!")
                positionCloser.onExternalCancels(ledger, orderIDs)
              } else if (ordStatuses == Set(Filled, Canceled)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}")
                actorCtx.log.info(s"${positionCloser.desc}: Got a fill and cancel in orderIDs: ${orderDescs.mkString(", ")} - closing position!")
                positionCloser.onDone(ledger2)
              } else if (ordStatuses.contains(Rejected)) {
                val orderDescs = orders.map(o => s"${o.orderID}:${o.ordType}:${o.side} @ ${o.price} = ${o.ordStatus}:${o.ordRejReason.getOrElse("<no_rejection_reason>")}")
                val orderIDsRejections = orders.map(o => (o.orderID, o.ordStatus, o.ordRejReason))
                actorCtx.log.warn(s"${positionCloser.desc}: Got a rejection on orderIDs: ${orderDescs.mkString(", ")} - unexpected!")
                positionCloser.onRejections(ledger, orderIDsRejections)
              } else if (lifecycle == Waiting && ordStatuses.contains(Filled)) {
                positionCloser.cancelOrders(orderIDs) onComplete (res => actorCtx.self ! RestEvent(res))
                loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
              } else {
                loop(ctx.copy(ledger = ledger2))
              }
            case (ClosePositionCtx(ledger, _, _), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              loop(ctx.copy(ledger = ledger2))
          }
        }

        // initial request
        positionCloser.openOrders(initLedger) onComplete (res => timers.startSingleTimer(RestEvent(res), 0.millisecond))
        loop(ClosePositionCtx(ledger = initLedger, lifecycle = IssuingNew))
      }


  def apply(restGateway: IRestGateway,
            tradeQty: Int, minTradeVol: BigDecimal,
            openPositionExpiryMs: Long,
            bullScoreThreshold: BigDecimal=0.5, bearScoreThreshold: BigDecimal= -0.5,
            reqRetries: Int, markupRetries: Int,
            takeProfitMargin: BigDecimal, stoplossMargin: BigDecimal, postOnlyPriceAdj: BigDecimal,
            metrics: Option[Metrics]=None)(implicit log: Logger, execCtx: ExecutionContext): Behavior[ActorEvent] = {

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
        override def openOrder(l: Ledger): Future[Order] = {
          val price = l.bidPrice
          log.info(s"$desc: opening @ $price")
          restGateway.placeLimitOrderAsync(tradeQty, price, false, OrderSide.Buy)
        }
        override def cancelOrder(orderID: String): Future[Orders] = {
          log.info(s"$desc: cancelling orderID: $orderID")
          restGateway.cancelOrderAsync(Some(Seq(orderID)), None)
        }
        override def amendOrder(orderID: String, newPrice: BigDecimal): Future[Order] = {
          log.info(s"$desc: amending orderID: $orderID, newPrice: $newPrice")
          restGateway.amendOrderAsync(Some(orderID), None, newPrice)
        }
        override def shouldKeepGoing(l: Ledger): Boolean = l.sentimentScore >= bearScoreThreshold
      }, metrics)

    def closeLong(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val desc = "Long Sell"
        override def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDs.mkString(", ")}")
        override def onRejections(l: Ledger, orderIDsRejections: Seq[(String, OrderStatus.Value, Option[String])]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDsRejections.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orderIDs.mkString(", ")}", exc)
        override def openOrders(l: Ledger): Future[Orders] = restGateway.placeBulkOrdersAsync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Sell, tradeQty, openPrice + takeProfitMargin, true),
          OrderReq.asStopOrder(OrderSide.Sell, tradeQty, openPrice - stoplossMargin, true)))
        )
        override def cancelOrders(orderIDs: Seq[String]): Future[Orders] = restGateway.cancelOrderAsync(Some(orderIDs), None)
      }, metrics)

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val desc = "Short Sell"
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeShort(l, openPrice)
        override def onChangeOfHeart(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExternalCancel(l: Ledger, orderID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID")
        override def onRejection(l: Ledger, orderID: String, rejectionReason: Option[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID, reason: $rejectionReason")
        override def onIrrecoverableError(l: Ledger, orderID: Option[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $orderID", exc)
        override def bestPrice(l: Ledger): BigDecimal = l.askPrice
        override def openOrder(l: Ledger): Future[Order] = {
          val price = l.askPrice
          log.info(s"$desc: opening @ $price")
          restGateway.placeLimitOrderAsync(tradeQty, price, false, OrderSide.Sell)
        }
        override def cancelOrder(orderID: String): Future[Orders] = {
          log.info(s"$desc: cancelling orderID: $orderID")
          restGateway.cancelOrderAsync(Some(Seq(orderID)), None)
        }
        override def amendOrder(orderID: String, newPrice: BigDecimal): Future[Order] = {
          log.info(s"$desc: amending orderID: $orderID, newPrice: $newPrice")
          restGateway.amendOrderAsync(Some(orderID), None, newPrice)
        }
        override def shouldKeepGoing(l: Ledger): Boolean = l.sentimentScore <= bullScoreThreshold
      }, metrics)

    def closeShort(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val desc = "Short Buy"
        override def onDone(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, orderIDs: Seq[String]): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing orderID: ${orderIDs.mkString(", ")}")
        override def onRejections(l: Ledger, orderIDsRejections: Seq[(String, OrderStatus.Value, Option[String])]): Behavior[ActorEvent] = throw new Exception(s"Unexpected rejection of long closing orderID: ${orderIDsRejections.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, orderIDs: Seq[String], exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing orderID: ${orderIDs.mkString(", ")}", exc)
        override def openOrders(l: Ledger): Future[Orders] = restGateway.placeBulkOrdersAsync(OrderReqs(Seq(
          OrderReq.asLimitOrder(OrderSide.Buy, tradeQty, openPrice - takeProfitMargin, true),
          OrderReq.asStopOrder(OrderSide.Buy, tradeQty, openPrice + stoplossMargin, true)))
        )
        override def cancelOrders(orderIDs: Seq[String]): Future[Orders] = restGateway.cancelOrderAsync(Some(orderIDs), None)
      }, metrics)

    assert(bullScoreThreshold > bearScoreThreshold, s"bullScoreThreshold ($bullScoreThreshold) <= bearScoreThreshold ($bearScoreThreshold)")
    init(InitCtx(ledger = Ledger()))
  }
}
