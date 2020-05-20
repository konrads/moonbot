package rcb

import org.scalatest._
import org.scalatest.matchers.should._

class LedgerSpec extends FlatSpec with Matchers with Inside {
  "Ledger" should "work with WS and REST orders" in {
    val ws1 = UpsertOrder(Some("insert"), data=Seq(
      OrderData(orderID="o1", price=Some(1), orderQty=Some(1), side=Some(OrderSide.Buy), timestamp="2010-01-01", ordStatus=Some(OrderStatus.New)),
      OrderData(orderID="o2", price=Some(2), orderQty=Some(2), side=Some(OrderSide.Buy), timestamp="2010-01-02", ordStatus=Some(OrderStatus.New)),
      OrderData(orderID="o3", price=Some(3), orderQty=Some(3), side=Some(OrderSide.Buy), timestamp="2010-01-03", ordStatus=Some(OrderStatus.New)),
    ))
    val ws2 = UpsertOrder(Some("update"), data=Seq(
      OrderData(orderID="o2", price=Some(2), orderQty=Some(2), side=Some(OrderSide.Buy), timestamp="2010-01-02", ordStatus=Some(OrderStatus.Canceled), text=Some("had execInst of ParticipateDoNotInitiate")),
    ))
    val rest1 = Order(orderID="o1", symbol="XBTUSD", price=Some(1), orderQty=1, side=OrderSide.Buy, ordType="Limit", timestamp="2010-01-01", ordStatus=Some(OrderStatus.New))
    val rest2 = Order(orderID="o2", symbol="XBTUSD", price=Some(2), orderQty=2, side=OrderSide.Buy, ordType="Limit", timestamp="2010-01-01", ordStatus=Some(OrderStatus.Filled))

    val l = Ledger()
    val l2 = l.record(ws1).record(ws2)
    val l3 = l2.record(rest1).record(rest2)
    println(l3.ledgerOrders.mkString("\n"))
  }
}
