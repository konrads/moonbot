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
case class Metrics(host: String, port: Int=2003, prefix: String, addJvmMetrics: Boolean=true) {
  private val log = Logger[Metrics]
  private val osBean = ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean])
  private val thisDir = new File(".")
  private val runtime = Runtime.getRuntime
  private var graphite: Graphite = null

  def gauge(gauges: Map[String, Any], nowMs: Option[Long]): Unit = {
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
      val now = nowMs.getOrElse(System.currentTimeMillis) / 1000
      // allow for connectivity issues
      /* FIXME: attempt to re-initialize the socket to avoid data loss */ // if (graphite != null) { graphite.close(); graphite = null }
      if (graphite == null)
        graphite = try {
          val g = new Graphite(new InetSocketAddress(host, port))
          g.connect()
          g
        } catch {
          case exc: IOException =>
            log.warn(s"Failed to connect to graphite at $host:$port due to ${exc.getClass.getSimpleName}: ${exc.getMessage}")
            null
        }

      if (graphite == null)
        log.warn(s"*UNSENT* metrics:\n${gauges2.map { case (k, v) => s"- $prefix.$k $v $now" }.mkString("\n")}")
      else {
        try {
          for { (k, v) <- gauges2 } graphite.send(s"$prefix.$k", v.toString, now)
          if (graphite.getFailures > 0)
            log.warn(s"Failed to send to graphite: ${graphite.getFailures}")
          log.debug(s"Metrics:\n${gauges2.map { case (k, v) => s"- $prefix.$k $v $now" }.mkString("\n")}")
        }
        catch {
          case exc:IOException =>
            log.warn(s"**UNSENT** metrics:\n${gauges2.map { case (k, v) => s"- $prefix.$k $v $now" }.mkString("\n")} due to ${exc.getClass.getSimpleName}: ${exc.getMessage}")
            graphite = null
        }
      }
    }
  }
}
