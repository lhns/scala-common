package de.lhns.common.http.client

import cats.effect.syntax.all.*
import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.client.middleware.Metrics
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.client.ClientMiddleware as Otel4sClientMiddleware
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

object HttpClient {
  def resource[
    F[_] : Async : Network : Tracer : Meter
  ](
     attributes: Attributes = Attributes.empty,
     classifierF: Request[F] => Option[String] = { (_: Request[F]) =>
       None
     }
   ): Resource[F, Client[F]] =
    OtelMetrics.clientMetricsOps[F](
      attributes = attributes
    ).toResource.flatMap { metricsOps =>
      HttpClientPlatform.resource[F].map { client =>
        Otel4sClientMiddleware.default[F].build(
          Metrics(
            metricsOps,
            classifierF
          )(
            client
          )
        )
      }
    }
}
