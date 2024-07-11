package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.{ExitCode, IO, Resource, ResourceApp}
import org.typelevel.log4cats.console.ConsoleLoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

trait CommonAppPlatform extends ResourceApp {
  self: CommonApp =>

  override def run(args: List[String]): Resource[IO, ExitCode] = {
    val context = CommonApp.Context[IO](
      args = args,
      env = Env[IO],
      loggerFactory = ConsoleLoggerFactory.create[IO],
      tracerProvider = TracerProvider.noop[IO],
      meterProvider = MeterProvider.noop[IO]
    )

    run(context)
  }
}
