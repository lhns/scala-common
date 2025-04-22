package de.lhns.common.http.server

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.redact.{HeaderRedactor, PathRedactor, QueryRedactor}
import org.http4s.otel4s.middleware.trace.server.{ServerSpanDataProvider, ServerMiddleware as Otel4sServerMiddleware}
import org.http4s.server.Server
import org.http4s.server.middleware.{ErrorHandling, Metrics}
import org.http4s.{HttpApp, HttpRoutes, Query, Request, Uri}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import org.typelevel.otel4s.{Attribute, Attributes}

import scala.concurrent.duration.*
import scala.util.chaining.*

object HttpServer {
  def resource[
    F[_] : Async : Network : TracerProvider : MeterProvider
  ](
     attributes: Attributes = Attributes.empty,
     classifierF: Request[F] => Option[String] = { (_: Request[F]) =>
       None
     },
     idleTimeout: Duration | Null = null
   )(
     endpoints: (SocketAddress[Host], HttpApp[F])*
   ): Resource[F, Seq[Server]] =
    endpoints.map { (socketAddress, app) =>
      for {
        metricsMiddleware <- metricsMiddlewareResource(
          socketAddress = socketAddress,
          attributes = attributes,
          classifierF = classifierF
        )
        tracerMiddleware <- Otel4sServerMiddleware.builder[F](
            ServerSpanDataProvider
              .openTelemetry(new PathRedactor with QueryRedactor {
                override def redactPath(path: Uri.Path): Uri.Path = path

                override def redactQuery(query: Query): Query = query
              })
              .optIntoClientPort
              .optIntoHttpRequestHeaders(HeaderRedactor.default)
              .optIntoHttpResponseHeaders(HeaderRedactor.default)
          )
          .build
          .toResource
        server <- EmberServerBuilder
          .default[F]
          .withHost(socketAddress.host)
          .withPort(socketAddress.port)
          .withHttpApp(
            ErrorHandling(
              tracerMiddleware.wrapHttpApp(
                metricsMiddleware(
                  app
                )
              )
            )
          )
          .withShutdownTimeout(1.second)
          .pipe(builder =>
            idleTimeout match {
              case null => builder
              case timeout => builder.withIdleTimeout(timeout)
            }
          )
          .build
      } yield
        server
    }.parUnorderedSequence

  private def metricsMiddlewareResource[F[_] : Async : MeterProvider](
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
