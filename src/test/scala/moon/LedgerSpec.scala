package moon

import org.scalatest._
import org.scalatest.matchers.should._
import play.api.libs.json.JsSuccess
import moon.ModelsSpec._

import scala.collection.SortedSet
import com.github.nscala_time.time.Imports._
import org.joda.time.DateTimeZone


class LedgerSpec extends FlatSpec with Matchers with Inside {
  "Ledger" should "work with WS and REST orders" in {
    val ws1 = UpsertOrder(Some("insert"), data=Seq(
      OrderData(orderID="o1", price=Some(1), orderQty=Some(1), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-01T00:00:00.000Z"), ordStatus=Some(OrderStatus.New)),
      OrderData(orderID="o2", price=Some(2), orderQty=Some(2), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-02T00:00:00.000Z"), ordStatus=Some(OrderStatus.New)),
      OrderData(orderID="o3", price=Some(3), orderQty=Some(3), side=Some(OrderSide.Buy), timestamp=parseDateTime("2010-01-03T00:00:00.000Z"), ordStatus=Some(OrderStatus.New)),
    ))
    val ws2 = UpsertOrder(Some("update"), data=Seq(
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
      // o1 - postonly err
      ("rest", restOrderNew("o1", OrderSide.Buy, 10, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderNew("o1", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-01T00:00:00.000Z")),
      ("ws",   wsOrderPostOnlyFailure("o1", OrderSide.Buy, 12, 13, "2010-01-01T00:00:00.000Z")),
      // o2 - buy filled
      ("rest", restOrderNew("o2", OrderSide.Buy, 10, 10, OrderType.Limit, "2010-01-02T00:00:00.000Z")),
      ("ws",   wsOrderNew("o2", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-02T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o2", 12, 13, 10, OrderType.Limit, "2010-01-02T12:00:00.000Z")),
      // o3 - sell canceled
      ("rest", restOrderNew("o3", OrderSide.Sell, 10, 10, OrderType.Limit, "2010-01-03T00:00:00.000Z")),
      ("ws",   wsOrderNew("o3", OrderSide.Sell, 11, 10, OrderType.Limit, "2010-01-03T00:00:00.000Z")),
      ("ws",   wsOrderCancelled("o3", "2010-01-03T12:00:00.000Z")),
      // o4 - not myOrder
      ("ws",   wsOrderNew("o4", OrderSide.Buy, 11, 10, OrderType.Limit, "2010-01-04T06:00:00.000Z")),
      ("ws",   wsOrderFilled("o4", 12, 13, 10, OrderType.Limit, "2010-01-04T12:00:00.000Z")),
    )
    l.ledgerOrders.size shouldBe 4
    l.ledgerOrders.toSeq.map(_.orderID) shouldBe Seq("o4", "o3", "o2", "o1")
    l.myOrders.toSeq.map(_.orderID) shouldBe Seq("o3", "o2", "o1")

    // add orderbook data
    val l2 = l.record(OrderBook("blah", "update", Seq(OrderBookData("xbtusd", parseDateTime("2001-01-01T00:00:00.000Z"), asks=Seq(Seq(10, 20), Seq(20, 30)), bids=Seq(Seq(100, 200), Seq(200, 300))))))

    val l3 = l2.withMetrics()
    val metrics3 = l2.ledgerMetrics
    metrics3 shouldBe None  // no buy/sell as yet

    // add sell and buy, recalculate metrics, expect the last buy to be ignored
    val l4 = buildLedger(l3,
      // o2 - buy filled
      ("rest", restOrderNew("o5", OrderSide.Sell, 10, 10, OrderType.Market, "2010-01-05T00:00:00.000Z")),
      ("ws",   wsOrderNew("o5", OrderSide.Sell, 11, 10, OrderType.Market, "2010-01-05T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o5", 20, 24, 10, OrderType.Market, "2010-01-05T12:00:00.000Z")),
      // o2 - buy filled
      ("rest", restOrderNew("o6", OrderSide.Buy, 10, 10, OrderType.Market, "2010-01-06T00:00:00.000Z")),
      ("ws",   wsOrderNew("o6", OrderSide.Buy, 11, 10, OrderType.Market, "2010-01-06T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o6", 8, 6, 10, OrderType.Market, "2010-01-06T12:00:00.000Z")),
    )
    l4.ledgerOrders.toSeq.map(_.orderID) shouldBe Seq("o6", "o5", "o4", "o3", "o2", "o1")

    val l5 = l4.withMetrics()
    val metrics5 = l5.ledgerMetrics
    metrics5 shouldBe Some(LedgerMetrics(Map("data.price" -> BigDecimal(55), "data.pandl" -> BigDecimal("-0.2313269230769230769230769230769231"), "data.pandlDelta" -> BigDecimal("-0.2313269230769230769230769230769231"), "data.sentimentScore" -> BigDecimal(0), "data.myTradesCnt" -> 3), parseDateTime("2010-01-05T00:00:00.000Z"), BigDecimal("-0.2313269230769230769230769230769231")))  // no buy/sell as yet

    // add sell, recalculate metrics
    val l6 = buildLedger(l5,
      // o2 - buy filled
      ("rest", restOrderNew("o7", OrderSide.Sell, 10, 10, OrderType.Market, "2010-01-07T00:00:00.000Z")),
      ("ws",   wsOrderNew("o7", OrderSide.Sell, 11, 10, OrderType.Market, "2010-01-07T00:00:00.000Z")),
      ("ws",   wsOrderFilled("o7", 20, 24, 10, OrderType.Market, "2010-01-07T12:00:00.000Z")),
    )
    val l7 = l6.withMetrics()
    val metrics7 = l7.ledgerMetrics
    metrics7 shouldBe Some(LedgerMetrics(Map("data.price" -> BigDecimal(55), "data.pandl" -> BigDecimal("-0.2328269230769230769230769230769231"), "data.pandlDelta" -> BigDecimal("-0.00150"), "data.sentimentScore" -> BigDecimal(0), "data.myTradesCnt" -> 4), parseDateTime("2010-01-07T00:00:00.000Z"), BigDecimal("-0.2328269230769230769230769230769231")))  // no buy/sell as yet
  }

  it should "order LedgerOrders desc" in {
    val set = SortedSet(
      LedgerOrder("o1",   "clo1",   12, 23, null, null, null, None, parseDateTime("2010-01-01T00:00:00.000Z"), true),
      LedgerOrder("o3",   "clo3",   12, 23, null, null, null, None, parseDateTime("2010-01-03T00:00:00.000Z"), true),
      LedgerOrder("o3.5", "clo3.5", 12, 23, null, null, null, None, parseDateTime("2010-01-03T12:00:00.000Z"), true),
      LedgerOrder("o2",   "clo2",   12, 23, null, null, null, None, parseDateTime("2010-01-02T00:00:00.000Z"), true),
    )
    set.toSeq.map(_.orderID) shouldBe Seq("o3.5", "o3", "o2", "o1")
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
