package moon

import akka.actor.typed.{ActorRef, ActorSystem}
import com.typesafe.config._
import com.typesafe.scalalogging.Logger
import org.rogach.scallop.ScallopConf
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global


object BotApp extends App {
  private implicit val log = Logger("BotApp")

  class CliConf extends ScallopConf(args) {
    val flush = opt[Boolean](default = Some(true))
    verify()
  }
  val cliConf = new CliConf()

  val conf = ConfigFactory.load()
    .withFallback(ConfigFactory.parseResources("application.conf"))
    .withFallback(ConfigFactory.parseResources("application.private.conf"))
    .resolve()

  val bitmexUrl         = conf.getString("bitmex.url")
  val bitmexWsUrl       = conf.getString("bitmex.wsUrl")
  val bitmexApiKey      = conf.getString("bitmex.apiKey")
  val bitmexApiSecret   = conf.getString("bitmex.apiSecret")

  val graphiteHost      = conf.getString("graphite.host")
  val graphitePort      = conf.getInt("graphite.port")

  val namespace              = conf.getString("bot.namespace")
  val tradeQty               = conf.getInt("bot.tradeQty")
  val minTradeVol            = conf.getInt("bot.minTradeVol")
  val restSyncTimeoutMs      = conf.getLong("bot.restSyncTimeoutMs")
  val openPositionExpiryMs   = conf.getLong("bot.openPositionExpiryMs")
  val reqRetries             = conf.getInt("bot.reqRetries")
  val markupRetries          = conf.getInt("bot.markupRetries")
  val takeProfitMargin       = conf.getDouble("bot.takeProfitMargin")
  val stoplossMargin         = conf.getDouble("bot.stoplossMargin")
  val postOnlyPriceAdj       = conf.getDouble("bot.postOnlyPriceAdj")
  val bullScoreThreshold     = conf.getDouble("bot.bullScoreThreshold")
  val bearScoreThreshold     = conf.getDouble("bot.bearScoreThreshold")

  log.info(
    s"""
      |
      | ███▄ ▄███▓ ▒█████   ▒█████   ███▄    █     ▄▄▄▄    ▒█████  ▄▄▄█████▓
      |▓██▒▀█▀ ██▒▒██▒  ██▒▒██▒  ██▒ ██ ▀█   █    ▓█████▄ ▒██▒  ██▒▓  ██▒ ▓▒
      |▓██    ▓██░▒██░  ██▒▒██░  ██▒▓██  ▀█ ██▒   ▒██▒ ▄██▒██░  ██▒▒ ▓██░ ▒░
      |▒██    ▒██ ▒██   ██░▒██   ██░▓██▒  ▐▌██▒   ▒██░█▀  ▒██   ██░░ ▓██▓ ░
      |▒██▒   ░██▒░ ████▓▒░░ ████▓▒░▒██░   ▓██░   ░▓█  ▀█▓░ ████▓▒░  ▒██▒ ░
      |░ ▒░   ░  ░░ ▒░▒░▒░ ░ ▒░▒░▒░ ░ ▒░   ▒ ▒    ░▒▓███▀▒░ ▒░▒░▒░   ▒ ░░
      |░  ░      ░  ░ ▒ ▒░   ░ ▒ ▒░ ░ ░░   ░ ▒░   ▒░▒   ░   ░ ▒ ▒░     ░
      |░      ░   ░ ░ ░ ▒  ░ ░ ░ ▒     ░   ░ ░     ░    ░ ░ ░ ░ ▒    ░
      |       ░       ░ ░      ░ ░           ░     ░          ░ ░
      |                                                 ░
      |
      |Initialized with params...
      |• bitmexUrl:            $bitmexUrl
      |• bitmexWsUrl:          $bitmexWsUrl
      |• graphiteHost:         $graphiteHost
      |• graphitePort:         $graphitePort
      |• namespace:            $namespace
      |• tradeQty:             $tradeQty
      |• minTradeVol:          $minTradeVol
      |• restSyncTimeoutMs:    $restSyncTimeoutMs
      |• openPositionExpiryMs: $openPositionExpiryMs
      |• reqRetries:           $reqRetries
      |• markupRetries:        $markupRetries
      |• takeProfitMargin:     $takeProfitMargin
      |• stoplossMargin:       $stoplossMargin
      |• postOnlyPriceAdj:     $postOnlyPriceAdj
      |""".stripMargin)

  implicit val serviceSystem: akka.actor.ActorSystem = akka.actor.ActorSystem()
  // logDeadLetters(log, serviceSystem) // log dead letters if needed
  val restGateway: IRestGateway = new RestGateway(url=bitmexUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret, syncTimeoutMs = restSyncTimeoutMs)
  if (cliConf.flush()) {
    log.info("Bootstraping via closePosition...")
    restGateway.closePositionSync()
    Thread.sleep(100)  // fire and forget, not consuming the response as it clashes with my model :(
  }
  val wsGateway = new WsGateWay(wsUrl=bitmexWsUrl, apiKey=bitmexApiKey, apiSecret=bitmexApiSecret)
  val metrics = Metrics(graphiteHost, graphitePort, namespace)

  // FIXME: need to setup actor guardian (supervisor?) to restart!

  val orchestrator = OrchestratorActor(
    restGateway=restGateway,
    tradeQty=tradeQty, minTradeVol=minTradeVol,
    openPositionExpiryMs=openPositionExpiryMs,
    bullScoreThreshold=bullScoreThreshold, bearScoreThreshold=bearScoreThreshold,
    reqRetries=reqRetries, markupRetries=markupRetries,
    takeProfitMargin=takeProfitMargin, stoplossMargin=stoplossMargin, postOnlyPriceAdj=postOnlyPriceAdj,
    metrics=Some(metrics))
  val orchestratorActor: ActorRef[ActorEvent] = ActorSystem(orchestrator, "orchestrator-actor")

  val wsMessageConsumer: PartialFunction[JsResult[WsModel], Unit] = {
    case JsSuccess(value, _) => orchestratorActor ! WsEvent(value)
    case e:JsError           => log.error("WS consume error!", e)
  }
  wsGateway.run(wsMessageConsumer)
}
