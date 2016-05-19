package com.monsanto.arch.kamon.prometheus

import akka.actor.{Actor, Props}
import akka.event.Logging
import com.monsanto.arch.kamon.prometheus.converter.{MetricSubscriber, SnapshotConverter}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot

/** An actor that receives messages from Kamon and updates the endpoint with the latest snapshot. */
class PrometheusListener(subscribers: Seq[MetricSubscriber], snapshotConverter: SnapshotConverter) extends Actor {

  private val log = Logging(context.system, this)

  subscribers.foreach(s => snapshotConverter.registerSubscriber(s))

  override def receive = {
    case tick: TickMetricSnapshot => {
      log.debug(s"Got a tick: $tick")
      snapshotConverter(tick)
    }
    case x => {
      log.warning(s"Got an $x")
    }
  }
}
object PrometheusListener {
  /** Provides the props to create a new PrometheusListener. */
  def props(subscribers: Seq[MetricSubscriber], snapshotConverter: SnapshotConverter): Props = Props(new PrometheusListener(subscribers, snapshotConverter))
}
