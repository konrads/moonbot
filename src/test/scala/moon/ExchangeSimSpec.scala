package moon

import com.typesafe.config.Config
import moon.Behaviour.{ExchangeCtx, ExchangeOrder, maybeFill, paperExchangeSideEffectHandler}
import moon.Dir._
import moon.ModelsSpec._
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.Sentiment._
import org.scalatest._
import org.scalatest.matchers.should._

class ExchangeSimSpec extends FlatSpec with Matchers with Inside {
  val log = org.slf4j.LoggerFactory.getLogger(classOf[ExchangeSimSpec])
  val startMs = 1600000000000L
  val minMs = 60000L
  val `30sMs` = 30000L

  class TestStrategy(var sentiment: Sentiment.Value) extends Strategy {
    override val config: Config = null
    override def strategize(ledger: Ledger): StrategyResult =
      StrategyResult(sentiment, Map.empty)
  }

  def validateContains(ctx: Ctx, eCtx: ExchangeCtx, status: OrderStatus.Value, side: OrderSide.Value, ordType: OrderType.Value, price: Double, qty: Double) = {
    ctx.ledger.ledgerOrdersByID.values.count(o => o.ordStatus == status && o.side == side && o.ordType == ordType && o.price == price && o.qty == qty) shouldBe 1
    eCtx.orders.values.count(o => o.status == status && o.side == side && o.ordType == ordType && o.price.contains(price) && o.qty == qty) shouldBe 1
  }

  def runSim(strategy: TestStrategy, sentimentsAndEvents: (Sentiment.Value, String)*): (Ctx, ExchangeCtx) = {
    val behaviorDsl = Orchestrator.asDsl(
      strategy=strategy,
      tierCalc=TierCalcImpl(dir=LongDir, tiers=Seq((0.95, 15.0), (0.9025, 13.0), (0.857, 11.0), (0.815, 8.0), (0.774, 4.0))),
      takeProfitPerc=0.001,
      dir=LongDir)

    /* FIXME: for debug only, should remove!!! */
    def behaviorDsl_dbg(ctx: Ctx, event: ActorEvent, log: org.slf4j.Logger, symbol: String): (Ctx, Option[SideEffect]) = {
      val (resCtx, resEffect) = behaviorDsl(ctx, event, log, "XBTUSD")
      println(s"##### init_ctx: ${ctx.getClass.getSimpleName}, event: $event =>\n#####  res_ctx: ${resCtx.getClass.getSimpleName}, effect: $resEffect\n")
      (resCtx, resEffect)
    }

    val (finalCtx, finalExchangeCtx) = sentimentsAndEvents.iterator.foldLeft((InitCtx(Ledger()): Ctx, ExchangeCtx())) {
      case ((ctx2, exchangeCtx2), (sentiment, eventStr)) =>
        val event = WsEvent(WsModel.asModel(eventStr).get)
        strategy.sentiment = sentiment
        paperExchangeSideEffectHandler(behaviorDsl_dbg, ctx2, exchangeCtx2, None, "XBTUSD", "namespace", log, true, false, event)
    }
    (finalCtx, finalExchangeCtx)
  }

  "Orchestrator" should "work with Bull Limit orders" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bull),
      (Bull, wsOrderBook10(10_000, 10, 10_100, 15, timestampL = startMs)),
      (Bull, wsOrderBook10(11_000, 10, 11_100, 15, timestampL = startMs + `30sMs`)),     // amend buy
      (Bull, wsOrderBook10(9_000,  10, 9_100,  15, timestampL = startMs + 2 * `30sMs`)), // execute buy @ 110 & issue sell
      (Bull, wsOrderBook10(10_000, 10, 10_100, 15, timestampL = startMs + 3 * `30sMs`)),
      (Bull, wsOrderBook10(15_000, 10, 15_100, 15, timestampL = startMs + 4 * `30sMs`)), // fill sell @ 11011
      (Bull, wsOrderBook10(16_000, 10, 16_100, 15, timestampL = startMs + 5 * `30sMs`)),
      (Bull, wsOrderBook10(15_800, 10, 15_900, 15, timestampL = startMs + 6 * `30sMs`)),
    )
    ctx.getClass shouldBe classOf[ClosePositionCtx]
    ctx.ledger.ledgerOrdersByID.size shouldBe 4
    validateContains(ctx, eCtx, Filled, Buy, Limit, 11_000, 15) // init @ 11_000
    validateContains(ctx, eCtx, Filled, Sell, Limit, 11_011, 15) // takeProfit @ 11_000 + profit
    validateContains(ctx, eCtx, Filled, Buy, Limit, 16_000, 8) // init, triggered at tier 3, price 9_000, amended up to 16_000
    validateContains(ctx, eCtx, New, Sell, Limit, 16_016, 8) // takeProfit
  }

  it should "maybeFill Market" in {
    var order: ExchangeOrder = null
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Buy, ordType = Market, status = New, price = None, trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe Some(order.copy(price = Some(15), status = Filled))
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Sell, ordType = Market, status = New, price = None, trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe Some(order.copy(price = Some(10), status = Filled))
  }

  it should "maybeFill Limit" in {
    var order: ExchangeOrder = null
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Buy, ordType = Limit, status = New, price = Some(9), trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe None
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Buy, ordType = Limit, status = New, price = Some(12), trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe None
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Buy, ordType = Limit, status = New, price = Some(16), trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe Some(order.copy(price = Some(16), status = Filled))

    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Sell, ordType = Limit, status = New, price = Some(9), trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe Some(order.copy(price = Some(9), status = Filled))
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Sell, ordType = Limit, status = New, price = Some(12), trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe None
    order = ExchangeOrder(symbol="XBTUSD", orderID = "x", clOrdID = "y", qty = 10, side = Sell, ordType = Limit, status = New, price = Some(16), trailingPeg = None, longHigh = None, shortLow = None, timestamp = null)
    maybeFill(order, bid = 10, ask = 15) shouldBe None
  }

  // REMOVE price amendments in opposite dir?

  // test on exchange price is triggered on opposite dir limits... do so with real life data/stage/raw data
}
