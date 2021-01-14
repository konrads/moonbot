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

  def validate[T <: Ctx](ctx: Ctx, eCtx: ExchangeCtx, ctxClass: Class[T], orders: (OrderStatus.Value, OrderSide.Value, OrderType.Value, Double /*price*/, Double /*qty*/)*) = {
    assert(ctx.getClass == ctxClass, "ctx type mismatch")
    assert(ctx.ledger.ledgerOrdersByID.size == orders.size, "ctx ledger order size mismatch")
    assert(eCtx.orders.values.size == orders.size, "eCtx ledger order size mismatch")
    orders foreach {
      case o@(status, side, ordType, price, qty) =>
        assert(
          ctx.ledger.ledgerOrdersByID.values.count(o => o.ordStatus == status && o.side == side && o.ordType == ordType && o.price == price /* skipping as exchange holds the truth: && o.qty == qty */) == 1,
          s"cannot find $o in ctx\n${ctx.ledger.ledgerOrdersByID.values.mkString("\n")}"
        )
        assert(
          eCtx.orders.values.count(o => o.status == status && o.side == side && o.ordType == ordType && o.price.contains(price) && o.qty == qty) == 1,
          s"cannot find $o in eCtx\n${eCtx.orders.values.mkString("\n")}"
        )
    }
  }

  def runSim(initCtx: Ctx, initECtx: ExchangeCtx, strategy: TestStrategy, sentimentsAndEvents: (Sentiment.Value, String)*): (Ctx, ExchangeCtx) = {
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

    val (finalCtx, finalExchangeCtx) = sentimentsAndEvents.iterator.foldLeft((initCtx: Ctx, initECtx)) {
      case ((ctx2, exchangeCtx2), (sentiment, eventStr)) =>
        val event = WsEvent(WsModel.asModel(eventStr).get)
        strategy.sentiment = sentiment
        paperExchangeSideEffectHandler(behaviorDsl_dbg, ctx2, exchangeCtx2, None, "XBTUSD", "namespace", log, true, false, event)
    }
    (finalCtx, finalExchangeCtx)
  }

  "Orchestrator" should "work with Bull Limit orders" in {
    val initCtx = InitCtx(Ledger())
    val initECtx = ExchangeCtx()
    val strategy = new TestStrategy(Bull)
    val (ctx, eCtx) = runSim(initCtx, initECtx, strategy,
      (Bull, wsOrderBook10(10_000, 10, 10_100, 15, timestampL = startMs))
    )
    validate(ctx, eCtx, classOf[OpenPositionCtx],    (New, Buy, Limit, 10_000, 15))

    val (ctx2, eCtx2) = runSim(ctx, eCtx, strategy,
      (Bull, wsOrderBook10(11_000, 10, 11_100, 15, timestampL = startMs + `30sMs`))
    )
    validate(ctx2, eCtx2, classOf[OpenPositionCtx],  (New, Buy, Limit, 11_000, 15))

    val (ctx3, eCtx3) = runSim(ctx2, eCtx2, strategy,
      (Bull, wsOrderBook10(9_000,  10, 9_100,  15, timestampL = startMs + 2 * `30sMs`))
    )
    validate(ctx3, eCtx3, classOf[ClosePositionCtx], (Filled, Buy,  Limit, 11_000, 15),
                                                     (New,    Sell, Limit, 11_011, 15))

    val (ctx4, eCtx4) = runSim(ctx3, eCtx3, strategy,
      (Bull, wsOrderBook10(10_000, 10, 10_100, 15, timestampL = startMs + 3 * `30sMs`))
    )
    validate(ctx4, eCtx4, classOf[OpenPositionCtx],  (Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (New,    Sell, Limit, 11_011, 15),
                                                     (New,    Buy,  Limit, 10_000, 13))  // tier 1

    val (ctx5, eCtx5) = runSim(ctx4, eCtx4, strategy,
      (Bull, wsOrderBook10(15_000, 10, 15_100, 15, timestampL = startMs + 4 * `30sMs`))
    )
    validate(ctx5, eCtx5, classOf[OpenPositionCtx],  (Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (Filled, Sell, Limit, 11_011, 15),
                                                     (New,    Buy,  Limit, 15_000, 15))  // tier 0

    val (ctx6, eCtx6) = runSim(ctx5, eCtx5, strategy,
      (Bull, wsOrderBook10(16_000, 10, 16_100, 15, timestampL = startMs + 5 * `30sMs`))
    )
    validate(ctx6, eCtx6, classOf[OpenPositionCtx],  (Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (Filled, Sell, Limit, 11_011, 15),
                                                     (New,    Buy,  Limit, 16_000, 15))  // tier 0

    val (ctx7, eCtx7) = runSim(ctx6, eCtx6, strategy,
      (Bull, wsOrderBook10(15_800, 10, 15_900, 15, timestampL = startMs + 6 * `30sMs`))
    )
    validate(ctx7, eCtx7, classOf[ClosePositionCtx], (Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (Filled, Sell, Limit, 11_011, 15),
                                                     (Filled, Buy,  Limit, 16_000, 15),
                                                     (New,    Sell, Limit, 16_016, 15))  // tier 0

    val (ctx8, eCtx8) = runSim(ctx7, eCtx7, strategy,
      (Bull, wsOrderBook10(13_000, 10, 13_900, 15, timestampL = startMs + 7 * `30sMs`)),
      (Bull, wsOrderBook10(13_050, 10, 13_950, 15, timestampL = startMs + 8 * `30sMs`)),
    )
    validate(ctx8, eCtx8, classOf[OpenPositionCtx],  (Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (Filled, Sell, Limit, 11_011, 15),
                                                     (Filled, Buy,  Limit, 16_000, 15),
                                                     (New,    Sell, Limit, 16_016, 15),
                                                     (New,    Buy,  Limit, 13_050, 8))  // tier 3

    val (ctx9, eCtx9) = runSim(ctx8, eCtx8, strategy,
      (Bull, wsOrderBook10(14_000, 10, 14_900, 15, timestampL = startMs + 9 * `30sMs`)),
    )
    validate(ctx9, eCtx9, classOf[OpenPositionCtx],  (Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (Filled, Sell, Limit, 11_011, 15),
                                                     (Filled, Buy,  Limit, 16_000, 15),
                                                     (New,    Sell, Limit, 16_016, 15),
                                                     (New,    Buy,  Limit, 14_000, 11))  // moved up tier 3 -> 2

    val (ctx10, eCtx10) = runSim(ctx9, eCtx9, strategy,
      (Bull, wsOrderBook10(13_000, 10, 13_900, 15, timestampL = startMs + 10 * `30sMs`)),
      (Bull, wsOrderBook10(13_010, 10, 13_910, 15, timestampL = startMs + 11 * `30sMs`)),
    )
    validate(ctx10, eCtx10, classOf[OpenPositionCtx],(Filled, Buy,  Limit, 11_000, 15),  // tier 0
                                                     (Filled, Sell, Limit, 11_011, 15),
                                                     (Filled, Buy,  Limit, 16_000, 15),
                                                     (New,    Sell, Limit, 16_016, 15),
                                                     (Filled, Buy,  Limit, 14_000, 11),  // moved up tier 3 -> 2
                                                     (New,    Sell, Limit, 14_014, 11),
                                                     (New,    Buy,  Limit, 13_010, 4))   // tier 4
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
