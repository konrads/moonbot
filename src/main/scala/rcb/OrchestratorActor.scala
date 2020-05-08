package rcb

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

sealed trait OrchestratorModel

case class NotifyWs(msg: WsModel) extends OrchestratorModel
case class NotifyHttp(msg: String) extends OrchestratorModel

object OrchestratorActor {
  def apply(): Behavior[OrchestratorModel] =
    Behaviors.receive {
      case (context, NotifyWs(ob:OrderBook)) =>
        context.log.info("got ws message OrderBook: " + ob)
        Behaviors.same
      case (context, NotifyWs(o:UpdatedOrder)) =>
        context.log.info("got ws message UpdatedOrder: " + o)
        Behaviors.same
    }
}
