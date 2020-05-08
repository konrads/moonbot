package rcb

import org.scalatest._
import org.scalatest.matchers.should._

class WsModelSpec extends FlatSpec with Matchers with Inside  {
  val wsMessages =
    """
      |{"info":"Welcome to the BitMEX Realtime API.","version":"2020-04-29T23:19:10.000Z","timestamp":"2020-05-07T19:00:51.147Z","docs":"https://testnet.bitmex.com/app/wsAPI","limit":{"remaining":39}}
      |{"success":true,"subscribe":"orderBook10:XBTUSD","request":{"op":"subscribe","args":"orderBook10:XBTUSD"}}
      |{"success":true,"subscribe":"funding:XBTUSD","request":{"op":"subscribe","args":"funding:XBTUSD"}}
      |{"table":"orderBook10","action":"partial","keys":["symbol"],"types":{"symbol":"symbol","bids":"","asks":"","timestamp":"timestamp"},"foreignKeys":{"symbol":"instrument"},"attributes":{"symbol":"sorted"},"filter":{"symbol":"XBTUSD"},"data":[{"symbol":"XBTUSD","bids":[[9770,35719],[9769,31],[9768,50],[9767.5,40],[9765,30],[9764,30],[9763,282],[9762,100],[9760,100],[9759,30]],"asks":[[9770.5,101196],[9771,2095],[9772.5,50],[9773,783],[9773.5,169],[9774,996],[9774.5,167],[9775,3680],[9775.5,353],[9776,198]],"timestamp":"2020-05-07T19:00:47.235Z"}]}
      |{"table":"funding","action":"partial","keys":["timestamp","symbol"],"types":{"timestamp":"timestamp","symbol":"symbol","fundingInterval":"timespan","fundingRate":"float","fundingRateDaily":"float"},"foreignKeys":{"symbol":"instrument"},"attributes":{"timestamp":"sorted","symbol":"grouped"},"filter":{"symbol":"XBTUSD"},"data":[{"timestamp":"2020-05-07T12:00:00.000Z","symbol":"XBTUSD","fundingInterval":"2000-01-01T08:00:00.000Z","fundingRate":-0.00164,"fundingRateDaily":-0.00492}]}
      | {"success":true,"request":{"op":"authKey","args":["KTl_zNoXSONbWlFgRU_6PoiY",1588878047418,"9C5069075AD313F764B2CF41CAFC483EDEC49BD8A68C3BD2414CB134E45F971B"]}}
      | {"success":true,"subscribe":"order:XBTUSD","request":{"op":"subscribe","args":["order:XBTUSD"]}}
      | {"table":"order","action":"partial","keys":["orderID"],"types":{"orderID":"guid","clOrdID":"string","clOrdLinkID":"symbol","account":"long","symbol":"symbol","side":"symbol","simpleOrderQty":"float","orderQty":"long","price":"float","displayQty":"long","stopPx":"float","pegOffsetValue":"float","pegPriceType":"symbol","currency":"symbol","settlCurrency":"symbol","ordType":"symbol","timeInForce":"symbol","execInst":"symbol","contingencyType":"symbol","exDestination":"symbol","ordStatus":"symbol","triggered":"symbol","workingIndicator":"boolean","ordRejReason":"symbol","simpleLeavesQty":"float","leavesQty":"long","simpleCumQty":"float","cumQty":"long","avgPx":"float","multiLegReportingType":"symbol","text":"string","transactTime":"timestamp","timestamp":"timestamp"},"foreignKeys":{"symbol":"instrument","side":"side","ordStatus":"ordStatus"},"attributes":{"orderID":"grouped","account":"grouped","ordStatus":"grouped","workingIndicator":"grouped"},"filter":{"account":299045,"symbol":"XBTUSD"},"data":[]}
      | {"table":"orderBook10","action":"update","data":[{"symbol":"XBTUSD","asks":[[9770.5,101196],[9771,2095],[9772.5,50],[9773,756],[9773.5,169],[9774,996],[9774.5,167],[9775,3680],[9775.5,353],[9776,198]],"timestamp":"2020-05-07T19:00:53.458Z","bids":[[9770,35719],[9769,31],[9768,50],[9767.5,40],[9765,30],[9764,30],[9763,282],[9762,100],[9760,100],[9759,30]]}]}
      | {"table":"orderBook10","action":"update","data":[{"symbol":"XBTUSD","asks":[[9770.5,101196],[9771,2095],[9772.5,50],[9773,756],[9773.5,244],[9774,996],[9774.5,167],[9775,3680],[9775.5,353],[9776,198]],"timestamp":"2020-05-07T19:00:53.671Z","bids":[[9770,35719],[9769,31],[9768,50],[9767.5,40],[9765,30],[9764,30],[9763,282],[9762,100],[9760,100],[9759,30]]}]}
      |{"status":401,"error":"Signature not valid.","meta":{},"request":{"op":"authKey","args":["KTl_zNoXSONbWlFgRU_6PoiY",1588893594859,"1628B48174CAA478B05047066D059DD06647D159C900442E3AFD8CA89080F621"]}}
      |""".stripMargin

  "WsModel" should "unmarshall known WS json messages" in {
    val res = wsMessages.split("\n").filter(s => ! s.isEmpty).map(WsModel.asModel)
    print(res.mkString("\n"))
    res.filter(j => j.isError) shouldBe empty
  }
}
