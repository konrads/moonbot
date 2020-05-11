package rcb

import org.scalatest._
import org.scalatest.matchers.should._

class WsModelSpec extends FlatSpec with Matchers with Inside  {
  val wsMessages =
    """
      |{"success":true,"request":{"op":"authKey","args":["xxxx",1589034691432,"yyy"]}}
      |{"info":"Welcome to the BitMEX Realtime API.","version":"2020-04-29T23:19:10.000Z","timestamp":"2020-05-07T19:00:51.147Z","docs":"https://testnet.bitmex.com/app/wsAPI","limit":{"remaining":39}}
      |{"success":true,"subscribe":"orderBook10:XBTUSD","request":{"op":"subscribe","args":"orderBook10:XBTUSD"}}
      |{"table":"orderBook10","action":"partial","keys":["symbol"],"types":{"symbol":"symbol","bids":"","asks":"","timestamp":"timestamp"},"foreignKeys":{"symbol":"instrument"},"attributes":{"symbol":"sorted"},"filter":{"symbol":"XBTUSD"},"data":[{"symbol":"XBTUSD","bids":[[9770,35719],[9769,31],[9768,50],[9767.5,40],[9765,30],[9764,30],[9763,282],[9762,100],[9760,100],[9759,30]],"asks":[[9770.5,101196],[9771,2095],[9772.5,50],[9773,783],[9773.5,169],[9774,996],[9774.5,167],[9775,3680],[9775.5,353],[9776,198]],"timestamp":"2020-05-07T19:00:47.235Z"}]}
      | {"success":true,"request":{"op":"authKey","args":["KTl_zNoXSONbWlFgRU_6PoiY",1588878047418,"9C5069075AD313F764B2CF41CAFC483EDEC49BD8A68C3BD2414CB134E45F971B"]}}
      | {"success":true,"subscribe":"order:XBTUSD","request":{"op":"subscribe","args":["order:XBTUSD"]}}
      | {"table":"order","action":"partial","keys":["orderID"],"types":{"orderID":"guid","clOrdID":"string","clOrdLinkID":"symbol","account":"long","symbol":"symbol","side":"symbol","simpleOrderQty":"float","orderQty":"long","price":"float","displayQty":"long","stopPx":"float","pegOffsetValue":"float","pegPriceType":"symbol","currency":"symbol","settlCurrency":"symbol","ordType":"symbol","timeInForce":"symbol","execInst":"symbol","contingencyType":"symbol","exDestination":"symbol","ordStatus":"symbol","triggered":"symbol","workingIndicator":"boolean","ordRejReason":"symbol","simpleLeavesQty":"float","leavesQty":"long","simpleCumQty":"float","cumQty":"long","avgPx":"float","multiLegReportingType":"symbol","text":"string","transactTime":"timestamp","timestamp":"timestamp"},"foreignKeys":{"symbol":"instrument","side":"side","ordStatus":"ordStatus"},"attributes":{"orderID":"grouped","account":"grouped","ordStatus":"grouped","workingIndicator":"grouped"},"filter":{"account":299045,"symbol":"XBTUSD"},"data":[]}
      | {"table":"orderBook10","action":"update","data":[{"symbol":"XBTUSD","asks":[[9770.5,101196],[9771,2095],[9772.5,50],[9773,756],[9773.5,169],[9774,996],[9774.5,167],[9775,3680],[9775.5,353],[9776,198]],"timestamp":"2020-05-07T19:00:53.458Z","bids":[[9770,35719],[9769,31],[9768,50],[9767.5,40],[9765,30],[9764,30],[9763,282],[9762,100],[9760,100],[9759,30]]}]}
      | {"table":"orderBook10","action":"update","data":[{"symbol":"XBTUSD","asks":[[9770.5,101196],[9771,2095],[9772.5,50],[9773,756],[9773.5,244],[9774,996],[9774.5,167],[9775,3680],[9775.5,353],[9776,198]],"timestamp":"2020-05-07T19:00:53.671Z","bids":[[9770,35719],[9769,31],[9768,50],[9767.5,40],[9765,30],[9764,30],[9763,282],[9762,100],[9760,100],[9759,30]]}]}
      |{"status":401,"error":"Signature not valid.","meta":{},"request":{"op":"authKey","args":["KTl_zNoXSONbWlFgRU_6PoiY",1588893594859,"1628B48174CAA478B05047066D059DD06647D159C900442E3AFD8CA89080F621"]}}
      |{"table":"order","action":"insert","data":[{"orderID":"a6e58256-ef3d-08c2-c3f3-1446f28fa86b","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"Buy","simpleOrderQty":null,"orderQty":20,"price":9675,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"Canceled","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Canceled: Order had execInst of ParticipateDoNotInitiate\nSubmitted via API.","transactTime":"2020-05-09T13:33:06.552Z","timestamp":"2020-05-09T13:33:06.552Z"}]}
      |{"table":"trade","action":"insert","data":[{"timestamp":"2020-05-09T14:31:39.239Z","symbol":"XBTUSD","side":"Sell","size":12,"price":9672.5,"tickDirection":"ZeroMinusTick","trdMatchID":"ae5e6be1-5e64-ac0c-612b-b571e12f637a","grossValue":124068,"homeNotional":0.00124068,"foreignNotional":12}]}
      |""".stripMargin

  "WsModel" should "unmarshall known WS json messages" in {
    val res = wsMessages.split("\n").filter(s => ! s.isEmpty).map(WsModel.asModel)
    print(res.mkString("\n"))
    res.filter(j => j.isError) shouldBe empty
  }
}
