package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.{ExitCode, IO, Resource, ResourceApp}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

abstract class CommonApp extends ResourceApp with CommonAppPlatform {
  def run(context: CommonApp.Context[IO]): Resource[IO, ExitCode]
}

object CommonApp {
  case class Context[F[_]](
                            args: List[String],
                            env: Env[F],
                            loggerFactory: LoggerFactory[F],
                            tracerProvider: TracerProvider[F],
                            meterProvider: MeterProvider[F]
                          ) {
    given Env[F] = env

    given LoggerFactory[F] = loggerFactory

    given TracerProvider[F] = tracerProvider

    given MeterProvider[F] = meterProvider

    //def tracer(cls: Class[?]): F[Tracer[F]] = tracerProvider.get(cls.getName)

    //def meter(cls: Class[?]): F[Meter[F]] = meterProvider.get(cls.getName)
  }
}
