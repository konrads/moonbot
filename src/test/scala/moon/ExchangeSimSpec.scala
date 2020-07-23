package moon

import moon.ModelsSpec._
import moon.Orchestrator.{ExchangeCtx, ExchangeOrder, paperExchangeSideEffectHandler}
import moon.OrderSide._
import moon.OrderStatus._
import moon.OrderType._
import moon.Sentiment._
import org.joda.time.DateTime
import org.scalatest._
import org.scalatest.matchers.should._

class ExchangeSimSpec extends FlatSpec with Matchers with Inside {
  val log = org.slf4j.LoggerFactory.getLogger(classOf[ExchangeSim])
  val startMs = 1600000000000L
  val minMs = 60000L

  class TestStrategy(var sentiment: Sentiment.Value) extends Strategy {
    override def strategize(ledger: Ledger): StrategyResult =
      StrategyResult(sentiment, Map.empty, ledger)
  }

  def validateContains(ctx: Ctx, eCtx: ExchangeCtx, status: OrderStatus.Value, side: OrderSide.Value, ordType: OrderType.Value, price: Double, qty: Double) = {
    ctx.ledger.ledgerOrders.count(o => o.ordStatus == status && o.side == side && o.ordType == ordType && o.price == price && o.qty == qty) shouldBe 1
    eCtx.orders.values.count(o => o.status == status && o.side == side && o.ordType == ordType && o.price.contains(price) && o.qty == qty) shouldBe 1
  }

  def runSim(strategy: TestStrategy, openWithMarket: Boolean, useTrailingStoploss: Boolean, sentimentsAndEvents: (Sentiment.Value, String)*): (Ctx, ExchangeCtx) = {
    val behaviorDsl = Orchestrator.asDsl(
      strategy,
      100,
      10, 5,
      openWithMarket,
      useTrailingStoploss)

    val (finalCtx, finalExchangeCtx) = sentimentsAndEvents.iterator.foldLeft((InitCtx(Ledger()): Ctx, ExchangeCtx())) {
      case ((ctx2, exchangeCtx2), (sentiment, eventStr)) =>
        val event = WsEvent(WsModel.asModel(eventStr).get)
        strategy.sentiment = sentiment
        paperExchangeSideEffectHandler(behaviorDsl, ctx2, exchangeCtx2, None, log, true, event)
    }
    (finalCtx, finalExchangeCtx)
  }

  "Orchestrator" should "work with Bull Market orders" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bull), true, false,
      (Bull, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bull, wsTrade(120, 10, timestampL = startMs + minMs)),
      (Bull, wsOrderBook10(110, 10, 115, 15, timestampL = startMs + 2 * minMs)),
    )
    ctx.getClass shouldBe classOf[ClosePositionCtx]
    ctx.ledger.ledgerOrders.size shouldBe 3
    validateContains(ctx, eCtx, Filled, Buy, Market, 105, 100) // init
    validateContains(ctx, eCtx, New, Sell, Limit, 115, 100) // takeProfit
    validateContains(ctx, eCtx, New, Sell, Stop, 100, 100) // stoploss
  }

  it should "work with Bear Market orders" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bear), true, false,
      (Bear, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bear, wsTrade(120, 10, timestampL = startMs + minMs)),
      (Bear, wsOrderBook10(110, 10, 115, 15, timestampL = startMs + 2 * minMs)),
    )
    ctx.getClass shouldBe classOf[IdleCtx]
    ctx.ledger.ledgerOrders.size shouldBe 3
    validateContains(ctx, eCtx, Filled, Sell, Market, 100, 100) // init
    validateContains(ctx, eCtx, OrderStatus.Canceled, Buy, Limit, 90, 100) // takeProfit
    validateContains(ctx, eCtx, Filled, Buy, Stop, 105, 100) // stoploss
  }

  it should "work with vanilla Bull Stop orders" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bull), false, false,
      (Bull, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bull, wsTrade(101, 10, timestampL = startMs + minMs)),
      (Bull, wsOrderBook10(95, 10, 100, 15, timestampL = startMs + 2 * minMs)), // trigger buy, sets stoploss at...
      (Bear, wsOrderBook10(94, 10, 99,  15, timestampL = startMs + 3 * minMs)), // trigger stoploss @ 95 (100-5)
    )
    ctx.getClass shouldBe classOf[IdleCtx]
    ctx.ledger.ledgerOrders.size shouldBe 3
    validateContains(ctx, eCtx, Filled, Buy, Limit, 100, 100) // init
    validateContains(ctx, eCtx, OrderStatus.Canceled, Sell, Limit, 110, 100) // takeProfit
    validateContains(ctx, eCtx, Filled, Sell, Stop, 95, 100) // vanilla stoploss
  }

  it should "work with vanilla Bear Stop orders" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bear), false, false,
      (Bear, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bear, wsTrade(99, 10, timestampL = startMs + minMs)),
      (Bear, wsOrderBook10(105, 10, 115, 15, timestampL = startMs + 2 * minMs)), // trigger buy, sets stoploss at...
      (Bull, wsOrderBook10(106, 10, 116, 15, timestampL = startMs + 3 * minMs)), // trigger stoploss @ 105 (100+5)
    )
    ctx.getClass shouldBe classOf[IdleCtx]
    ctx.ledger.ledgerOrders.size shouldBe 3
    validateContains(ctx, eCtx, Filled, Sell, Limit, 105, 100) // init
    validateContains(ctx, eCtx, OrderStatus.Canceled, Buy, Limit, 95, 100) // takeProfit
    validateContains(ctx, eCtx, Filled, Buy, Stop, 110, 100) // vanilla stoploss
  }

  it should "work with init trailing Bull stoploss" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bull), false, true,
      (Bull, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bull, wsTrade(101, 10, timestampL = startMs + minMs)),
      (Bull, wsOrderBook10(95, 10, 100, 15, timestampL = startMs + 2 * minMs)), // trigger buy, sets trailing from the current market price (bid)
      (Bear, wsOrderBook10(104, 10, 109, 15, timestampL = startMs + 3 * minMs)),
      (Bear, wsOrderBook10(106, 10, 111, 15, timestampL = startMs + 4 * minMs)),
      (Bear, wsOrderBook10(102, 10, 107, 15, timestampL = startMs + 5 * minMs)),
      (Bear, wsOrderBook10(100, 10, 105, 15, timestampL = startMs + 7 * minMs)), // trigger stoploss @ 101 (106-5)
    )
    ctx.getClass shouldBe classOf[IdleCtx]
    ctx.ledger.ledgerOrders.size shouldBe 3
    validateContains(ctx, eCtx, Filled, Buy, Limit, 100, 100) // init
    validateContains(ctx, eCtx, OrderStatus.Canceled, Sell, Limit, 110, 100) // takeProfit
    validateContains(ctx, eCtx, Filled, Sell, Stop, 101, 100) // trailing stoploss
  }

  it should "work with init trailing Bear stoploss" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bear), false, true,
      (Bear, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bear, wsTrade(99, 10, timestampL = startMs + minMs)),
      (Bear, wsOrderBook10(105, 10, 110, 15, timestampL = startMs + 2 * minMs)), // trigger sell, sets trailing from the current market price (ask)
      (Bull, wsOrderBook10(96, 10, 101, 15, timestampL = startMs + 3 * minMs)),
      (Bull, wsOrderBook10(94, 10, 99, 15, timestampL = startMs + 4 * minMs)),
      (Bull, wsOrderBook10(98, 10, 103, 15, timestampL = startMs + 5 * minMs)),
      (Bull, wsOrderBook10(100, 10, 105, 15, timestampL = startMs + 7 * minMs)), // trigger stoploss @ 99 (94+5)
    )
    ctx.getClass shouldBe classOf[IdleCtx]
    ctx.ledger.ledgerOrders.size shouldBe 3
    validateContains(ctx, eCtx, Filled, Sell, Limit, 105, 100) // init
    validateContains(ctx, eCtx, OrderStatus.Canceled, Buy, Limit, 95, 100) // takeProfit
    validateContains(ctx, eCtx, Filled, Buy, Stop, 104, 100) // stoploss
  }

  it should "work with (escalating) Bull amendments" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bull), false, false,
      (Bull, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bull, wsTrade(105, 10, timestampL = startMs + minMs)),
      (Bull, wsOrderBook10(110, 10, 115, 15, timestampL = startMs + 2 * minMs)),
      (Bull, wsOrderBook10(115, 10, 120, 15, timestampL = startMs + 2 * minMs)),
      (Bull, wsOrderBook10(120, 10, 125, 15, timestampL = startMs + 2 * minMs)),
    )
    ctx.getClass shouldBe classOf[OpenPositionCtx]
    ctx.ledger.ledgerOrders.size shouldBe 1
    validateContains(ctx, eCtx, New, Buy, Limit, 120, 100) // init, after amendments
  }

  it should "work with (escalating) Bear amendments" in {
    val (ctx, eCtx) = runSim(new TestStrategy(Bear), false, false,
      (Bear, wsOrderBook10(100, 10, 105, 15, timestampL = startMs)),
      (Bear, wsTrade(95, 10, timestampL = startMs + minMs)),
      (Bear, wsOrderBook10(95, 10, 100, 15, timestampL = startMs + 2 * minMs)),
      (Bear, wsOrderBook10(90, 10, 95, 15, timestampL = startMs + 2 * minMs)),
      (Bear, wsOrderBook10(85, 10, 90, 15, timestampL = startMs + 2 * minMs)),
    )
    ctx.getClass shouldBe classOf[OpenPositionCtx]
    ctx.ledger.ledgerOrders.size shouldBe 1
    validateContains(ctx, eCtx, New, Sell, Limit, 90, 100) // init, after amendments
  }

  it should "work with Bull Limit orders" in {
    ???
  }

  it should "work with Bear Limit orders" in {
    ???
  }

  it should "change of heart in open" in {
    ???
  }

  it should "up/down/up/down in open" in {
    ???
  }

  it should "maybeFill" in {
    var order: ExchangeOrder = null
    // Market
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Buy, ordType=Market, status=New, price=None, trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe Some(order.copy(price=Some(15), status=Filled))
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Sell, ordType=Market, status=New, price=None, trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe Some(order.copy(price=Some(10), status=Filled))
    // Limit
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Buy, ordType=Limit, status=New, price=Some(9), trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe None
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Buy, ordType=Limit, status=New, price=Some(12), trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe None
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Buy, ordType=Limit, status=New, price=Some(16), trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe Some(order.copy(price=Some(16), status=Filled))

    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Sell, ordType=Limit, status=New, price=Some(9), trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe Some(order.copy(price=Some(9), status=Filled))
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Sell, ordType=Limit, status=New, price=Some(12), trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe None
    order = ExchangeOrder(orderID="x", clOrdID="y", qty=10, side=Sell, ordType=Limit, status=New, price=Some(16), trailingPeg=None, longHigh=None, shortLow=None, timestamp=null)
    Orchestrator.maybeFill(order, bid=10, ask=15) shouldBe None

    // vanilla Stop
    ???

    // trailing Stop
    ???
  }

  // REMOVE price amendments in opposite dir?

  // test on exchange price is triggered on opposite dir limits... do so with real life data/stage/raw data


}
