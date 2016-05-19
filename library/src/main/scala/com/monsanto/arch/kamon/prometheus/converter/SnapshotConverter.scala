package com.monsanto.arch.kamon.prometheus.converter

import com.monsanto.arch.kamon.prometheus.PrometheusSettings
import com.monsanto.arch.kamon.prometheus.metric.{Metric, MetricFamily, MetricValue, PrometheusType}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument._
import kamon.metric.{Entity, SingleInstrumentEntityRecorder}
import kamon.util.MilliTimestamp

/** Maps metrics from Kamon to a structure suitable for Prometheus.
  *
  * Kamon tracks entities associated with one or more instruments, e.g. a
  * counter or a gauge.  In the Kamon data model, each entity is uniquely
  * identified by a category, a name, and a set of tags (arbitrary string
  * key-value mappings).  Kamon supports four different types of instruments:
  * counters, histograms, min-max counters, and gauges.  This differs from
  * Prometheus' data model.
  *
  * In Prometheus, there is no concept of a category, just metric names.
  * Prometheus also accepts something like tags, called labels.  Prometheus
  * does support several different types: counters, gauges, histograms, and
  * summaries.  In these ways, Prometheus is similar to Kamon.  However, in
  * Prometheus, each metric can only be of one type.  Additionally, metrics
  * with different labels can have the same name (in fact, labels are used to
  * support the more complex types).
  *
  * This class exists to bridge the two different data models.
  *
  * @see [[http://prometheus.io/docs/concepts/data_model/ Prometheus: Data Model]]
  * @see [[http://prometheus.io/docs/concepts/metric_types/ Prometheus: Metric Types]]
  * @see [[http://kamon.io/core/metrics/core-concepts/ Kamon: Core Concepts]]
  * @see [[http://kamon.io/core/metrics/instruments/ Kamon: Metric Recording Instruments]]
  *
  * @author Daniel Solano Gómez
  */
class SnapshotConverter(settings: PrometheusSettings) {
  import SnapshotConverter._

  /** Transforms metric snapshots before being converted. */
  val preprocess: Preprocessor = new DefaultPreprocessor
  /** Transforms metric families post-conversion. */
  val postprocess: Postprocessor = new DefaultPostprocessor

  var subscribers: Seq[MetricSubscriber] = Seq.empty

  /** Converts a metric snapshot into a sequence of metric families. */
  def apply(tick: TickMetricSnapshot): Seq[MetricFamily] = {
    type Category = String
    type Name = String

    // first, regroup data from (name, category, tags) → snapshot to category → name → [(tags, snapshot)]
    val data = for ((Entity(name, category, rawTags), snapshot) ← tick.metrics) yield {
      // also, while we are here, let’s do what we need to do to the tags
      val fullTags = rawTags ++ settings.labels + (KamonCategoryLabel → category) + (KamonNameLabel → name)
      val mungedTags = fullTags.map(entry ⇒ Mungers.asLabelName(entry._1) → entry._2)

      preprocess(MetricSnapshot(category, name, mungedTags, snapshot))
    }
    val byCategoryData = data.groupBy(_.category)

    val metrics = byCategoryData.flatMap { case (category, categoryData) ⇒
      category match {
          case SingleInstrumentEntityRecorder.Counter ⇒
            categoryData.groupBy(_.name).map { case (_, snapshots) ⇒ makeCounterMetricFamily(snapshots, tick.to) }
          case SingleInstrumentEntityRecorder.Histogram ⇒
            categoryData.groupBy(_.name).map { case (_, snapshots) ⇒ makeHistogramMetricFamily(snapshots, tick.to) }
          case SingleInstrumentEntityRecorder.MinMaxCounter ⇒
            categoryData.groupBy(_.name).map { case (_, snapshots) ⇒ makeMinMaxCounterMetricFamily(snapshots, tick.to) }
          case SingleInstrumentEntityRecorder.Gauge ⇒
            categoryData.groupBy(_.name).map { case (_, snapshots) ⇒ makeGaugeMetricFamily(snapshots, tick.to) }
          case _ ⇒
            makeArbitraryMetricFamilies(categoryData, tick.to)
      }
    }.map(postprocess(_)).toList

    subscribers.foreach(s => s.setMetrics(metrics))

    metrics
  }

  def registerSubscriber(subscriber: MetricSubscriber) = {
    subscribers = subscribers :+ subscriber
  }

  /** Builds a metric family corresponding to a counter. */
  def makeCounterMetricFamily(snapshots: Iterable[MetricSnapshot], timestamp: MilliTimestamp) = {
    assert(snapshots.nonEmpty, "A metric family requires at least one member")
    assert(snapshots.forall(_.category == SingleInstrumentEntityRecorder.Counter),
      "All snapshots must be counter snapshots.")
    assert(snapshots.map(_.name).toSet.size == 1, "All snapshots must have the same name")

    val metrics = {
      snapshots.map { metricSnapshot ⇒
        val snapshot = metricSnapshot.value
        assert(snapshot.gauges.isEmpty, "A counter should not have any gauge values")
        assert(snapshot.histograms.isEmpty, "A counter should not have any histogram values")
        assert(snapshot.minMaxCounters.isEmpty, "A counter should not have any minMaxCounter values")
        assert(snapshot.counters.size == 1, "A counter should only have one counter value")
        val value = snapshot.counter("counter")
        assert(value.isDefined, "A counter‘s value should have the name ’counter‘")
        Metric(MetricValue.Counter(value.get.count), timestamp, metricSnapshot.tags)
      }
    }

    // TODO: handle help
    val suffix = SnapshotConverter.unitSuffix(snapshots.head.value.counters.head._1.unitOfMeasurement)
    MetricFamily(Mungers.asMetricName(snapshots.head.name + suffix), PrometheusType.Counter, None, metrics.toSeq)
  }

  /** Builds a metric family corresponding to a Kamon histogram. */
  def makeHistogramMetricFamily(snapshots: Iterable[MetricSnapshot], timestamp: MilliTimestamp) = {
    assert(snapshots.nonEmpty, "A metric family requires at least one member")
    assert(snapshots.forall(_.category == SingleInstrumentEntityRecorder.Histogram),
      "All snapshots must be histogram snapshots.")
    assert(snapshots.map(_.name).toSet.size == 1, "All snapshots must have the same name")

    val metrics = {
      snapshots.map { member ⇒
        val snapshot = member.value
        assert(snapshot.gauges.isEmpty, "A histogram should not have any gauge values")
        assert(snapshot.counters.isEmpty, "A histogram should not have any counter values")
        assert(snapshot.minMaxCounters.isEmpty, "A histogram should not have any minMaxCounter values")
        assert(snapshot.histograms.size == 1, "A histogram should only have one histogram value")
        assert(snapshot.histogram("histogram").isDefined, "A histogram‘s value should have the name ’histogram‘")
        val value = MetricValue.Histogram(snapshot.histogram("histogram").get)
        Metric(value, timestamp, member.tags)
      }
    }

    val suffix = SnapshotConverter.unitSuffix(snapshots.head.value.histograms.head._1.unitOfMeasurement)
    // TODO: handle help
    MetricFamily(Mungers.asMetricName(snapshots.head.name + suffix), PrometheusType.Histogram, None, metrics.toSeq)
  }

  /** Builds a metric family corresponding to a Kamon min-max counter. */
  def makeMinMaxCounterMetricFamily(snapshots: Iterable[MetricSnapshot], timestamp: MilliTimestamp) = {
    assert(snapshots.nonEmpty, "A metric family requires at least one member")
    assert(snapshots.forall(_.category == SingleInstrumentEntityRecorder.MinMaxCounter),
      "All snapshots must be min-max counter snapshots.")
    assert(snapshots.map(_.name).toSet.size == 1, "All snapshots must have the same name")

    val metrics = snapshots.map { member ⇒
      val snapshot = member.value
      assert(snapshot.gauges.isEmpty, "A min-max counter should not have any gauge values")
      assert(snapshot.histograms.isEmpty, "A min-max counter should not have any histogram values")
      assert(snapshot.counters.isEmpty, "A min-max counter should not have any counter values")
      assert(snapshot.minMaxCounters.size == 1, "A min-max counter should only have one min-max counter value")
      assert(snapshot.minMaxCounter("min-max-counter").isDefined, "A min-max counter’s value should have the name " +
        "‘min-max-counter’")

      val value = MetricValue.Histogram(snapshot.minMaxCounter("min-max-counter").get)
      Metric(value, timestamp, member.tags)
    }

    val suffix = SnapshotConverter.unitSuffix(snapshots.head.value.minMaxCounters.head._1.unitOfMeasurement)
    // TODO: handle help
    MetricFamily(Mungers.asMetricName(snapshots.head.name + suffix), PrometheusType.Histogram, None, metrics.toSeq)
  }

  /** Builds a metric family corresponding to a Kamon gauge. */
  def makeGaugeMetricFamily(snapshots: Iterable[MetricSnapshot], timestamp: MilliTimestamp) = {
    assert(snapshots.nonEmpty, "A metric family requires at least one member")
    assert(snapshots.forall(_.category == SingleInstrumentEntityRecorder.Gauge),
      "All snapshots must be gauge snapshots.")
    assert(snapshots.map(_.name).toSet.size == 1, "All snapshots must have the same name")

    val metrics = snapshots.map { member ⇒
      val snapshot = member.value
      assert(snapshot.minMaxCounters.isEmpty, "A gauge should not have any min-max counter values")
      assert(snapshot.gauges.size == 1, "A gauge should only have one min-max counter value")
      assert(snapshot.gauge("gauge").isDefined, "A gauge’s value should have the name ‘gauge’")

      assert(snapshot.histograms.isEmpty, "A gauge should not have any histogram values")
      assert(snapshot.counters.isEmpty, "A gauge should not have any counter values")

      val value = MetricValue.Histogram(snapshot.gauge("gauge").get)
      Metric(value, timestamp, member.tags)
    }.toList

    val suffix = SnapshotConverter.unitSuffix(snapshots.head.value.gauges.head._1.unitOfMeasurement)
    // TODO: handle help
    MetricFamily(Mungers.asMetricName(snapshots.head.name + suffix), PrometheusType.Histogram, None, metrics.toSeq)
  }

  /** Constructs a list of metric families for an arbitrary entity recorder.  Since these may have more than one
    * instrument, it is necessary to rearrange the data so that we get one metric family per instrument.
    */
  def makeArbitraryMetricFamilies(snapshots: Iterable[MetricSnapshot], timestamp: MilliTimestamp): Seq[MetricFamily] = {
    assert(snapshots.nonEmpty, "Must supply at least one member")
    assert(snapshots.map(_.category).toSet.size == 1, "All snapshots must have the same category")

    val category = snapshots.head.category

    // splat out snapshots into tuples of the instrument key, tags, and instrument value.  We ignore category since it
    // is the same for all values.  We ignore names since they have been included in the tags.
    val instrumentSnapshots = for {
      snapshot ← snapshots
      (key, value) ← snapshot.value.metrics
    } yield (key, snapshot.tags, value)

    // group the data by instrument key
    val groupedInstrumentSnapshots = instrumentSnapshots.groupBy(_._1)

    // Now, create one metric family per instrument
    groupedInstrumentSnapshots.map { case (key, data) ⇒
      assert(data.nonEmpty, "There must be data!")
      assert(data.map(_._3.getClass).toSet.size == 1, "All values for a given metric key must have the same type.")

      val familyName = Mungers.asMetricName(s"${category}_${key.name}${unitSuffix(key.unitOfMeasurement)}")
      val prometheusType = data.head._3 match {
        case _: Counter.Snapshot ⇒ PrometheusType.Counter
        case _: Histogram.Snapshot ⇒ PrometheusType.Histogram
      }

      val metrics = data.map { case (_, tags, snapshot) ⇒
          snapshot match {
            case c: Counter.Snapshot ⇒ Metric(MetricValue.Counter(c.count), timestamp, tags)
            case h: Histogram.Snapshot ⇒ Metric(MetricValue.Histogram(h), timestamp, tags)
          }
      }

      MetricFamily(familyName, prometheusType, None, metrics.toSeq)
    }.toSeq
  }
}

object SnapshotConverter {
  /** Label used to report the original Kamon category to Prometheus. */
  val KamonCategoryLabel = "kamon_category"
  /** Label used to report the original Kamon name to Prometheus. */
  val KamonNameLabel = "kamon_name"

  def unitSuffix(unitOfMeasurement: UnitOfMeasurement): String = {
    unitOfMeasurement match {
      case UnitOfMeasurement.Unknown ⇒ ""
      case Time.Nanoseconds ⇒ "_nanoseconds"
      case Time.Microseconds ⇒ "_microseconds"
      case Time.Milliseconds ⇒ "_milliseconds"
      case Time.Seconds ⇒ "_seconds"
      case Memory.Bytes ⇒ "_bytes"
      case Memory.KiloBytes ⇒ "_kilobytes"
      case Memory.MegaBytes ⇒ "_megabytes"
      case Memory.GigaBytes ⇒ "_gigabytes"
      case x ⇒ "_" + x.label
    }
  }
}
