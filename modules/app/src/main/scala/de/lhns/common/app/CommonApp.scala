package de.lhns.common.app

import cats.Applicative
import cats.effect.*
import cats.effect.std.Env
import cats.effect.syntax.all.*
import cats.mtl.Local
import cats.syntax.all.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.Otel4s
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.typelevel.otel4s.sdk.context.{LocalContextProvider, Context as OtelContext}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

abstract class CommonApp extends IOApp with CommonAppPlatform {
  protected[app] def scopeName: String = getClass.getName

  protected[app] def allowInsecure: Boolean = false

  def run(context: CommonApp.Context[IO]): Resource[IO, ExitCode]
}

object CommonApp {
  trait Context[F[_]] {
    def args: List[String]

    given env: Env[F]

    def otel: Otel4s[F]

    given loggerFactory: LoggerFactory[F]

    given tracerProvider: TracerProvider[F]

    given meterProvider: MeterProvider[F]

    given tracer: Tracer[F]

    given meter: Meter[F]
  }

  object Context {
    private[app] def otelNoop[F[_] : Applicative]: F[Otel4s[F]] = {
      trait NoopLocal[E] extends Local[F, E] {
        override def local[A](fa: F[A])(f: E => E): F[A] = fa

        override def applicative: Applicative[F] = Applicative[F]
      }

      given localContextProvider: LocalContextProvider[F] = LocalProvider.fromLocal[F, OtelContext](new NoopLocal[OtelContext] {
        override def ask[E2 >: OtelContext]: F[E2] = Applicative[F].pure(OtelContext.root)
      })

      OpenTelemetrySdk.noop[F].widen[Otel4s[F]]
    }

    def resource[F[_]](
                        args: List[String],
                        env: Env[F],
                        otel: Otel4s[F],
                        loggerFactory: LoggerFactory[F],
                        scopeName: String
                      ): Resource[F, Context[F]] = {
      val _args = args
      val _env = env
      val _otel = otel
      val _loggerFactory = loggerFactory

      for {
        _tracer <- otel.tracerProvider.get(scopeName).toResource
        _meter <- otel.meterProvider.get(scopeName).toResource
      } yield new Context[F] {
        override def args: List[String] = _args

        override def env: Env[F] = _env

        override def otel: Otel4s[F] = _otel

        override def loggerFactory: LoggerFactory[F] = _loggerFactory

        override def tracerProvider: TracerProvider[F] = otel.tracerProvider

        override def meterProvider: MeterProvider[F] = otel.meterProvider

        override def tracer: Tracer[F] = _tracer

        override def meter: Meter[F] = _meter
      }
    }
  }
}
