package de.lhns.common.http.client

import cats.effect.syntax.all.*
import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.Request
import org.http4s.client.Client
import org.http4s.client.middleware.Metrics
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.otel4s.middleware.trace.client.UriRedactor.OnlyRedactUserInfo
import org.http4s.otel4s.middleware.trace.client.{ClientSpanDataProvider, UriRedactor, ClientMiddleware as Otel4sClientMiddleware}
import org.http4s.otel4s.middleware.trace.redact.HeaderRedactor
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

object HttpClient {
  def resource[
    F[_] : Async : Network : TracerProvider : MeterProvider
  ](
     attributes: Attributes = Attributes.empty,
     classifierF: Request[F] => Option[String] = { (_: Request[F]) =>
       None
     }
   ): Resource[F, Client[F]] =
    for {
      metricsOps <- OtelMetrics.clientMetricsOps[F](
        attributes = attributes
      ).toResource
      tracerMiddleware <- Otel4sClientMiddleware.builder[F](
          ClientSpanDataProvider
            .openTelemetry(new OnlyRedactUserInfo {})
            .optIntoUrlScheme
            .optIntoHttpRequestHeaders(HeaderRedactor.default)
            .optIntoHttpResponseHeaders(HeaderRedactor.default)
        )
        .build
        .toResource
      client <- HttpClientPlatform.resource[F]
    } yield
      tracerMiddleware.wrapClient(
        Metrics(
          metricsOps,
          classifierF
        )(
          client
        )
      )
}
