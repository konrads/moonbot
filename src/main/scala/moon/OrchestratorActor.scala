package moon

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger
import moon.OrderStatus._
import moon.TradeLifecycle._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object OrchestratorActor {
  trait PositionOpener {
    val desc: String
    def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent]             // desired outcome - order filled
    def onChangeOfHeart(l: Ledger): Behavior[ActorEvent]                             // once we decide to stop opening (via shouldKeepGoing())
    def onExternalCancel(l: Ledger, clOrdID: String): Behavior[ActorEvent]           // unexpected cancel (not from bot)
    def onRejection(l: Ledger, order: LedgerOrder): Behavior[ActorEvent]             // unexpected rejection
    def onIrrecoverableError(l: Ledger, clOrdID: String, exc: Throwable): Behavior[ActorEvent]  // coding error???
    def bestPrice(l: Ledger): BigDecimal
    def shouldKeepGoing(l: Ledger): Boolean
    def openOrder(l: Ledger): (String, Future[Order])                                // how to open order
    def cancelOrder(clOrdID: String): Future[Orders]                                 // how to cancel order
    def amendOrder(clOrdID: String, newPrice: BigDecimal): Future[Order]             // how to open order
  }

  trait PositionCloser {
    val desc: String
    def onProfit(l: Ledger): Behavior[ActorEvent]                                    // takeProfit or stoploss filled
    def onLoss(l: Ledger): Behavior[ActorEvent]                                      // takeProfit or stoploss filled
    def onExternalCancels(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String): Behavior[ActorEvent]    // unexpected cancel (not from bot)
    def onRejections(l: Ledger, orders: LedgerOrder*): Behavior[ActorEvent]  // unexpected rejection
    def onIrrecoverableError(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, exc: Throwable): Behavior[ActorEvent]  // coding error???
    def openOrders(l: Ledger): (String, String, Future[Orders])                      // how to open orders
    def cancelOrders(clOrdID: String*): Future[Orders]                               // how to cancel orders
    // keeping following for when I do need a backoff :)
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
            case (OpenPositionCtx(ledger, _, IssuingNew), RestEvent(Success(o:Order))) if o.clOrdID.isDefined =>
              val ledger2 = ledger.record(o)
              val order = ledger2.ledgerOrdersByClOrdID(o.clOrdID.get)
              order.ordStatus match {
                case Filled =>
                  actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: ${order.fullOrdID} @ ${order.price}")
                  positionOpener.onFilled(ledger2, order.price)
                case PostOnlyFailure =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got PostOnlyFailure for orderID: ${order.fullOrdID}, re-issuing...")
                  val (clOrdID, resF) = positionOpener.openOrder(ctx.ledger)
                  resF onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger2, clOrdID = clOrdID))
                case Canceled =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: ${order.fullOrdID}")
                  positionOpener.onExternalCancel(ledger2, order.clOrdID)
                case _ =>
                  loop(ctx.copy(ledger = ledger2, clOrdID = order.clOrdID, lifecycle = Waiting))
              }
            case (OpenPositionCtx(ledger, _, IssuingNew), RestEvent(Failure(_: RecoverableError))) =>
              val (clOrdID, resF) = positionOpener.openOrder(ledger)
              resF onComplete (res => actorCtx.self ! RestEvent(res)) // FIXME: can I re-issue, no guarantees original hasn't gone through...
              loop(ctx.copy(clOrdID = clOrdID))
            case (OpenPositionCtx(ledger, clOrdID, IssuingCancel), RestEvent(Success(os:Orders))) if os.containsClOrdIDs(clOrdID) =>
              val ledger2 = ledger.record(os)
              val order = ledger2.ledgerOrdersByClOrdID(ctx.clOrdID)
              order.ordStatus match {
                case Canceled =>
                  actorCtx.log.info(s"${positionOpener.desc}: Canceled due to change of heart, orderID: ${order.fullOrdID}")
                  positionOpener.onChangeOfHeart(ledger2)
                case Filled =>
                  actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: ${order.fullOrdID} @ ${order.price} (even though had change of heart)")
                  positionOpener.onFilled(ledger2, order.price)
                case _ =>
                  loop(ctx.copy(ledger = ledger2, lifecycle = Waiting)) // unexpected, ignore...
              }
            case (OpenPositionCtx(_, clOrdID, IssuingCancel), RestEvent(Failure(_: RecoverableError))) =>
              positionOpener.cancelOrder(clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
              Behaviors.same
            case (OpenPositionCtx(ledger, clOrdID, IssuingAmend), RestEvent(Success(o:Order))) if o.clOrdID.contains(clOrdID) =>
              val ledger2 = ledger.record(o)
              val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
              order.ordStatus match {
                case Filled =>
                  actorCtx.log.info(s"${positionOpener.desc}: Filled (upon amend) orderID: ${order.fullOrdID} @ ${order.price}")
                  positionOpener.onFilled(ledger2, order.price)
                case PostOnlyFailure =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got PostOnlyFailure (upon amend) for orderID: ${order.fullOrdID}, re-issuing...")
                  val (clOrdID2, resF) = positionOpener.openOrder(ctx.ledger)
                  resF onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger2, clOrdID = clOrdID2, lifecycle = IssuingNew))
                case Canceled =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: ${order.fullOrdID}")
                  positionOpener.onExternalCancel(ledger2, order.orderID)
                case _ =>  // presumingly amended
                  loop(ctx.copy(ledger = ledger2, lifecycle = Waiting))
              }
            case (OpenPositionCtx(ledger, clOrdID, IssuingAmend), RestEvent(Failure(_: RecoverableError))) =>
              val bestPrice = positionOpener.bestPrice(ledger)
              positionOpener.amendOrder(clOrdID, bestPrice) onComplete (res => actorCtx.self ! RestEvent(res))
              Behaviors.same

              // unexpected, but catered for REST interactions. Might occur when REST looses race to WS
            case (OpenPositionCtx(ledger, _, _), RestEvent(Success(o:Order))) => loop(ctx.copy(ledger = ledger.record(o)))
            case (OpenPositionCtx(ledger, _, _), RestEvent(Success(os:Orders))) => loop(ctx.copy(ledger = ledger.record(os)))
            case (_, RestEvent(Success(_))) => Behaviors.same
            case (_, RestEvent(Failure(_: IgnorableError))) => Behaviors.same
            case (OpenPositionCtx(ledger, clOrdID, lifecycle), RestEvent(Failure(exc))) => Behaviors.same
              actorCtx.log.warn(s"${positionOpener.desc}: $lifecycle: Unexpected failure of clOrdID: $clOrdID", exc)
              positionOpener.onIrrecoverableError(ledger, clOrdID, exc)

            case (OpenPositionCtx(ledger, clOrdID, lifecycle), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
              val clOrdIDMatch = orderOpt.exists(_.clOrdID == clOrdID)
              (orderOpt, lifecycle, clOrdIDMatch) match {
                case (Some(order), _, true) if order.ordStatus == Filled =>
                  actorCtx.log.info(s"${positionOpener.desc}: Filled orderID: ${order.fullOrdID} @ ${order.price}")
                  positionOpener.onFilled(ledger2, order.price)
                case (Some(order), IssuingCancel, true) if order.ordStatus == Canceled =>
                  actorCtx.log.info(s"${positionOpener.desc}: Canceled2 due to change of heart, orderID: ${order.orderID}")
                  positionOpener.onChangeOfHeart(ledger2)
                case (Some(order), _, true) if order.ordStatus == Canceled =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected cancellation of orderID: ${order.orderID} in lifecycle: $lifecycle")
                  positionOpener.onExternalCancel(ledger2, order.orderID)
                case (Some(order), _, true) if order.ordStatus == Rejected =>
                  actorCtx.log.warn(s"${positionOpener.desc}: Got unexpected rejection of orderID: ${order.orderID} in lifecycle: $lifecycle")
                  positionOpener.onRejection(ledger2, order)
                case (Some(order), Waiting, _) =>
                  // will sentiment force a change of heart?
                  if (!positionOpener.shouldKeepGoing(ledger2)) {
                    actorCtx.log.info(s"${positionOpener.desc}: having a change of heart, cancelling ${order.fullOrdID}...")
                    positionOpener.cancelOrder(clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                    loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
                  } else {
                    // need to update best price?
                    val bestPrice = positionOpener.bestPrice(ledger2)
                    if (order.price != bestPrice) {
                      actorCtx.log.info(s"${positionOpener.desc}: Best price moved, will change: ${order.price} -> $bestPrice")
                      positionOpener.amendOrder(clOrdID, bestPrice) onComplete (res => actorCtx.self ! RestEvent(res))
                      loop(ctx.copy(ledger = ledger2, lifecycle = IssuingAmend))
                    } else {
                      actorCtx.log.debug(s"...Issuing noop @ orderID: ${order.fullOrdID}, lifecycle: $lifecycle, data: $data")
                      loop(ctx.copy(ledger = ledger2))
                    }
                  }
                case _ =>  // catch all
                  loop(ctx.copy(ledger = ledger2))
              }
          }
      }

      // initial request
      val (clOrdID, resF) = positionOpener.openOrder(initLedger)
      resF onComplete (res => timers.startSingleTimer(RestEvent(res), 0.nanoseconds))
      loop(OpenPositionCtx(ledger = initLedger, clOrdID = clOrdID, lifecycle = IssuingNew))
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
            case (ClosePositionCtx(ledger, takeProfitClOrdID, stoplossClOrdID, lifecycle), RestEvent(Success(os: Orders))) =>
              val ledger2 = ledger.record(os)
              val takeProfitOrder = ledger2.ledgerOrdersByClOrdID.get(takeProfitClOrdID)
              val stoplossOrder = ledger2.ledgerOrdersByClOrdID.get(stoplossClOrdID)
              (takeProfitOrder, stoplossOrder) match {
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Canceled =>
                  actorCtx.log.error(s"${positionCloser.desc}: Got unexpected (external?) cancels on both takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onExternalCancels(ledger2, takeProfitClOrdID, stoplossClOrdID)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Rejected || sOrd.ordStatus == Rejected =>
                  actorCtx.log.error(s"${positionCloser.desc}: Got unexpected rejections on either takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onRejections(ledger2, tOrd, sOrd)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && sOrd.ordStatus == Canceled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: Filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} and cancelled stoploss: ${sOrd.fullOrdID} ✔✔✔")
                  positionCloser.onProfit(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Filled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: Cancelled takeProfit: ${tOrd.fullOrdID} and filled stoploss: ${sOrd.fullOrdID} @ ${sOrd.price} ✗✗✗")
                  positionCloser.onLoss(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == PostOnlyFailure || sOrd.ordStatus == PostOnlyFailure =>
                  // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
                  throw new Exception(s"PostOnlyFailure on closing position... need to deal?\ntakeProfitOrder: $takeProfitOrder\nstoplossOrder = $stoplossOrder")
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && lifecycle != IssuingCancel =>
                  actorCtx.log.info(s"${positionCloser.desc}: Filled takeProfit: ${tOrd.fullOrdID} straight away, issuing cancel on stoploss: ${sOrd.fullOrdID}")
                  positionCloser.cancelOrders(sOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
                case (Some(tOrd), Some(sOrd)) if sOrd.ordStatus == Filled && lifecycle != IssuingCancel =>
                  actorCtx.log.info(s"${positionCloser.desc}: Filled stoploss: ${sOrd.fullOrdID} straight away, issuing cancel on takeProfit: ${tOrd.fullOrdID}")
                  positionCloser.cancelOrders(tOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
                case (Some(_), Some(_)) =>
                  // some other combinations of states - keep going
                  loop(ctx.copy(ledger = ledger2))
                case _  =>
                  actorCtx.log.warn(s"${positionCloser.desc}: Unexpected RestEvent: $os\nexpected to match takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID")
                  loop(ctx.copy(ledger = ledger2))
              }
            case (ClosePositionCtx(ledger, _, _, _), RestEvent(Success(o: Order))) =>
              actorCtx.log.warn(s"${positionCloser.desc}: Unexpected RestEvent Order (expected Orders only): $o")
              val ledger2 = ledger.record(o)
              loop(ctx.copy(ledger = ledger2))
            case (ClosePositionCtx(_, takeProfitClOrdID, stoplossClOrdID, IssuingCancel), RestEvent(Failure(_: RecoverableError))) =>
              positionCloser.cancelOrders(takeProfitClOrdID, stoplossClOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
              Behaviors.same
            case (ClosePositionCtx(ledger, _, _, IssuingNew), RestEvent(Failure(_: RecoverableError))) =>
              val (takeProfitClOrdID, stoplossClOrdID, resF) = positionCloser.openOrders(ledger)
              resF onComplete (res => actorCtx.self ! RestEvent(res))
              loop(ctx.copy(ledger, takeProfitClOrdID, stoplossClOrdID))

            case (_, RestEvent(Success(_))) => Behaviors.same
            case (_, RestEvent(Failure(_: IgnorableError))) => Behaviors.same
            case (ClosePositionCtx(ledger, takeProfitClOrdID, stoplossClOrdID, lifecycle), RestEvent(Failure(exc))) => Behaviors.same
              actorCtx.log.warn(s"${positionCloser.desc}: $lifecycle: Unexpected failure of takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID", exc)
              positionCloser.onIrrecoverableError(ledger, takeProfitClOrdID, stoplossClOrdID, exc)

            case (ClosePositionCtx(ledger, takeProfitClOrdID, stoplossClOrdID, lifecycle), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              // FIXME: repetition from RestEvent!!!
              val takeProfitOrder = ledger2.ledgerOrdersByClOrdID.get(takeProfitClOrdID)
              val stoplossOrder = ledger2.ledgerOrdersByClOrdID.get(stoplossClOrdID)
              (takeProfitOrder, stoplossOrder) match {
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Canceled =>
                  actorCtx.log.error(s"${positionCloser.desc}: Got unexpected (external?) cancels on both takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onExternalCancels(ledger2, takeProfitClOrdID, stoplossClOrdID)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Rejected || sOrd.ordStatus == Rejected =>
                  actorCtx.log.error(s"${positionCloser.desc}: Got unexpected rejections on either takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onRejections(ledger2, tOrd, sOrd)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && sOrd.ordStatus == Canceled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: Filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} and cancelled stoploss: ${sOrd.fullOrdID} ✔✔✔")
                  positionCloser.onProfit(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Filled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: Cancelled takeProfit: ${tOrd.fullOrdID} and filled stoploss: ${sOrd.fullOrdID} @ ${sOrd.price} ✗✗✗")
                  positionCloser.onLoss(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == PostOnlyFailure || sOrd.ordStatus == PostOnlyFailure =>
                  // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
                  throw new Exception(s"PostOnlyFailure on closing position... need to deal?\ntakeProfitOrder: $takeProfitOrder\nstoplossOrder = $stoplossOrder")
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && lifecycle != IssuingCancel =>
                  actorCtx.log.info(s"${positionCloser.desc}: Filled takeProfit: ${tOrd.fullOrdID} straight away, issuing cancel on stoploss: ${sOrd.fullOrdID}")
                  positionCloser.cancelOrders(sOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
                case (Some(tOrd), Some(sOrd)) if sOrd.ordStatus == Filled && lifecycle != IssuingCancel =>
                  actorCtx.log.info(s"${positionCloser.desc}: Filled stoploss: ${sOrd.fullOrdID} straight away, issuing cancel on takeProfit: ${tOrd.fullOrdID}")
                  positionCloser.cancelOrders(tOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingCancel))
                case (Some(_), Some(_)) =>
                  // some other combinations of states - keep going
                  loop(ctx.copy(ledger = ledger2))
                case _  =>
                  // if not our orders or non Order(s)
                  loop(ctx.copy(ledger = ledger2))
              }
          }
        }

        // initial request
        val (takeProfitClOrdID, stoplossClOrdID, resF) = positionCloser.openOrders(initLedger)
        resF onComplete (res => timers.startSingleTimer(RestEvent(res), 0.milliseconds))
        loop(ClosePositionCtx(ledger = initLedger, takeProfitClOrdID = takeProfitClOrdID, stoplossClOrdID = stoplossClOrdID, lifecycle = IssuingNew))
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
        override def onExternalCancel(l: Ledger, clOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening clOrdID: $clOrdID")
        override def onRejection(l: Ledger, order: LedgerOrder): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening order: $order")
        override def onIrrecoverableError(l: Ledger, clOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $clOrdID", exc)
        override def bestPrice(l: Ledger): BigDecimal = l.bidPrice
        override def openOrder(l: Ledger): (String, Future[Order]) = {
          val price = l.bidPrice
          log.info(s"$desc: opening @ $price")
          restGateway.placeLimitOrderAsync(tradeQty, price, false, OrderSide.Buy)
        }
        override def cancelOrder(clOrdID: String): Future[Orders] = {
          log.info(s"$desc: cancelling orderID: $clOrdID")
          restGateway.cancelOrderAsync(clOrdIDs = Seq(clOrdID))
        }
        override def amendOrder(clOrdID: String, newPrice: BigDecimal): Future[Order] = {
          log.info(s"$desc: amending clOrdID: $clOrdID, newPrice: $newPrice")
          restGateway.amendOrderAsync(orderID=None, origClOrdID=Some(clOrdID), price=newPrice)
        }
        override def shouldKeepGoing(l: Ledger): Boolean = l.sentimentScore >= bearScoreThreshold
      }, metrics)

    def closeLong(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val desc = "Long Sell"
        override def onProfit(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onLoss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing takeProfitClOrdID: $takeProfitClOrdID or stoplossClOrdID: $stoplossClOrdID")
        override def onRejections(l: Ledger, orders: LedgerOrder*): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orders.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID", exc)
        override def openOrders(l: Ledger): (String, String, Future[Orders]) = {
          val (o1::o2::Nil, resF) = restGateway.placeBulkOrdersAsync(OrderReqs(Seq(
            OrderReq.asLimitOrder(OrderSide.Sell, tradeQty, openPrice + takeProfitMargin, true),
            OrderReq.asStopOrder(OrderSide.Sell, tradeQty, openPrice - stoplossMargin, true)))
          )
          (o1, o2, resF)
        }
        override def cancelOrders(clOrdIDs: String*): Future[Orders] = restGateway.cancelOrderAsync(clOrdIDs=clOrdIDs)
      }, metrics)

    def openShort(ledger: Ledger): Behavior[ActorEvent] =
      openPosition(ledger, new PositionOpener {
        override val desc = "Short Sell"
        override def onFilled(l: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] = closeShort(l, openPrice)
        override def onChangeOfHeart(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
        override def onExternalCancel(l: Ledger, clOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening clOrdID: $clOrdID")
        override def onRejection(l: Ledger, order: LedgerOrder): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orders: $order")
        override def onIrrecoverableError(l: Ledger, clOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $clOrdID", exc)
        override def bestPrice(l: Ledger): BigDecimal = l.askPrice
        override def openOrder(l: Ledger): (String, Future[Order]) = {
          val price = l.askPrice
          log.info(s"$desc: opening @ $price")
          restGateway.placeLimitOrderAsync(tradeQty, price, false, OrderSide.Sell)
        }
        override def cancelOrder(clOrdID: String): Future[Orders] = {
          log.info(s"$desc: cancelling orderID: $clOrdID")
          restGateway.cancelOrderAsync(clOrdIDs = Seq(clOrdID))
        }
        override def amendOrder(clOrdID: String, newPrice: BigDecimal): Future[Order] = {
          log.info(s"$desc: amending clOrdID: $clOrdID, newPrice: $newPrice")
          restGateway.amendOrderAsync(orderID=None, origClOrdID=Some(clOrdID), price=newPrice)
        }
        override def shouldKeepGoing(l: Ledger): Boolean = l.sentimentScore <= bullScoreThreshold
      }, metrics)

    def closeShort(ledger: Ledger, openPrice: BigDecimal): Behavior[ActorEvent] =
      closePosition(ledger, new PositionCloser {
        override val desc = "Short Buy"
        override def onProfit(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onLoss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(ledger))
        override def onExternalCancels(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing takeProfitClOrdID: $takeProfitClOrdID or stoplossClOrdID: $stoplossClOrdID")
        override def onRejections(l: Ledger, orders: LedgerOrder*): Behavior[ActorEvent] = throw new Exception(s"Unexpected rejection of long closing orders: ${orders.mkString(", ")}")
        override def onIrrecoverableError(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID", exc)
        override def openOrders(l: Ledger): (String, String, Future[Orders]) = {
          val (o1::o2::Nil, resF) = restGateway.placeBulkOrdersAsync(OrderReqs(Seq(
            OrderReq.asLimitOrder(OrderSide.Buy, tradeQty, openPrice - takeProfitMargin, true),
            OrderReq.asStopOrder(OrderSide.Buy, tradeQty, openPrice + stoplossMargin, true)))
          )
          (o1, o2, resF)
        }
        override def cancelOrders(clOrdIDs: String*): Future[Orders] = restGateway.cancelOrderAsync(clOrdIDs=clOrdIDs)
      }, metrics)

    assert(bullScoreThreshold > bearScoreThreshold, s"bullScoreThreshold ($bullScoreThreshold) <= bearScoreThreshold ($bearScoreThreshold)")
    init(InitCtx(ledger = Ledger()))
  }
}
