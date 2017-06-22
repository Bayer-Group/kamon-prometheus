package com.monsanto.arch.kamon.prometheus.spray

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorDSL._
import akka.actor.{ActorRefFactory, ExtendedActorSystem}
import akka.event.Logging
import com.monsanto.arch.kamon.prometheus.metric.{MetricFamily, ProtoBufFormat, TextFormat}
import com.monsanto.arch.kamon.prometheus.spray.PrometheusEndpoint.{ProtoBufContentType, TextContentType}
import com.monsanto.arch.kamon.prometheus.{Prometheus, PrometheusExtension}
import spray.http._
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.{Directives, Route}

import scala.util.{Failure, Success}

/** Manages the Spray endpoint that Prometheus can use to scrape metrics.
  *
  * @author Daniel Solano Gómez
  */
private[spray] class PrometheusEndpoint(val system: ExtendedActorSystem) extends SprayEndpoint {
  implicit val arf: ActorRefFactory = system

  /** Mutable cell with the latest snapshot. */
  private[spray] val snapshot = new AtomicReference[Seq[MetricFamily]]

  /** Marshals a snapshot to the text exposition format. */
  private val textMarshaller: ToResponseMarshaller[Seq[MetricFamily]] =
    ToResponseMarshaller.delegate[Seq[MetricFamily], String](TextContentType)(TextFormat.format _)

  /** Marshals a snapshot to the protocol buffer exposition format. */
  private val protoBufMarshaller: ToResponseMarshaller[Seq[MetricFamily]] =
    ToResponseMarshaller.delegate[Seq[MetricFamily], Array[Byte]](ProtoBufContentType)(ProtoBufFormat.format _)

  /** Marshals a snapshot depending on content negotiation. */
  private implicit val marshaller: ToResponseMarshaller[Seq[MetricFamily]] =
    ToResponseMarshaller.oneOf(TextContentType, ProtoBufContentType)(textMarshaller, protoBufMarshaller)

  /** Provides a basic route that responds to GET requests with the metrics in a Prometheus-compatible exposition
    * format.  Note that if there is no snapshot information available, this will respond with a No Content
    * response.
    */
  val route: Route = {
    import Directives._

    get {
      compressResponseIfRequested() {
        dynamic {
          Option(snapshot.get) match {
            case Some(s) ⇒ complete(s)
            case None    ⇒ complete(StatusCodes.NoContent)
          }
        }
      }
    }
  }

  private val updater = actor(new Act {
    become {
      case PrometheusExtension.Snapshot(s) ⇒ snapshot.set(s)
    }
  })

  Prometheus.kamonInstance.onComplete { result ⇒
    val log = Logging(system, classOf[SprayEndpoint])
    result match {
      case Success(ext) ⇒
        log.debug("SprayEndpoint is subscribing to Kamon Prometheus updates")
        ext.ref.tell(PrometheusExtension.Subscribe, updater)
      case Failure(t) ⇒
        log.error(t, "Kamon Prometheus module failed to load, SprayEndpoint will never serve data")
    }
  }(system.dispatcher)
}

private object PrometheusEndpoint {
  /** The media type for the Prometheus text-based exposition format. */
  val TextMediaType = MediaTypes.register(
    MediaType.custom("text", "plain", compressible = true, parameters = Map("version" -> "0.0.4")))
  /** Spray version of the canonical UTF-8 charset. */
  val Utf8Charset = {
    val nioCharset = Charset.forName("UTF-8")
    val aliases = nioCharset.aliases().toArray(Array.empty[String])
    HttpCharset.custom(nioCharset.name, aliases: _*).get
  }
  /** Additionally, the Prometheus text-based exposition format requires UTF-8 encoding. */
  val TextContentType = TextMediaType.withCharset(PrometheusEndpoint.Utf8Charset)

  /** Media type for the protocol buffer encoding supported by Prometheus. */
  val ProtoBufMediaType = MediaTypes.register(
    MediaType.custom("application", "vnd.google.protobuf", binary = true, compressible = true,
      parameters = Map("proto" → "io.prometheus.client.MetricFamily", "encoding" → "delimited")))

    /** Content type for the protocol buffer encoding supported by Prometheus.  This exists primarily to match against
      * it during marshalling.
      */
  val ProtoBufContentType = ContentType(ProtoBufMediaType)
}
