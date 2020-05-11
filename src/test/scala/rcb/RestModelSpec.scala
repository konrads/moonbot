package rcb

import org.scalatest._
import org.scalatest.matchers.should._

class RestModelSpec extends FlatSpec with Matchers with Inside  {
  val restMessages =
    """
      |[{"orderID":"f4fd34c7-0748-bf70-f0b1-b61ec84e4038","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"Buy","simpleOrderQty":null,"orderQty":20,"price":8750,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"Canceled","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Canceled: Canceled via API.\nSubmitted via API.","transactTime":"2020-05-11T11:23:29.922Z","timestamp":"2020-05-11T11:27:53.507Z"}]
      |""".stripMargin

  "RestModel" should "unmarshall known Rest json messages" in {
    val res = restMessages.split("\n").filter(s => ! s.isEmpty).map(RestModel.asModel)
    print(res.mkString("\n"))
    res.filter(j => j.isError) shouldBe empty
  }
}
