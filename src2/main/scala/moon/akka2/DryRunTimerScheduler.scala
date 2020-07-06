package moon.akka2

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.FiniteDuration

class DryRunTimerScheduler[T](t0: Long=0) extends TimerScheduler[T] {
  var timers = Map.empty[Any, (T, FiniteDuration, Long /* nextTrigger */)]
  var time: Long = t0
  var actorRef: ActorRef[T] = _

  def withActorRef(ref: ActorRef[T]): TimerScheduler[T] = {
    actorRef = ref
    this
  }

  def setTime(ts: Long): Unit = {
    time = ts
    timers = timers.map {
      case (key, (msg, interval, nextTrigger)) =>
        if (nextTrigger > time)
          actorRef ! msg
        key -> (msg, interval, nextTrigger + interval.toMillis)
    }
  }

  override def startTimerAtFixedRate(key: Any, msg: T, interval: FiniteDuration): Unit =
    timers += key -> (msg, interval, time + interval.toMillis)

  override def isTimerActive(key: Any): Boolean = timers.contains(key)
  override def cancel(key: Any): Unit = timers -= key
  override def cancelAll(): Unit = timers = Map.empty

  // not advancing the following till needed
  override def startTimerWithFixedDelay(key: Any, msg: T, delay: FiniteDuration): Unit = ???
  override def startPeriodicTimer(key: Any, msg: T, interval: FiniteDuration): Unit = ???
  override def startSingleTimer(key: Any, msg: T, delay: FiniteDuration): Unit = ???
}
