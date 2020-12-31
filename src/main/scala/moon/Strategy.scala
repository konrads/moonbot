package moon

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import moon.Sentiment._


case class StrategyResult(sentiment: Sentiment.Value, metrics: Map[String, Double])

trait Strategy {
  val log = Logger[Strategy]
  val config: Config
  def strategize(ledger: Ledger, mustPreserveState: Boolean=false): StrategyResult
}

object Strategy {
  def apply(name: String, config: Config, parentConfig: Config): Strategy = name match {
    case "permabull"  => new PermaBullStrategy(config)
  }
}

// Test strategy
class PermaBullStrategy(val config: Config) extends Strategy {
  override def strategize(ledger: Ledger, mustPreserveState: Boolean=false): StrategyResult = {
    StrategyResult(Bull, Map("data.permabull.sentiment" -> Bull.id))
  }
}
