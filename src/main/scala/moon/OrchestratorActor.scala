package moon

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors.Receive
import moon.OrderSide._
import moon.OrderStatus._
import moon.RunType._
import moon.Sentiment._
import moon.TradeLifecycle._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object OrchestratorActor {
  trait PositionOpener {
    val desc: String
    def onFilled(l: Ledger, openPrice: Double): Behavior[ActorEvent]                 // desired outcome - order filled
    def onChangeOfHeart(l: Ledger): Behavior[ActorEvent]                             // once we decide to stop opening (via shouldKeepGoing())
    def onExternalCancel(l: Ledger, clOrdID: String): Behavior[ActorEvent]           // unexpected cancel (not from bot)
    def onRejection(l: Ledger, order: LedgerOrder): Behavior[ActorEvent]             // unexpected rejection
    def onIrrecoverableError(l: Ledger, clOrdID: String, exc: Throwable): Behavior[ActorEvent]  // coding error???
    def bestPrice(l: Ledger): Double
    def shouldKeepGoing(l: Ledger): (Boolean, Ledger)
    def openOrder(l: Ledger): (String, Future[Order])                                // how to open order
    def cancelOrder(clOrdID: String): Future[Orders]                                 // how to cancel order
    def amendOrder(clOrdID: String, newPrice: Double): Future[Order]                 // how to open order
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

  def receiveMessage[T](eventProcessedNotifier: Option[ActorRef[EventProcessed]]=None)(onMessage: T => Behavior[T]): Receive[T] =
    Behaviors.receiveMessage[T] { event =>
      val res = onMessage(event)
      eventProcessedNotifier.foreach(_ ! EventProcessed())
      res
    }

  def openPosition(actorCtx: ActorContext[ActorEvent], initLedger: Ledger, positionOpener: PositionOpener, metrics: Option[Metrics]=None, strategy: Strategy, eventProcessedNotifier: Option[ActorRef[EventProcessed]])(implicit execCtx: ExecutionContext): Behavior[ActorEvent] = {
    def loop(ctx: OpenPositionCtx): Behavior[ActorEvent] =
      receiveMessage[ActorEvent](eventProcessedNotifier) { event =>
        (ctx, event) match {
          case (_, SendMetrics(nowMs)) =>
            val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
            metrics.foreach(_.gauge(ledger2.ledgerMetrics.metrics, nowMs))
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}::metrics lifecycle: ${ctx.lifecycle}, clOrdID: ${ctx.clOrdID}")
            loop(ctx.copy(ledger2))
          case (OpenPositionCtx(ledger, _, IssuingNew), RestEvent(Success(o: Order))) if o.clOrdID.isDefined =>
            val ledger2 = ledger.record(o)
            val order = ledger2.ledgerOrdersByClOrdID(o.clOrdID.get)
            order.ordStatus match {
              case Filled =>
                actorCtx.log.info(s"${positionOpener.desc}: filled orderID: ${order.fullOrdID} @ ${order.price}")
                positionOpener.onFilled(ledger2, order.price)
              case PostOnlyFailure =>
                actorCtx.log.warn(s"${positionOpener.desc}: PostOnlyFailure for orderID: ${order.fullOrdID}, re-issuing...")
                val (clOrdID, resF) = positionOpener.openOrder(ledger2)
                resF onComplete (res => actorCtx.self ! RestEvent(res))
                actorCtx.log.info(s"${positionOpener.desc}: re-issued order: clOrdID: $clOrdID")
                loop(ctx.copy(ledger = ledger2, clOrdID = clOrdID))
              case Canceled =>
                actorCtx.log.warn(s"${positionOpener.desc}: unexpected cancellation of orderID: ${order.fullOrdID}")
                positionOpener.onExternalCancel(ledger2, order.clOrdID)
              case _ =>
                if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}: waiting $o in ${ctx.lifecycle}")
                loop(ctx.copy(ledger = ledger2, clOrdID = order.clOrdID, lifecycle = Waiting))
            }
          case (OpenPositionCtx(ledger, _, IssuingNew), RestEvent(Failure(_: RecoverableError))) =>
            val (clOrdID, resF) = positionOpener.openOrder(ledger)
            resF onComplete (res => actorCtx.self ! RestEvent(res)) // FIXME: can I re-issue, no guarantees original hasn't gone through...
            actorCtx.log.info(s"${positionOpener.desc}: re-issued order: clOrdID: $clOrdID")
            loop(ctx.copy(clOrdID = clOrdID))
          case (OpenPositionCtx(ledger, clOrdID, IssuingOpenCancel), RestEvent(Success(os: Orders))) if os.containsClOrdIDs(clOrdID) =>
            val ledger2 = ledger.record(os)
            val order = ledger2.ledgerOrdersByClOrdID(ctx.clOrdID)
            order.ordStatus match {
              case Canceled =>
                actorCtx.log.info(s"${positionOpener.desc}: canceled due to change of heart, orderID: ${order.fullOrdID}")
                positionOpener.onChangeOfHeart(ledger2)
              case Filled =>
                actorCtx.log.info(s"${positionOpener.desc}: filled orderID: ${order.fullOrdID} @ ${order.price} (even though had change of heart)")
                positionOpener.onFilled(ledger2, order.price)
              case _ =>
                if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}: unexpected $os in ${ctx.lifecycle}")
                loop(ctx.copy(ledger = ledger2, lifecycle = Waiting)) // unexpected, ignore...
            }
          case (OpenPositionCtx(_, clOrdID, IssuingOpenCancel), RestEvent(Failure(_: RecoverableError))) =>
            positionOpener.cancelOrder(clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
            Behaviors.same
          case (OpenPositionCtx(ledger, clOrdID, IssuingOpenAmend), RestEvent(Success(o: Order))) if o.clOrdID.contains(clOrdID) =>
            val ledger2 = ledger.record(o)
            val order = ledger2.ledgerOrdersByClOrdID(clOrdID)
            order.ordStatus match {
              case Filled =>
                actorCtx.log.info(s"${positionOpener.desc}: filled (upon amend) orderID: ${order.fullOrdID} @ ${order.price}")
                positionOpener.onFilled(ledger2, order.price)
              case PostOnlyFailure =>
                val (clOrdID2, resF) = positionOpener.openOrder(ledger2)
                resF onComplete (res => actorCtx.self ! RestEvent(res))
                actorCtx.log.warn(s"${positionOpener.desc}: PostOnlyFailure (upon amend) for orderID: ${order.fullOrdID}, re-issuing...")
                loop(ctx.copy(ledger = ledger2, clOrdID = clOrdID2, lifecycle = IssuingNew))
              case Canceled =>
                actorCtx.log.warn(s"${positionOpener.desc}: unexpected cancellation of orderID: ${order.fullOrdID}")
                positionOpener.onExternalCancel(ledger2, order.orderID)
              case other => // presumingly amended
                if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}: catchall: $other in lifecycle: ${ctx.lifecycle}, order: $o")
                loop(ctx.copy(ledger = ledger2, lifecycle = Waiting))
            }
          case (OpenPositionCtx(ledger, clOrdID, IssuingOpenAmend), RestEvent(Failure(_: RecoverableError))) =>
            val bestPrice = positionOpener.bestPrice(ledger)
            positionOpener.amendOrder(clOrdID, bestPrice) onComplete (res => actorCtx.self ! RestEvent(res))
            actorCtx.log.info(s"${positionOpener.desc}: re-issued amend to order: clOrdID: $clOrdID @ $bestPrice")
            Behaviors.same

          // unexpected, but catered for REST interactions. Might occur when REST looses race to WS
          case (OpenPositionCtx(ledger, _, _), RestEvent(Success(data))) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}: unexpected RestEvent, recording: $data")
            loop(ctx.copy(ledger = ledger.record(data)))
          case (_, RestEvent(Success(_))) => Behaviors.same
          case (_, RestEvent(Failure(_: IgnorableError))) => Behaviors.same
          case (OpenPositionCtx(ledger, clOrdID, lifecycle), RestEvent(Failure(exc))) => Behaviors.same
            actorCtx.log.warn(s"${positionOpener.desc}: $lifecycle: unexpected failure of clOrdID: $clOrdID", exc)
            positionOpener.onIrrecoverableError(ledger, clOrdID, exc)

          case (OpenPositionCtx(ledger, clOrdID, lifecycle), WsEvent(data)) =>
            val ledger2 = ledger.record(data)
            val orderOpt = ledger2.ledgerOrdersByClOrdID.get(clOrdID)
            val clOrdIDMatch = orderOpt.exists(_.clOrdID == clOrdID)
            (orderOpt, lifecycle, clOrdIDMatch) match {
              case (Some(order), _, true) if order.ordStatus == Filled =>
                actorCtx.log.info(s"${positionOpener.desc}: filled orderID: ${order.fullOrdID} @ ${order.price}")
                positionOpener.onFilled(ledger2, order.price)
              case (Some(order), IssuingOpenCancel, true) if order.ordStatus == Canceled =>
                actorCtx.log.info(s"${positionOpener.desc}: canceled2 due to change of heart, orderID: ${order.orderID}")
                positionOpener.onChangeOfHeart(ledger2)
              case (Some(order), _, true) if order.ordStatus == Canceled =>
                actorCtx.log.warn(s"${positionOpener.desc}: unexpected cancellation of orderID: ${order.orderID} in lifecycle: $lifecycle")
                positionOpener.onExternalCancel(ledger2, order.orderID)
              case (Some(order), _, true) if order.ordStatus == Rejected =>
                actorCtx.log.warn(s"${positionOpener.desc}: rejection of orderID: ${order.orderID} in lifecycle: $lifecycle")
                positionOpener.onRejection(ledger2, order)
              case (Some(order), Waiting, _) =>
                // will sentiment force a change of heart?
                val (shouldKeepGoing, ledger3) = positionOpener.shouldKeepGoing(ledger2)
                if (! shouldKeepGoing) {
                  actorCtx.log.info(s"${positionOpener.desc}: having a change of heart, cancelling ${order.fullOrdID}...")
                  positionOpener.cancelOrder(clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  loop(ctx.copy(ledger = ledger3, lifecycle = IssuingOpenCancel))
                } else {
                  // need to update best price?
                  val bestPrice = positionOpener.bestPrice(ledger3)
                  if (order.price != bestPrice) {
                    actorCtx.log.info(s"${positionOpener.desc}: best price moved, will change: ${order.price} -> $bestPrice")
                    positionOpener.amendOrder(clOrdID, bestPrice) onComplete (res => actorCtx.self ! RestEvent(res))
                    loop(ctx.copy(ledger = ledger3, lifecycle = IssuingOpenAmend))
                  } else {
                    if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}: noop @ orderID: ${order.fullOrdID}, lifecycle: $lifecycle, data: $data")
                    loop(ctx.copy(ledger = ledger3))
                  }
                }
              case other => // catch all
                if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionOpener.desc}: catchall: $other in lifecycle: $lifecycle, data: $data")
                loop(ctx.copy(ledger = ledger2))
            }
        }
      }

      // initial request
      val (clOrdID, resF) = positionOpener.openOrder(initLedger)
      resF onComplete (res => actorCtx.self ! RestEvent(res))
      // actorCtx.log.info(s"${positionOpener.desc}: Initial opening clOrdID: $clOrdID")
      loop(OpenPositionCtx(ledger = initLedger, clOrdID = clOrdID, lifecycle = IssuingNew))
    }

  def closePosition(actorCtx: ActorContext[ActorEvent], initLedger: Ledger, positionCloser: PositionCloser, metrics: Option[Metrics]=None, strategy: Strategy, eventProcessedNotifier: Option[ActorRef[EventProcessed]])(implicit execCtx: ExecutionContext): Behavior[ActorEvent] = {
      def loop(ctx: ClosePositionCtx): Behavior[ActorEvent] =
        receiveMessage[ActorEvent](eventProcessedNotifier) { wsEvent =>
          (ctx, wsEvent) match {
            case (_, SendMetrics(nowMs)) =>
              val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
              metrics.foreach(_.gauge(ledger2.ledgerMetrics.metrics, nowMs))
              if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionCloser.desc}::metrics takeProfitClOrdID: lifecycle: ${ctx.lifecycle}, ${ctx.takeProfitClOrdID}, stoplossClOrdID: ${ctx.stoplossClOrdID}")
              loop(ctx.copy(ledger2))
            case (ClosePositionCtx(ledger, takeProfitClOrdID, stoplossClOrdID, lifecycle), RestEvent(Success(os: Orders))) =>
              val ledger2 = ledger.record(os)
              val takeProfitOrder = ledger2.ledgerOrdersByClOrdID.get(takeProfitClOrdID)
              val stoplossOrder = ledger2.ledgerOrdersByClOrdID.get(stoplossClOrdID)
              (takeProfitOrder, stoplossOrder) match {
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Canceled =>
                  actorCtx.log.error(s"${positionCloser.desc}: unexpected (external?) cancels on both takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onExternalCancels(ledger2, takeProfitClOrdID, stoplossClOrdID)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Rejected || sOrd.ordStatus == Rejected =>
                  actorCtx.log.error(s"${positionCloser.desc}: unexpected rejections on either takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onRejections(ledger2, tOrd, sOrd)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && sOrd.ordStatus == Canceled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: ✔✔✔ filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} and cancelled stoploss: ${sOrd.fullOrdID} ✔✔✔")
                  positionCloser.onProfit(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Filled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: ✗✗✗ cancelled takeProfit: ${tOrd.fullOrdID} and filled stoploss: ${sOrd.fullOrdID} @ ${sOrd.price} ✗✗✗")
                  positionCloser.onLoss(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == PostOnlyFailure || sOrd.ordStatus == PostOnlyFailure =>
                  // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
                  throw new Exception(s"PostOnlyFailure on closing position... need to deal?\ntakeProfitOrder: $takeProfitOrder\nstoplossOrder = $stoplossOrder")
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && lifecycle != IssuingOpenCancel =>
                  positionCloser.cancelOrders(sOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  actorCtx.log.info(s"${positionCloser.desc}: filled takeProfit: ${tOrd.fullOrdID} straight away, issuing cancel on stoploss: ${sOrd.fullOrdID}")
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingOpenCancel))
                case (Some(tOrd), Some(sOrd)) if sOrd.ordStatus == Filled && lifecycle != IssuingOpenCancel =>
                  positionCloser.cancelOrders(tOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  actorCtx.log.info(s"${positionCloser.desc}: filled stoploss: ${sOrd.fullOrdID} straight away, issuing cancel on takeProfit: ${tOrd.fullOrdID}")
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingOpenCancel))
                case (Some(_), Some(_)) =>
                  // some other combinations of states - keep going
                  loop(ctx.copy(ledger = ledger2))
                case _  =>
                  if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionCloser.desc}: unexpected RestEvent: $os\nexpected to match takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID")
                  loop(ctx.copy(ledger = ledger2))
              }
            case (_, RestEvent(Success(other))) =>
              if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionCloser.desc}: unexpected RestEvent, recording: $other")
              loop(ctx.copy(ledger = ctx.ledger.record(other)))
            case (ClosePositionCtx(_, takeProfitClOrdID, stoplossClOrdID, IssuingOpenCancel), RestEvent(Failure(_: RecoverableError))) =>
              positionCloser.cancelOrders(takeProfitClOrdID, stoplossClOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
              actorCtx.log.warn(s"${positionCloser.desc}: re-issuing cancel on takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID")
              Behaviors.same
            case (ClosePositionCtx(ledger, _, _, IssuingNew), RestEvent(Failure(exc: RecoverableError))) =>
              val (takeProfitClOrdID, stoplossClOrdID, resF) = positionCloser.openOrders(ledger)
              resF onComplete (res => actorCtx.self ! RestEvent(res))
              actorCtx.log.info(s"${positionCloser.desc}: re-issued orders: takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID, due to err: $exc")
              loop(ctx.copy(ledger, takeProfitClOrdID, stoplossClOrdID))

            case (_, RestEvent(Failure(_: IgnorableError))) => Behaviors.same
            case (ClosePositionCtx(ledger, takeProfitClOrdID, stoplossClOrdID, lifecycle), RestEvent(Failure(exc))) => Behaviors.same
              actorCtx.log.warn(s"${positionCloser.desc}: $lifecycle: Uuexpected failure of takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID", exc)
              positionCloser.onIrrecoverableError(ledger, takeProfitClOrdID, stoplossClOrdID, exc)

            case (ClosePositionCtx(ledger, takeProfitClOrdID, stoplossClOrdID, lifecycle), WsEvent(data)) =>
              val ledger2 = ledger.record(data)
              // FIXME: repetition from RestEvent!!!
              val takeProfitOrder = ledger2.ledgerOrdersByClOrdID.get(takeProfitClOrdID)
              val stoplossOrder = ledger2.ledgerOrdersByClOrdID.get(stoplossClOrdID)
              (takeProfitOrder, stoplossOrder) match {
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Canceled =>
                  actorCtx.log.error(s"${positionCloser.desc}: unexpected (external?) cancels on both takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onExternalCancels(ledger2, takeProfitClOrdID, stoplossClOrdID)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Rejected || sOrd.ordStatus == Rejected =>
                  actorCtx.log.error(s"${positionCloser.desc}: rejections on either takeProfit: ${tOrd.fullOrdID} and stoploss: ${sOrd.fullOrdID}")
                  positionCloser.onRejections(ledger2, tOrd, sOrd)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && sOrd.ordStatus == Canceled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: ✔✔✔ filled takeProfit: ${tOrd.fullOrdID} @ ${tOrd.price} and cancelled stoploss: ${sOrd.fullOrdID} ✔✔✔")
                  positionCloser.onProfit(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Canceled && sOrd.ordStatus == Filled  =>
                  actorCtx.log.info(s"${positionCloser.desc}: ✗✗✗ cancelled takeProfit: ${tOrd.fullOrdID} and filled stoploss: ${sOrd.fullOrdID} @ ${sOrd.price} ✗✗✗")
                  positionCloser.onLoss(ledger2)
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == PostOnlyFailure || sOrd.ordStatus == PostOnlyFailure =>
                  // FIXME: not dealing with PostOnlyFailure, in presumption that margins will always be large enough. Otherwise, will need IssueAmend cycle
                  throw new Exception(s"PostOnlyFailure on closing position... need to deal?\ntakeProfitOrder: $takeProfitOrder\nstoplossOrder = $stoplossOrder")
                case (Some(tOrd), Some(sOrd)) if tOrd.ordStatus == Filled && lifecycle != IssuingOpenCancel =>
                  positionCloser.cancelOrders(sOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  actorCtx.log.info(s"${positionCloser.desc}: filled takeProfit: ${tOrd.fullOrdID}, issuing cancel on stoploss: ${sOrd.fullOrdID}")
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingOpenCancel))
                case (Some(tOrd), Some(sOrd)) if sOrd.ordStatus == Filled && lifecycle != IssuingOpenCancel =>
                  positionCloser.cancelOrders(tOrd.clOrdID) onComplete (res => actorCtx.self ! RestEvent(res))
                  actorCtx.log.info(s"${positionCloser.desc}: filled stoploss: ${sOrd.fullOrdID}, issuing cancel on takeProfit: ${tOrd.fullOrdID}")
                  loop(ctx.copy(ledger = ledger2, lifecycle = IssuingOpenCancel))
                case (Some(tOrd), Some(sOrd)) =>
                  // some other combinations of states - keep going
                  if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionCloser.desc}: new state of takeProfitOrder: $tOrd, stoplossOrder: $sOrd, in lifecycle: $lifecycle, data: $data")
                  loop(ctx.copy(ledger = ledger2))
                case other  =>
                  // if not our orders or non Order(s)
                  if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"${positionCloser.desc}: catchall: $other in lifecycle: $lifecycle, data: $data")
                  loop(ctx.copy(ledger = ledger2))
              }
          }
        }

        // initial request
        val (takeProfitClOrdID, stoplossClOrdID, resF) = positionCloser.openOrders(initLedger)
        resF onComplete (res => actorCtx.self ! RestEvent(res))
        // actorCtx.log.info(s"${positionCloser.desc}: issued initial orders: takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID")
        loop(ClosePositionCtx(ledger = initLedger, takeProfitClOrdID = takeProfitClOrdID, stoplossClOrdID = stoplossClOrdID, lifecycle = IssuingNew))
      }


  def apply(strategy: Strategy,
            flushSessionOnRestart: Boolean=true,
            restGateway: IRestGateway,
            tradeQty: Int, minTradeVol: Double,
            openPositionExpiryMs: Long,
            reqRetries: Int, markupRetries: Int,
            takeProfitMargin: Double, stoplossMargin: Double, postOnlyPriceAdj: Double,
            metrics: Option[Metrics]=None,
            openWithMarket: Boolean=false,
            eventProcessedNotifier: Option[ActorRef[EventProcessed]]=None,
            runType: RunType.Value=Live)(implicit execCtx: ExecutionContext): Behavior[ActorEvent] = {

    Behaviors.setup[ActorEvent] { actorCtx =>
      Behaviors.withTimers[ActorEvent] { timers =>
        if (flushSessionOnRestart && runType == Live) {
          actorCtx.log.info("init: Bootstraping via closePosition/orderCancels...")
          // not consuming the response as it clashes with my model :(. Just assumes to have worked
          for {
            res1 <- restGateway.closePositionAsync()
            res2 <- restGateway.cancelAllOrdersAsync()
          } yield (res1, res2)
        }

        /**
         * Gather enough WS data to trade, then switch to idle
         */
        def init(ctx: InitCtx): Behavior[ActorEvent] = receiveMessage[ActorEvent](eventProcessedNotifier) {
          case SendMetrics(nowMs) =>
            if (ctx.ledger.isMinimallyFilled) {
              val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
              metrics.foreach(_.gauge(ledger2.ledgerMetrics.metrics, nowMs))
              init(ctx.copy(ledger2))
            } else
              Behaviors.same
          case WsEvent(data) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"init: WsEvent: $data")
            val ledger2 = ctx.ledger.record(data)
            if (ledger2.isMinimallyFilled) {
              timers.startTimerAtFixedRate(SendMetrics(None), 1.minute)
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
          case RestEvent(Success(data)) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"init: RestEvent: $data")
            init(ctx.copy(ledger = ctx.ledger.record(data)))
          case RestEvent(Failure(exc)) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"init: unexpected failure: $exc", exc)
            Behaviors.same
        }

        /**
         * Waiting for market conditions to change to bull or bear
         */
        def idle(ctx: IdleCtx): Behavior[ActorEvent] = receiveMessage[ActorEvent](eventProcessedNotifier) {
          case SendMetrics(nowMs) =>
            val ledger2 = ctx.ledger.withMetrics(strategy = strategy)
            metrics.foreach(_.gauge(ledger2.ledgerMetrics.metrics, nowMs))
            idle(ctx.copy(ledger2))
          case WsEvent(wsData) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"idle: WsEvent: $wsData")
            val ledger2 = ctx.ledger.record(wsData)
            val strategyRes = strategy.strategize(ledger2)
            val (sentiment, ledger3) = (strategyRes.sentiment, strategyRes.ledger)
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"idle: Sentiment is $sentiment")
            if (sentiment == Bull && runType != Dry)
              openLong(ledger3)
            else if (sentiment == Bear && runType != Dry)
              openShort(ledger3)
            else
              idle(ctx.copy(ledger = ledger3))

          case RestEvent(Success(data)) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"idle: unexpected RestEvent: $data")
            idle(ctx.copy(ledger = ctx.ledger.record(data)))
          case RestEvent(Failure(exc)) =>
            if (actorCtx.log.isDebugEnabled) actorCtx.log.debug(s"idle: unexpected Rest failure: $exc", exc)
            Behaviors.same
        }

        def openLong(ledger: Ledger): Behavior[ActorEvent] =
          openPosition(actorCtx, ledger, new PositionOpener {
            override val desc = "Long Open"
            override def onFilled(l: Ledger, openPrice: Double): Behavior[ActorEvent] = closeLong(l, openPrice)
            override def onChangeOfHeart(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
            override def onExternalCancel(l: Ledger, clOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening clOrdID: $clOrdID")
            override def onRejection(l: Ledger, order: LedgerOrder): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening order: $order")
            override def onIrrecoverableError(l: Ledger, clOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long opening orderID: $clOrdID", exc)
            override def bestPrice(l: Ledger): Double = l.bidPrice
            override def openOrder(l: Ledger): (String, Future[Order]) = {
              val price = l.bidPrice
              val clOrdID = uuid
              val resF = if (openWithMarket)
                restGateway.placeMarketOrderAsync(tradeQty, Buy, Some(clOrdID))
              else
                restGateway.placeLimitOrderAsync(tradeQty, price, false, Buy, Some(clOrdID))
              actorCtx.log.info(s"$desc: opening $clOrdID @ $price, isMarket: $openWithMarket")
              (clOrdID, resF)
            }
            override def cancelOrder(clOrdID: String): Future[Orders] = {
              // actorCtx.log.info(s"$desc: cancelling clOrdID: $clOrdID")
              restGateway.cancelOrderAsync(clOrdIDs = Vector(clOrdID))
            }
            override def amendOrder(clOrdID: String, newPrice: Double): Future[Order] = {
              actorCtx.log.info(s"$desc: amending clOrdID: $clOrdID, newPrice: $newPrice")
              restGateway.amendOrderAsync(orderID=None, origClOrdID=Some(clOrdID), price=newPrice)
            }
            override def shouldKeepGoing(l: Ledger): (Boolean, Ledger) = {
              val strategyRes = strategy.strategize(l)
              val (sentiment, l2) = (strategyRes.sentiment, strategyRes.ledger)
              (sentiment == Bull, l2)
            }
          }, metrics, strategy, eventProcessedNotifier)

        def closeLong(ledger: Ledger, openPrice: Double): Behavior[ActorEvent] =
          closePosition(actorCtx, ledger, new PositionCloser {
            override val desc = "Long Close"
            override def onProfit(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
            override def onLoss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
            override def onExternalCancels(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing takeProfitClOrdID: $takeProfitClOrdID or stoplossClOrdID: $stoplossClOrdID")
            override def onRejections(l: Ledger, orders: LedgerOrder*): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing orderID: ${orders.mkString(", ")}")
            override def onIrrecoverableError(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of long closing takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID", exc)
            override def openOrders(l: Ledger): (String, String, Future[Orders]) = {
              val takeProfitLimit = openPrice + takeProfitMargin
              val (takeProfitClOrdID, stoplossClOrdID) = (uuid, uuid)
              val resF = restGateway.placeBulkOrdersAsync(OrderReqs(Vector(
                OrderReq.asLimitOrder(Sell, tradeQty, takeProfitLimit, true, clOrdID=Some(takeProfitClOrdID)),
                OrderReq.asTrailingStopOrder(Sell, tradeQty, stoplossMargin, true, clOrdID=Some(stoplossClOrdID))))
                // or for market stop: OrderReq.asStopOrder(Sell, tradeQty, openPrice - stoplossMargin, true)))
              )
              actorCtx.log.info(s"$desc: closing with takeProfit: $takeProfitClOrdID @ $takeProfitLimit, trailing stoploss: $stoplossClOrdID @ $stoplossMargin")
              (takeProfitClOrdID, stoplossClOrdID, resF)
            }
            override def cancelOrders(clOrdIDs: String*): Future[Orders] = restGateway.cancelOrderAsync(clOrdIDs=clOrdIDs)
          }, metrics, strategy, eventProcessedNotifier)

        def openShort(ledger: Ledger): Behavior[ActorEvent] =
          openPosition(actorCtx, ledger, new PositionOpener {
            override val desc = "Short Open"
            override def onFilled(l: Ledger, openPrice: Double): Behavior[ActorEvent] = closeShort(l, openPrice)
            override def onChangeOfHeart(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
            override def onExternalCancel(l: Ledger, clOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening clOrdID: $clOrdID")
            override def onRejection(l: Ledger, order: LedgerOrder): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orders: $order")
            override def onIrrecoverableError(l: Ledger, clOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short opening orderID: $clOrdID", exc)
            override def bestPrice(l: Ledger): Double = l.askPrice
            override def openOrder(l: Ledger): (String, Future[Order]) = {
              val price = l.askPrice
              val clOrdID = uuid
              val resF = if (openWithMarket)
                restGateway.placeMarketOrderAsync(tradeQty, Sell, Some(clOrdID))
              else
                restGateway.placeLimitOrderAsync(tradeQty, price, false, Sell, Some(clOrdID))
              actorCtx.log.info(s"$desc: opening $clOrdID @ $price, isMarket: $openWithMarket")
              (clOrdID, resF)
            }
            override def cancelOrder(clOrdID: String): Future[Orders] = {
              // actorCtx.log.info(s"$desc: cancelling clOrdID: $clOrdID")
              restGateway.cancelOrderAsync(clOrdIDs = Vector(clOrdID))
            }
            override def amendOrder(clOrdID: String, newPrice: Double): Future[Order] = {
              actorCtx.log.info(s"$desc: amending clOrdID: $clOrdID, newPrice: $newPrice")
              restGateway.amendOrderAsync(orderID=None, origClOrdID=Some(clOrdID), price=newPrice)
            }
            override def shouldKeepGoing(l: Ledger): (Boolean, Ledger) = {
              val strategyRes = strategy.strategize(l)
              val (sentiment, l2) = (strategyRes.sentiment, strategyRes.ledger)
              (sentiment == Bear, l2)
            }
          }, metrics, strategy, eventProcessedNotifier)

        def closeShort(ledger: Ledger, openPrice: Double): Behavior[ActorEvent] =
          closePosition(actorCtx, ledger, new PositionCloser {
            override val desc = "Short Close"
            override def onProfit(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
            override def onLoss(l: Ledger): Behavior[ActorEvent] = idle(IdleCtx(l))
            override def onExternalCancels(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing takeProfitClOrdID: $takeProfitClOrdID or stoplossClOrdID: $stoplossClOrdID")
            override def onRejections(l: Ledger, orders: LedgerOrder*): Behavior[ActorEvent] = throw new Exception(s"Unexpected rejection of long closing orders: ${orders.mkString(", ")}")
            override def onIrrecoverableError(l: Ledger, takeProfitClOrdID: String, stoplossClOrdID: String, exc: Throwable): Behavior[ActorEvent] = throw new Exception(s"Unexpected cancellation of short closing takeProfitClOrdID: $takeProfitClOrdID, stoplossClOrdID: $stoplossClOrdID", exc)
            override def openOrders(l: Ledger): (String, String, Future[Orders]) = {
              val takeProfitLimit = openPrice - takeProfitMargin
              val (takeProfitClOrdID, stoplossClOrdID) = (uuid, uuid)
              val resF = restGateway.placeBulkOrdersAsync(OrderReqs(Vector(
                OrderReq.asLimitOrder(Buy, tradeQty, takeProfitLimit, true, Some(takeProfitClOrdID)),
                OrderReq.asTrailingStopOrder(Buy, tradeQty, stoplossMargin, true, Some(takeProfitClOrdID))))
                // or for market stop: OrderReq.asStopOrder(Buy, tradeQty, openPrice + stoplossMargin, true)))
              )
              actorCtx.log.info(s"$desc: closing with takeProfit: $takeProfitClOrdID @ $takeProfitLimit, trailing stoploss: $stoplossClOrdID @ $stoplossMargin")
              (takeProfitClOrdID, stoplossClOrdID, resF)
            }
            override def cancelOrders(clOrdIDs: String*): Future[Orders] = restGateway.cancelOrderAsync(clOrdIDs=clOrdIDs)
          }, metrics, strategy, eventProcessedNotifier)

        init(InitCtx(ledger = Ledger()))
      }
    }
  }
}
