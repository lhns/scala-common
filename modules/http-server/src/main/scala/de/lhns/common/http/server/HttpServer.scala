package de.lhns.common.http.server

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.{OtelMetrics, ServerMiddleware as Otel4sServerMiddleware}
import org.http4s.server.Server
import org.http4s.server.middleware.{ErrorHandling, Metrics}
import org.http4s.{HttpApp, HttpRoutes, Request}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.{Attribute, Attributes}

import scala.concurrent.duration.*

object HttpServer {
  def resource[
    F[_] : Async : Network : Tracer : Meter
  ](
     attributes: Attributes = Attributes.empty,
     classifierF: Request[F] => Option[String] = { (_: Request[F]) =>
       None
     }
   )(
     endpoints: (SocketAddress[Host], HttpApp[F])*
   ): Resource[F, Seq[Server]] =
    endpoints.map { (socketAddress, app) =>
      metricsMiddlewareResource(
        socketAddress = socketAddress,
        attributes = attributes,
        classifierF = classifierF
      ).flatMap { metricsMiddleware =>
        EmberServerBuilder
          .default[F]
          .withHost(socketAddress.host)
          .withPort(socketAddress.port)
          .withHttpApp(
            ErrorHandling(
              Otel4sServerMiddleware.default[F].buildHttpApp(
                metricsMiddleware(
                  app
                )
              )
            )
          )
          .withShutdownTimeout(1.second)
          .build
      }
    }.parUnorderedSequence

  private def metricsMiddlewareResource[F[_] : Async : Meter](
                                                               socketAddress: SocketAddress[Host],
                                                               attributes: Attributes,
                                                               classifierF: Request[F] => Option[String]
                                                             ): Resource[F, HttpApp[F] => HttpApp[F]] =
    OtelMetrics.serverMetricsOps[F](
      attributes = Attributes(
        Attribute("server.address", socketAddress.host.toString),
        Attribute("server.port", socketAddress.port.value.toLong)
      ) ++ attributes
    ).map { metricsOps =>
      val metricsMiddleware: HttpApp[F] => HttpApp[F] = { (app: HttpApp[F]) =>
        Metrics(
          ops = metricsOps,
          classifierF = classifierF
        )(HttpRoutes.strict(request => app(request)))
          .mapF(_.getOrRaise(throw new RuntimeException()))
      }
      metricsMiddleware
    }.toResource
}
