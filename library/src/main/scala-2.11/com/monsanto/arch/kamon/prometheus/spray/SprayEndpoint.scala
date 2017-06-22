package com.monsanto.arch.kamon.prometheus.spray

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import spray.routing.Route

/** An Akka extension for creating a Spray-based endpoint for serving Kamon
  * metrics to Prometheus.  It supports both the text and protocol buffer
  * formats and optional gzip encoding.  All content negotiation is handled
  * through Spray.
  *
  * ==Usage==
  *
  * As an Akka extension, you will need to instantiate the extension using
  * an actor system.  Once you have an a reference to the extension, you
  * can simply call [[SprayEndpoint.route route]] to get a Spray route
  * that will handle requests from Prometheus.
  *
  * {{{
  *   import akka.actor.ActorSystem
  *   import com.monsanto.arch.kamon.prometheus.spray.SprayEndpoint
  *   import spray.routing.SimpleRoutingApp
  *
  *   object Main extends App with SimpleRoutingApp {
  *     implicit val actorSystem = ActorSystem("Demo")
  *
  *     startServer("localhost", 8888) {
  *       path("metrics") {
  *         SprayEndpoint(actorSystem).route
  *       }
  *     }
  *   }
  * }}}
  *
  * '''NOTE:'''  When you instantiate the extension, it will asynchronously
  * register itself with the Kamon Prometheus extension and start receiving
  * snapshots.  '''However, if Kamon fails to instantiate the Prometheus
  * extension, the Spray endpoint will never serve any data.'''  Errors will
  * be logged.
  *
  * @author Daniel Solano Gómez
  */
trait SprayEndpoint extends Extension {
  def route: Route
}

/** The Akka extension support for the `SprayEndpoint`. */
object SprayEndpoint extends ExtensionId[SprayEndpoint] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): SprayEndpoint = new PrometheusEndpoint(system)

  override def lookup(): ExtensionId[_ <: Extension] = SprayEndpoint
}
