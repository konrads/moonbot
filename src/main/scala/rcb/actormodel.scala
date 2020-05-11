package rcb

// Event
sealed trait ActorEvent

case class WsEvent(data: WsModel) extends ActorEvent

case object Timeout extends ActorEvent


// Context
// FIXME: add ledger, orderbook, trade stats, encountered orders
sealed trait ActorCtx[T] {
  val stats: Stats
  def withStats(stats: Stats): T
}

case class Stats(tsOrders: Seq[(Long, String)]) {
  def updateStats(o: InsertedOrder): Stats = ???
  def updateStats(o: UpdatedOrder): Stats = ???
  def updateStats(o: Order): Stats = ???
  def updateStats(o: OrderBook): Stats = ???
  def updateStats(o: Trade): Stats = ???
  lazy val canOpenLong: Boolean = ???
  lazy val canOpenShort: Boolean = ???
  lazy val bidPrice: BigDecimal = ???
  lazy val askPrice: BigDecimal = ???
}

case class Idle(stats: Stats=Stats(Nil)) extends ActorCtx[Idle] { def withStats(stats: Stats) = copy(stats)}

case class OpeningLongCtx(stats: Stats, orderID: String, price: Option[BigDecimal]=None, qty: Option[BigDecimal]=None, retries: Int) extends ActorCtx[OpeningLongCtx] { def withStats(stats: Stats) = copy(stats)}

case class HoldingLongCtx(stats: Stats, boughtPrice: BigDecimal, qty: BigDecimal) extends ActorCtx[HoldingLongCtx] { def withStats(stats: Stats) = copy(stats)}

case class ClosingLongCtx(stats: Stats, orderID: String, price: Option[BigDecimal], qty: Option[BigDecimal]) extends ActorCtx[ClosingLongCtx] { def withStats(stats: Stats) = copy(stats)}

case class OpeningShortCtx(stats: Stats, orderID: String, price: Option[BigDecimal]=None, qty: Option[BigDecimal]=None, retries: BigDecimal) extends ActorCtx[OpeningShortCtx] { def withStats(stats: Stats) = copy(stats)}

case class HoldingShortCtx(stats: Stats, soldPrice: BigDecimal, qty: BigDecimal) extends ActorCtx[HoldingShortCtx] { def withStats(stats: Stats) = copy(stats)}

case class ClosingShortCtx(stats: Stats, orderID: String, price: Option[BigDecimal], qty: Option[BigDecimal]) extends ActorCtx[ClosingShortCtx] { def withStats(stats: Stats) = copy(stats)}

