package moon

import java.io.{File, IOException}
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress

import com.codahale.metrics.graphite.Graphite
import com.sun.management.OperatingSystemMXBean
import com.typesafe.scalalogging.Logger

// inspired by:
// https://github.com/datasift/dropwizard-scala/blob/master/metrics/src/main/scala/com/datasift/dropwizard/scala/metrics.scala
// https://gist.github.com/jkpl/1789f1feeb86f8314f32966ecf0940fa
case class Metrics(host: String, port: Int=2003, prefix: String, clock: Clock=WallClock, addJvmMetrics: Boolean=true) {
  private val log = Logger[Metrics]
  private val osBean = ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean])
  private val thisDir = new File(".")
  private val runtime = Runtime.getRuntime
  private var graphite: Graphite = null

  def gauge(gauges: Map[String, Any]): Unit = {
    val gauges2 = if (addJvmMetrics) {
      gauges +
        ("memory.used.jvm"    -> (runtime.totalMemory() - runtime.freeMemory())) +
        ("memory.used.system" -> (osBean.getTotalPhysicalMemorySize - osBean.getFreePhysicalMemorySize)) +
        ("cpu.load.jvm"       -> osBean. getProcessCpuLoad) +
        ("cpu.load.system"    -> osBean.getSystemCpuLoad) +
        ("disk.used"          -> thisDir.getUsableSpace)
    } else
      gauges

    if (gauges2.nonEmpty) {
      val now = clock.now / 1000
      // allow for connectivity issues
      if (graphite == null)
        graphite = try {
          val g = new Graphite(new InetSocketAddress(host, port))
          g.connect()
          g
        } catch {
          case _: IOException => null
        }

      if (graphite == null)
        log.warn(s"*UNSENT* metrics:\n${gauges2.map { case (k, v) => s"- $prefix.$k $v $now" }.mkString("\n")}")
      else {
        try {
          for { (k, v) <- gauges2 } graphite.send(s"$prefix.$k", v.toString, now)
          log.debug(s"Metrics:\n${gauges2.map { case (k, v) => s"- $prefix.$k $v $now" }.mkString("\n")}")
        }
        catch {
          case _:IOException =>
            log.warn(s"**UNSENT** metrics:\n${gauges2.map { case (k, v) => s"- $prefix.$k $v $now" }.mkString("\n")}")
        }
      }
    }
  }
}
