package rcb

import org.scalatest._
import org.scalatest.matchers.should._

class RestModelSpec extends FlatSpec with Matchers with Inside  {
  val restMessages =
    """
      |[{"orderID":"f4fd34c7-0748-bf70-f0b1-b61ec84e4038","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"Buy","simpleOrderQty":null,"orderQty":20,"price":8750,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"Canceled","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Canceled: Canceled via API.\nSubmitted via API.","transactTime":"2020-05-11T11:23:29.922Z","timestamp":"2020-05-11T11:27:53.507Z"}]
      |{"orderID":"09b4e6d2-8cef-a036-3255-98de6b8d28eb","clOrdID":"5c4ae76f-4e18-4b3e-b41d-83191875936d","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"Sell","simpleOrderQty":null,"orderQty":30,"price":null,"displayQty":null,"stopPx":9400,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Stop","timeInForce":"GoodTillCancel","execInst":"","contingencyType":"","exDestination":"XBME","ordStatus":"New","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":30,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Submitted via API.","transactTime":"2020-05-15T04:14:42.796Z","timestamp":"2020-05-15T04:14:42.796Z"}
      |""".stripMargin

  "RestModel" should "unmarshall known Rest json messages" in {
    val res = restMessages.split("\n").filter(s => ! s.isEmpty).map(RestModel.asModel)
    print(res.mkString("\n"))
    res.filter(j => j.isError) shouldBe empty
  }
}
