package moon

import java.io.IOException
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
  private val graphite = new Graphite(new InetSocketAddress(host, port))
  private val osBean = ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean])

  def gauge(gauges: Map[String, Any]): Unit = {
    val gauges2 = if (addJvmMetrics) {
      val totalMemory = Runtime.getRuntime().totalMemory()
      val freeMemory = Runtime.getRuntime().freeMemory()
      gauges +
        ("jvm.memory.total" -> totalMemory) +
        ("jvm.memory.free"  -> freeMemory) +
        ("jvm.memory.used"  -> (totalMemory - freeMemory)) +
        ("os.memory.free"   -> osBean.getFreePhysicalMemorySize) +
        ("os.cpu.process"   -> osBean.getProcessCpuLoad) +
        ("os.cpu.system"    -> osBean.getSystemCpuLoad) +
        ("os.memory.free"   -> osBean.getFreePhysicalMemorySize)
    } else
      gauges

    if (gauges2.nonEmpty) {
      val now = System.currentTimeMillis() / 1000
      try {
        graphite.connect()
        for { (k, v) <- gauges2 } graphite.send(s"$prefix.$k", v.toString, now)
      } catch {
        case exc: IOException => log.warn(s"Failed to send graphite metrics: ${gauges.mkString(", ")}", exc)
      } finally {
        try graphite.close()
        catch {
          case exc: IOException => log.warn(s"Failed to close graphite connection: ${gauges.mkString(", ")}", exc)
        }
      }
    }
  }
}
