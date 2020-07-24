package moon

import com.typesafe.config.ConfigFactory
import moon.ModelsSpec._
import moon.OrderSide._
import moon.OrderType._
import org.scalatest._
import org.scalatest.matchers.should._
import play.api.libs.json.JsSuccess


class LedgerSpec extends FlatSpec with Matchers with Inside {
  val bbandsStrategy = new BBandsStrategy(ConfigFactory.parseString(""))

  "Ledger" should "work with WS and REST orders" in {
    val ws1 = UpsertOrder(Some("insert"), data=Vector(
      OrderData(orderID="o1", price=Some(1), orderQty=Some(1), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-01T00:00:00.000Z"), ordStatus=Some(OrderStatus.New)),
      OrderData(orderID="o2", price=Some(2), orderQty=Some(2), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-02T00:00:00.000Z"), ordStatus=Some(OrderStatus.New)),
      OrderData(orderID="o3", price=Some(3), orderQty=Some(3), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-03T00:00:00.000Z"), ordStatus=Some(OrderStatus.New)),
    ))
    val ws2 = UpsertOrder(Some("update"), data=Vector(
      OrderData(orderID="o2", price=Some(2), orderQty=Some(2), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-02T00:00:00.000Z"), ordStatus=Some(OrderStatus.PostOnlyFailure), text=Some("had execInst of ParticipateDoNotInitiate")),
    ))
    val rest1 = Order(orderID="o1", symbol="XBTUSD", price=Some(1), orderQty=1, side=OrderSide.Buy, ordType=OrderType.Limit, timestamp=parseDateTime("2010-01-01T00:00:00.000Z"), ordStatus=Some(OrderStatus.New))
    val rest2 = Order(orderID="o2", symbol="XBTUSD", price=Some(2), orderQty=2, side=OrderSide.Buy, ordType=OrderType.Limit, timestamp=parseDateTime("2010-01-01T00:00:00.000Z"), ordStatus=Some(OrderStatus.Filled))

    val l = Ledger()
    val l2 = l.record(ws1).record(ws2)
    val l3 = l2.record(rest1).record(rest2)
    println(l3.ledgerOrders.mkString("\n"))
  }

  it should "work with buy-sell scenario" in {
    val l = buildLedger(Ledger(),
      ("rest", restOrderNew("o1", OrderSide.Buy, 10, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderNew("o1", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o1", 12, 13, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z"))
    )
    l.ledgerOrders.size shouldBe 1
    l.ledgerOrders.head.qty shouldBe 10
    l.ledgerOrders.head.price shouldBe 13
    l.ledgerOrders.head.ordStatus shouldBe OrderStatus.Filled
    l.ledgerOrders.head.myOrder shouldBe true
  }

  it should "work with postonlyerr scenario" in {
    val l = buildLedger(Ledger(),
      ("rest", restOrderNew("o1", OrderSide.Buy, 10, 10, OrderType.Limit,"2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderNew("o1", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderPostOnlyFailure("o1", OrderSide.Buy, 12, 13, "2010-01-01T00:00:00.000Z"))
    )
    l.ledgerOrders.size shouldBe 1
    l.ledgerOrders.head.ordStatus shouldBe OrderStatus.PostOnlyFailure
    l.ledgerOrders.head.myOrder shouldBe true
  }

  it should "work with cancel scenario" in {
    val l = buildLedger(Ledger(),
      ("rest", restOrderNew("o1", OrderSide.Buy, 10, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderNew("o1", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("rest", restOrderCancelled("o1", "2010-01-02T00:00:00.000Z"))
    )

    l.ledgerOrders.size shouldBe 1
    l.ledgerOrders.head.ordStatus shouldBe OrderStatus.Canceled
    l.ledgerOrders.head.timestamp shouldBe parseDateTime("2010-01-02T00:00:00.000Z")
    l.ledgerOrders.head.myOrder shouldBe true
    // quickly check map...
    l.ledgerOrdersByID.size shouldBe 1
    l.ledgerOrdersByID("o1").timestamp shouldBe parseDateTime("2010-01-02T00:00:00.000Z")
  }

  it should "not be myOrder if only ws operations" in {
    val l = buildLedger(Ledger(),
      ("ws",   wsOrderNew("o1", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderCancelled("o1", "2010-01-02T00:00:00.000Z"))
    )
    l.ledgerOrders.size shouldBe 1
    l.ledgerOrders.head.myOrder shouldBe false
  }

  it should "work for incremental and full pandl" in {
    val l = buildLedger(Ledger(),
      // o4 - hence not myOrder, not initiated by Rest...
      ("ws",   wsOrderNew("o4", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-04T06:00:00.000Z")),
      ("ws",   wsOrderFilled("o4", 12, 13, 10, OrderType.Limit, "2010-01-04T12:00:00.000Z")),
      // o3 - sell canceled
      ("rest", restOrderNew("o3", OrderSide.Sell, 10, 10, OrderType.Limit, "2010-01-03T00:00:00.000Z")),
      ("ws",   wsOrderNew("o3", OrderSide.Sell, 11, 10, OrderType.Limit, "2010-01-03T00:00:00.000Z")),
      ("ws",   wsOrderCancelled("o3", "2010-01-03T12:00:00.000Z")),
      // o2 - buy filled
      ("rest", restOrderNew("o2", OrderSide.Buy, 10, 10, OrderType.Limit, "2010-01-02T00:00:00.000Z")),
      ("ws",   wsOrderNew("o2", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-02T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o2", 12, 13, 10, OrderType.Limit, "2010-01-02T12:00:00.000Z")),
      // o1 - postonly err
      ("rest", restOrderNew("o1", OrderSide.Buy, 10, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderNew("o1", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderPostOnlyFailure("o1", OrderSide.Buy, 12, 13, "2010-01-01T00:00:00.000Z")),
    )
    l.ledgerOrders.size shouldBe 4
    l.ledgerOrders.toVector.map(_.orderID) shouldBe Vector("o4", "o3", "o2", "o1")
    l.myOrders.toVector.map(_.orderID) shouldBe Vector("o3", "o2", "o1")

    // add orderbook data
    val l2 = l.record(OrderBook("blah", "update", Vector(OrderBookData("xbtusd", parseDateTime("2001-01-01T00:00:00.000Z"), asks=Vector(Vector(10, 20), Vector(20, 30)), bids=Vector(Vector(100, 200), Vector(200, 300))))))

    val l3 = l2.withMetrics(strategy = bbandsStrategy)
    val metrics3 = l2.ledgerMetrics
    metrics3.metrics shouldBe Map.empty  // no buy/sell as yet

    // add sell and buy, note, only the sell is used to the latest pandl, as o6 buy is pair-less
    val l4 = buildLedger(l3,
      // o5 - buy filled
      ("rest", restOrderNew("o5", OrderSide.Sell, 10, 10, OrderType.Market, "2010-01-05T00:00:00.000Z")),
      ("ws",   wsOrderNew("o5", OrderSide.Sell, 11, 10, OrderType.Market, "2010-01-05T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o5", 20, 24, 10, OrderType.Market, "2010-01-05T12:00:00.000Z")),
      // o6 - buy filled
      ("rest", restOrderNew("o6", OrderSide.Buy, 10, 10, OrderType.Market, "2010-01-06T00:00:00.000Z")),
      ("ws",   wsOrderNew("o6", OrderSide.Buy, 11, 10, OrderType.Market, "2010-01-06T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o6", 8, 6, 10, OrderType.Market, "2010-01-06T12:00:00.000Z")),
    )
    l4.ledgerOrders.toVector.map(_.orderID) shouldBe Vector("o4", "o3", "o2", "o1", "o5", "o6")

    val l5 = l4.withMetrics(strategy = bbandsStrategy)
    val metrics5 = l5.ledgerMetrics
    metrics5 shouldBe LedgerMetrics(Map("data.price" -> 55.0, "data.pandl.pandl" -> 0.3524439102564103, "data.pandl.delta" -> 0.3524439102564103, "data.sentiment" -> 0.0, "data.bbands.score" -> 0.0, "data.myTradeCnt" -> 3, "data.volume" -> 0.0, "data.bbands.sentiment" -> 0.0), "o5", null, 0.3524439102564103)

    // add sell, recalculate metrics - expect no runningPandl change as not matching Buy
    val l6 = buildLedger(l5,
      // o8 - sell filled
      ("rest", restOrderNew("o7", OrderSide.Sell, 10, 10, OrderType.Market, "2010-01-07T00:00:00.000Z")),
      ("ws",   wsOrderNew("o7", OrderSide.Sell, 11, 10, OrderType.Market, "2010-01-07T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o7", 20, 24, 10, OrderType.Market, "2010-01-07T12:00:00.000Z")),
    )
    l6.ledgerOrders.toVector.map(_.orderID) shouldBe Vector("o4", "o3", "o2", "o1", "o5", "o6", "o7")
    val l7 = l6.withMetrics(strategy = bbandsStrategy)
    val metrics7 = l7.ledgerMetrics
    metrics7 shouldBe LedgerMetrics(Map("data.price" -> 55.0, "data.pandl.pandl" -> 1.6008814102564104, "data.pandl.delta" -> 1.2484375, "data.sentiment" -> 0.0, "data.bbands.score" -> 0.0, "data.myTradeCnt" -> 4, "data.volume" -> 0.0, "data.bbands.sentiment" -> 0.0), "o7", null, 1.6008814102564104)

    // add pairless buy
    val l8 = buildLedger(l7,
      // o8 - sell filled
      ("rest", restOrderNew("o8", OrderSide.Buy, 10, 10, OrderType.Market, "2010-01-07T00:00:00.000Z")),
      ("ws",   wsOrderNew("o8", OrderSide.Buy, 11, 10, OrderType.Market, "2010-01-07T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o8", 20, 24, 10, OrderType.Market, "2010-01-07T12:00:00.000Z")),
    )
    l8.ledgerOrders.toVector.map(_.orderID) shouldBe Vector("o4", "o3", "o2", "o1", "o5", "o6", "o7", "o8")
    val l9 = l8.withMetrics(strategy = bbandsStrategy)
    val metrics9 = l9.ledgerMetrics
    metrics9 shouldBe  LedgerMetrics(Map("data.price" -> 55.0, "data.pandl.pandl" -> 1.6008814102564104, "data.pandl.delta" -> 0, "data.sentiment" -> 0.0, "data.bbands.score" -> 0.0, "data.myTradeCnt" -> 5, "data.volume" -> 0, "data.bbands.sentiment" -> 0.0), "o7", null, 1.6008814102564104)
  }

  it should "do basic pandl" in {
    // do p and l on: long b & s -> long b & s -> short s & b -> short s & b -> long b & s
    import ModelsSpec._
    def addToLedger(l: Ledger, expPandl: Double, expPandlDelta: Double, orders: Seq[(String, String)]): Ledger = {
      val l2 = orders.foldLeft(l) {
        case (soFar, ("rest", order)) => soFar.record(RestModel.asModel(order).get)
        case (soFar, ("ws", order)) => soFar.record(WsModel.asModel(order).get)
      }
      val l3 = l2.withMetrics(strategy = bbandsStrategy)
      round(l3.ledgerMetrics.metrics("data.pandl.pandl").asInstanceOf[Double]) shouldBe round(expPandl)
      round(l3.ledgerMetrics.metrics("data.pandl.delta").asInstanceOf[Double]) shouldBe round(expPandlDelta)
      l3
    }

    val l0 = Ledger(orderBookSummary=OrderBookSummary("btc", parseDateTime("2010-01-01T00:00:00.000Z"), 1.0, 2.0))

    // long buy & sell, split
    val l1_1 = addToLedger(l0, 0, 0, Vector(
      ("rest", restOrderNew("o1", Buy, 10, 1, Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderAmend("o1", 100 /* ignored */, "2010-01-01T00:00:00.000Z")),
    ))
    val expPandlDelta1 = 0.0500375  // 1/10.0*1.00025-1/20.0*.99975
    val l1_2 = addToLedger(l1_1, expPandlDelta1, expPandlDelta1, Vector(
      ("ws",   wsOrderFilled("o1", 10, 10, 1, Limit, "2010-01-01T00:00:01.000Z")),
      ("rest", restOrderNew("o2", Sell, 20, 1, Limit, "2010-01-01T00:00:01.000Z")),
      ("ws",   wsOrderFilled("o2", 20, 20, 1, Limit, "2010-01-01T00:00:01.000Z")),
    ))

    // long buy & sell, no profit (except for rebate)
    val expPandlDelta2 = 0.00005  // 1/10.0*1.00025-1/10.0*.99975
    val l2 = addToLedger(l1_2, expPandlDelta1 + expPandlDelta2, expPandlDelta2, Vector(
      ("rest", restOrderNew("o3", Buy, 10, 1, Limit, "2010-01-01T00:00:03.000Z")),
      ("ws",   wsOrderFilled("o3", 10, 10, 1, Limit, "2010-01-01T00:00:03.000Z")),
      ("rest", restOrderNew("o4", Sell, 10, 1, Limit, "2010-01-01T00:00:04.000Z")),
      ("ws",   wsOrderFilled("o4", 10, 10, 1, Limit, "2010-01-01T00:00:04.000Z")),
    ))

    // short sell & buy, tiny loss due to stop
    val expPandlDelta3 = -0.00005  // -1/10.0*1.00075+1/10.0*1.00025
    val l3 = addToLedger(l2, expPandlDelta1 + expPandlDelta2 + expPandlDelta3, expPandlDelta3, Vector(
      ("rest", restOrderNew("o5", Sell, 10, 1, Limit, "2010-01-01T00:00:05.000Z")),
      ("ws",   wsOrderFilled("o5", 10, 10, 1, Limit, "2010-01-01T00:00:05.000Z")),
      ("rest", restOrderNew("o6", Buy, 10, 1, Stop, "2010-01-01T00:00:06.000Z")),
      ("ws",   wsOrderFilled("o6", 10, 10, 1, Stop, "2010-01-01T00:00:06.000Z")),
    ))

    // short sell & buy, parially
    val l4_1 = addToLedger(l3, expPandlDelta1 + expPandlDelta2 + expPandlDelta3, 0, Vector(
      ("ws",   wsOrderFilled("o7", 20, 20, 10, Limit, "2010-01-01T00:00:07.000Z")),
      ("ws",   wsOrderFilled("o8", 10, 10, 10, Stop, "2010-01-01T00:00:08.000Z")),
    ))
    val expPandlDelta4 = 0.499375  // -10/20.0*.99975+10/10.0*.99925
    val l4_2 = addToLedger(l4_1, expPandlDelta1 + expPandlDelta2 + expPandlDelta3 + expPandlDelta4, expPandlDelta4, Vector(
      ("rest", restOrderNew("o7", Sell, 20, 10, Limit, "2010-01-01T00:00:07.000Z")),
      ("rest", restOrderNew("o8", Buy, 10, 10, Stop, "2010-01-01T00:00:08.000Z")),
    ))

    // long buy & sell
    val expPandlDelta5 = -0.500625  // 10/20.0*1.00025-10/10.0*1.00075
    val l5 = addToLedger(l4_2, expPandlDelta1 + expPandlDelta2 + expPandlDelta3 + expPandlDelta4 + expPandlDelta5, expPandlDelta5, Vector(
      ("rest", restOrderNew("o9", Buy, 20, 10, Limit, "2010-01-01T00:00:09.000Z")),
      ("ws",   wsOrderFilled("o9", 20, 20, 10, Limit, "2010-01-01T00:00:09.000Z")),
      ("rest", restOrderNew("o10", Sell, 10, 10, Stop, "2010-01-01T00:00:10.000Z")),
      ("ws",   wsOrderFilled("o10", 10, 10, 10, Stop, "2010-01-01T00:00:10.000Z")),
    ))

    // no pandl diff on PostOnlyFailure and cancels
    val l6 = addToLedger(l5, expPandlDelta1 + expPandlDelta2 + expPandlDelta3 + expPandlDelta4 + expPandlDelta5, 0, Vector(
      ("rest", restOrderNew("o11", Buy, 20, 10, Limit, "2010-01-01T00:00:11.000Z")),
      ("ws",   wsOrderPostOnlyFailure("o11", Buy, 20, 10, "2010-01-01T00:00:11.000Z")),
      ("rest", restOrderPostOnlyFailure("o12", Buy, 20, 10, "2010-01-01T00:00:12.000Z")),
      ("rest", restOrderNew("o13", Sell, 10, 10, Stop, "2010-01-01T00:00:13.000Z")),
      ("ws",   wsOrderCancelled("o13", "2010-01-01T00:00:13.000Z")),
      ("rest", restOrderCancelled("o14", "2010-01-01T00:00:14.000Z")),
    ))
  }

  def buildLedger(l: Ledger, reqs: (String, String)*) =
    reqs.foldLeft(l) {
      case (l, ("rest", reqStr)) =>
        RestModel.asModel(reqStr) match {
          case JsSuccess(x:Order, _)  => l.record(x)
          case JsSuccess(x:Orders, _) => l.record(x)
          case other                  => throw new Exception(s"Failed to parse rest json: $reqStr: $other")
        }
      case (l, ("ws", reqStr))        => l.record(WsModel.asModel(reqStr).get)
    }
}
