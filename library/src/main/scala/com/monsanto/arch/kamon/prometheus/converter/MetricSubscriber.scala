package com.monsanto.arch.kamon.prometheus.converter

import com.monsanto.arch.kamon.prometheus.metric.MetricFamily

/** Default interface to subscribe for metric updates
  *
  * @author Yegor Andreenko
  */
trait MetricSubscriber {

  def setMetrics(metrics: Seq[MetricFamily])

}
