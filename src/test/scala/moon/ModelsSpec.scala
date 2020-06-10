package moon

import org.scalatest.{FlatSpec, Inside}
import org.scalatest.matchers.should.Matchers


object ModelsSpec {
  def wsOrderNew(orderID: String, side: OrderSide.Value, price: BigDecimal, orderQty: BigDecimal, ordType: OrderType.Value, timestamp: String) = ordType match {
    case OrderType.Limit =>
      s"""{"table":"order","action":"insert","data":[{"orderID":"$orderID","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":$price,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"New","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":$orderQty,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Submission from testnet.bitmex.com","transactTime":"$timestamp","timestamp":"$timestamp"}]}""".stripMargin
    case OrderType.Market =>
      s"""{"table":"order","action":"insert","data":[{"orderID":"$orderID","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":null,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Market","timeInForce":"ImmediateOrCancel","execInst":"","contingencyType":"","exDestination":"XBME","ordStatus":"New","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":$orderQty,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Submission from testnet.bitmex.com","transactTime":"$timestamp","timestamp":"$timestamp"}]}"""
    case OrderType.Stop =>
      s"""{"table":"order","action":"insert","data":[{"orderID":"$orderID","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":null,"displayQty":null,"stopPx":$price,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Stop","timeInForce":"ImmediateOrCancel","execInst":"LastPrice","contingencyType":"","exDestination":"XBME","ordStatus":"New","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":$orderQty,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Submission from testnet.bitmex.com","transactTime":"$timestamp","timestamp":"$timestamp"}]}"""
    case other =>
      throw new Exception(s"wsOrderNew: don't need to deal with $other")
  }

  def wsOrderPostOnlyFailure(orderID: String, side: OrderSide.Value, price: BigDecimal, orderQty: BigDecimal, timestamp: String) =
    s"""{"table":"order","action":"insert","data":[{"orderID":"$orderID","clOrdID":"","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":$price,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"Canceled","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Canceled: Order had execInst of ParticipateDoNotInitiate Submission from testnet.bitmex.com","transactTime":"$timestamp","timestamp":"$timestamp"}]}"""

  def wsOrderFilled(orderID: String, price: BigDecimal, avgPx: BigDecimal /* actual price */ , cumQty: BigDecimal /* actual qty */ , ordType: OrderType.Value, timestamp: String) = ordType match {
    case OrderType.Limit =>
      s"""{"table":"order","action":"update","data":[{"orderID":"$orderID","ordStatus":"Filled","workingIndicator":false,"leavesQty":0,"cumQty":$cumQty,"avgPx":$avgPx,"timestamp":"$timestamp","clOrdID":"","account":299045,"symbol":"XBTUSD"}]}"""
    case OrderType.Market =>
      s"""{"table":"order","action":"update","data":[{"orderID":"$orderID","price":$price,"ordStatus":"Filled","leavesQty":0,"cumQty":$cumQty,"avgPx":$avgPx,"clOrdID":"","account":299045,"symbol":"XBTUSD","timestamp":"$timestamp"}]}"""
    case OrderType.Stop =>
      s"""{"table":"order","action":"update","data":[{"orderID":"$orderID","price":$price,"ordStatus":"Filled","leavesQty":0,"cumQty":$cumQty,"avgPx":$avgPx,"transactTime":"$timestamp","clOrdID":"","account":299045,"symbol":"XBTUSD","timestamp":"$timestamp"}]}"""
    case other =>
      throw new Exception(s"wsOrderFilled: don't need to deal with $other")
  }

  def wsOrderCancelled(orderID: String, timestamp: String) =
    s"""{"table":"order","action":"update","data":[{"orderID":"$orderID","ordStatus":"Canceled","workingIndicator":false,"leavesQty":0,"text":"Canceled: Cancel from testnet.bitmex.com Submission from testnet.bitmex.com","timestamp":"$timestamp","clOrdID":"","account":299045,"symbol":"XBTUSD"}]}"""

  def wsOrderAmend(orderID: String, price: BigDecimal, timestamp: String) =
    s"""{"table":"order","action":"update","data":[{"orderID":"$orderID","price":$price,"text":"Amended price: Amend from testnet.bitmex.com Submission from testnet.bitmex.com","transactTime":"$timestamp","timestamp":"$timestamp","clOrdID":"","account":299045,"symbol":"XBTUSD"}]}"""

  def restOrderNew(orderID: String, side: OrderSide.Value, price: BigDecimal = null, orderQty: BigDecimal, ordType: OrderType.Value, timestamp: String, ordStatus: OrderStatus.Value=OrderStatus.New) = ordType match {
    case OrderType.Limit =>
      s"""{"orderID":"$orderID","clOrdID":"${orderID}__balls","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":$price,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"$ordStatus","triggered":"","workingIndicator":true,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":30,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Submitted via API.","transactTime":"$timestamp","timestamp":"$timestamp"}"""
    case OrderType.Market =>
      s"""{"orderID":"$orderID","clOrdID":"${orderID}__balls","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":$price,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Market","timeInForce":"GoodTillCancel","execInst":"","contingencyType":"","exDestination":"XBME","ordStatus":"$ordStatus","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":$orderQty,"avgPx":$price,"multiLegReportingType":"SingleSecurity","text":"Submitted via API.","transactTime":"$timestamp","timestamp":"$timestamp"}"""
    case OrderType.Stop =>
      s"""{"orderID":"$orderID","clOrdID":"${orderID}__balls","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":null,"displayQty":null,"stopPx":$price,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Stop","timeInForce":"GoodTillCancel","execInst":"LastPrice","contingencyType":"","exDestination":"XBME","ordStatus":"$ordStatus","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":$orderQty,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Submitted via API.","transactTime":"$timestamp","timestamp":"$timestamp"}"""
    case other =>
      throw new Exception(s"restOrderNew: don't need to deal with $other")
  }

  def restOrderPostOnlyFailure(orderID: String, side: OrderSide.Value, orderQty: BigDecimal, price: BigDecimal, timestamp: String) =
    s"""{"orderID":"$orderID","clOrdID":"${orderID}__balls","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"$side","simpleOrderQty":null,"orderQty":$orderQty,"price":$price,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"Canceled","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Canceled: Order had execInst of ParticipateDoNotInitiate Submitted via API.","transactTime":"$timestamp","timestamp":"$timestamp"}"""

  def restOrderCancelled(orderID: String, timestamp: String) =
    s"""[{"orderID":"$orderID","clOrdID":"${orderID}__balls","clOrdLinkID":"","account":299045,"symbol":"XBTUSD","side":"Buy","simpleOrderQty":null,"orderQty":30,"price":8500,"displayQty":null,"stopPx":null,"pegOffsetValue":null,"pegPriceType":"","currency":"USD","settlCurrency":"XBt","ordType":"Limit","timeInForce":"GoodTillCancel","execInst":"ParticipateDoNotInitiate","contingencyType":"","exDestination":"XBME","ordStatus":"Canceled","triggered":"","workingIndicator":false,"ordRejReason":"","simpleLeavesQty":null,"leavesQty":0,"simpleCumQty":null,"cumQty":0,"avgPx":null,"multiLegReportingType":"SingleSecurity","text":"Canceled: Canceled via API. Submitted via API.","transactTime":"$timestamp","timestamp":"$timestamp"}]"""
}

class ModelsSpec extends FlatSpec with Matchers with Inside {
  import ModelsSpec._
  "Models" should "work parse" in {
    val restMessages = Seq(
      // New
      restOrderNew(orderID="o1", side=OrderSide.Buy,  price=11.22, orderQty=11, ordType=OrderType.Limit,  timestamp="2010-01-01T00:00:00.000Z"),
      restOrderNew(orderID="o2", side=OrderSide.Sell, price=11.22, orderQty=11, ordType=OrderType.Limit,  timestamp="2010-01-02T00:00:00.000Z"),
      restOrderNew(orderID="o3", side=OrderSide.Buy,  price=11.22, orderQty=11, ordType=OrderType.Market, timestamp="2010-01-03T00:00:00.000Z"),
      restOrderNew(orderID="o4", side=OrderSide.Sell, price=11.22, orderQty=11, ordType=OrderType.Market, timestamp="2010-01-04T00:00:00.000Z"),
      restOrderNew(orderID="o5", side=OrderSide.Buy,  price=11.22, orderQty=11, ordType=OrderType.Stop,   timestamp="2010-01-05T00:00:00.000Z"),
      restOrderNew(orderID="o6", side=OrderSide.Sell, price=11.22, orderQty=11, ordType=OrderType.Stop,   timestamp="2010-01-06T00:00:00.000Z"),
      // post only failure
      restOrderPostOnlyFailure(orderID="o11", side=OrderSide.Buy, price=22.33, orderQty=22, timestamp="2010-01-10T00:00:00.000Z"),
      // Cancel
      restOrderCancelled(orderID="o31", timestamp="2010-01-31T00:00:00.000Z"),
    )
    val wsMessages = Seq(
      // New
      wsOrderNew(orderID="o1", side=OrderSide.Buy,  price=11.22, orderQty=11, ordType=OrderType.Limit,  timestamp="2010-01-01T00:00:00.000Z"),
      wsOrderNew(orderID="o2", side=OrderSide.Sell, price=11.22, orderQty=11, ordType=OrderType.Limit,  timestamp="2010-01-02T00:00:00.000Z"),
      wsOrderNew(orderID="o3", side=OrderSide.Buy,  price=11.22, orderQty=11, ordType=OrderType.Market, timestamp="2010-01-03T00:00:00.000Z"),
      wsOrderNew(orderID="o4", side=OrderSide.Sell, price=11.22, orderQty=11, ordType=OrderType.Market, timestamp="2010-01-04T00:00:00.000Z"),
      wsOrderNew(orderID="o5", side=OrderSide.Buy,  price=11.22, orderQty=11, ordType=OrderType.Stop,   timestamp="2010-01-05T00:00:00.000Z"),
      wsOrderNew(orderID="o6", side=OrderSide.Sell, price=11.22, orderQty=11, ordType=OrderType.Stop,   timestamp="2010-01-06T00:00:00.000Z"),
      // post only failure
      wsOrderPostOnlyFailure(orderID="o11", side=OrderSide.Buy, price=22.33, orderQty=22, timestamp="2010-01-10T00:00:00.000Z"),
      // fill
      wsOrderFilled(orderID="o21", price=44.55, avgPx=44.66, cumQty=44, ordType=OrderType.Limit,  timestamp="2010-01-20T00:00:00.000Z"),
      wsOrderFilled(orderID="o22", price=44.55, avgPx=44.66, cumQty=44, ordType=OrderType.Limit,  timestamp="2010-01-20T00:00:00.000Z"),
      wsOrderFilled(orderID="o23", price=44.55, avgPx=44.66, cumQty=44, ordType=OrderType.Market, timestamp="2010-01-20T00:00:00.000Z"),
      wsOrderFilled(orderID="o24", price=44.55, avgPx=44.66, cumQty=44, ordType=OrderType.Market, timestamp="2010-01-20T00:00:00.000Z"),
      wsOrderFilled(orderID="o25", price=44.55, avgPx=44.66, cumQty=44, ordType=OrderType.Stop,   timestamp="2010-01-20T00:00:00.000Z"),
      wsOrderFilled(orderID="o26", price=44.55, avgPx=44.66, cumQty=44, ordType=OrderType.Stop,   timestamp="2010-01-20T00:00:00.000Z"),
      // Cancel
      wsOrderCancelled(orderID="o31", timestamp="2010-01-31T00:00:00.000Z"),
      // Amend
      wsOrderAmend(orderID="o41", price=55.66, timestamp="2010-01-31T00:00:00.000Z"),
    )
    val restRes = restMessages.map(RestModel.asModel)
    restRes.filter(j => j.isError) shouldBe empty
    val wsRes = wsMessages.map(WsModel.asModel)
    wsRes.filter(j => j.isError) shouldBe empty
  }
}
