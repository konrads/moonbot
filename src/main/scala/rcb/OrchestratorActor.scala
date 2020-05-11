package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object OrchestratorActor {
  def apply(restGateway: RestGateway, tradeQty: Int, tradeTimeoutSecs: Int, holdTimeoutSecs: Int, maxRetries: Int): Behavior[ActorEvent] = {

    /**
     * Idle, ie. not long/short related state. Monitor WsEvents and instantiate trades via REST.
     */
    def idle(ctx: Idle): Behavior[ActorEvent] = Behaviors.receivePartial[ActorEvent] {
      case (actorCtx, WsEvent(wsData)) =>
        val stats2 = updateStats(wsData, ctx.stats)
        if (stats2.canOpenLong)
          // instantiate restOrder sync
          restGateway.placeOrderSync(stats2.bidPrice, tradeQty, OrderSide.Buy) match {
            case scala.util.Success(o) =>
              val stats3 = stats2.updateStats(o)
              openingLong(OpeningLongCtx(stats3, orderID=o.orderID, retries = maxRetries))
            case scala.util.Failure(exc) =>
              actorCtx.log.error(s"REST bid error within Orchestrator: $exc")
              idle(Idle(stats2))
          }
        else if (stats2.canOpenShort)
          restGateway.placeOrderSync(stats2.askPrice, tradeQty, OrderSide.Sell) match {
            case scala.util.Success(o) =>
              val stats3 = stats2.updateStats(o)
              openingShort(OpeningShortCtx(stats3, orderID=o.orderID, retries = maxRetries))
            case scala.util.Failure(exc) =>
              actorCtx.log.error(s"REST ask error within Orchestrator: $exc")
              idle(Idle(stats2))
          }
        else
          idle(ctx.withStats(stats2))
    }

    def openingLong(ctx: OpeningLongCtx): Behavior[ActorEvent] = Behaviors.withTimers[ActorEvent] { timers =>
      timers.startSingleTimer(Timeout, Duration(holdTimeoutSecs, SECONDS))
      Behaviors.receivePartial[ActorEvent] {
        case (actorCtx, Timeout) =>
          ???
          // order expired, go back to idle
//          restGateway.cancelOrderSync(ctx.orderID) match {
//            case scala.util.Success(os) => os
//
//          }
        case (actorCtx, WsEvent(wsData)) =>
          val stats2 = updateStats(wsData, ctx.stats)

          openingLong(ctx.withStats(stats=stats2))
      }
    }
    def holdingLong(ctx: HoldingLongCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] { ??? }
    def closingLong(ctx: ClosingLongCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] { ??? }

    def openingShort(ctx: OpeningShortCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] { ??? }
    def holdingShort(ctx: HoldingShortCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] { ??? }
    def closingShort(ctx: ClosingShortCtx): Behavior[ActorEvent] = Behaviors.receiveMessagePartial[ActorEvent] { ??? }

    idle(Idle())
  }

  def updateStats(wsModel: WsModel, stats: Stats) = wsModel match {
    case o:OrderBook => stats.updateStats(o)
    case o:Trade => stats.updateStats(o)
    case o:InsertedOrder => stats.updateStats(o)
    case o:UpdatedOrder => stats.updateStats(o)
    case _ => stats
  }


//  def apply(): Behavior[OrchestratorModel] =
//    Behaviors.receive {
//      case (context, NotifyWs(ob:OrderBook)) =>
//        context.log.info(s"got ws message OrderBook: $ob")
//        Behaviors.same
//      case (context, NotifyWs(o:UpdatedOrder)) =>
//        context.log.info(s"got ws message UpdatedOrder: $o")
//        Behaviors.same
//      case (context, NotifyWs(msg)) =>
//        context.log.error(s"Unexpected WS message: $msg")
//        Behaviors.same
//    }
}
