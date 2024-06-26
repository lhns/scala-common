package de.lhns.common.http.client

import cats.effect.Async
import fs2.io.net.Network
import org.http4s.Request
import org.http4s.client.middleware.Metrics
import org.http4s.otel4s.middleware.OtelMetrics
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import cats.effect.syntax.all.*
import org.http4s.otel4s.middleware.{ClientMiddleware => Otel4sClientMiddleware}
import org.http4s.client.Client

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
