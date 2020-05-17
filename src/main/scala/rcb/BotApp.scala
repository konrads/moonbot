package rcb

import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}


object BotApp extends App {
  private val log = Logger("BotApp")

  val conf = ConfigFactory.load()
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .withFallback(ConfigFactory.parseResources("application.private.conf"))
    .resolve()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")

  val graphiteNamespace = conf.getString("graphite.namespace")
  val graphiteHost      = conf.getString("graphite.host")
  val graphitePort      = conf.getInt("graphite.port")

  val tradeQty               = conf.getInt("bot.tradeQty")
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val openPositionExpiryMs   = conf.getLong("bot.openPositionExpiryMs")
  val closePositionExpiryMs  = conf.getLong("bot.closePositionExpiryMs")
  val backoffMs              = conf.getLong("bot.backoffMs")
  val reqRetries             = conf.getInt("bot.maxReqRetries")
  val markupRetries          = conf.getInt("bot.maxPostOnlyRetries")
  val takeProfitAmount       = conf.getDouble("bot.takeProfitAmount")
  val stoplossAmount         = conf.getDouble("bot.stoplossAmount")
  val postOnlyPriceAdjAmount = conf.getDouble("bot.postOnlyPriceAdjAmount")

  implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
  val restGateway: IRestGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs = restSyncTimeoutMs)
  val wsGateway = new WsGateWay(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)

  val orchestrator = OrchestratorActor(
    restGateway=restGateway,
    tradeQty=tradeQty,
    openPositionExpiryMs=openPositionExpiryMs, closePositionExpiryMs=closePositionExpiryMs, backoffMs=backoffMs,
    reqRetries=reqRetries, markupRetries=markupRetries,
    takeProfitAmount=takeProfitAmount, stoplossAmount=stoplossAmount, postOnlyPriceAdjAmount=postOnlyPriceAdjAmount)
  val orchestratorActor: ActorRef[ActorEvent] = ActorSystem(orchestrator, "orchestrator-actor")

  val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value:OrderBook,    _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value:UpsertOrder, _) => orchestratorActor ! WsEvent(value)
    case JsSuccess(value, _)              => log.info(s"Got orchestrator ignorable WS message: $value")
    case e:JsError                        => log.error(s"WS consume error!: $e")
  }
  wsGateway.run(wsMessageConsumer)
}
