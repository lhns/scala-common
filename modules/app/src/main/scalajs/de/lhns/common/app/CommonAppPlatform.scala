package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.*
import org.typelevel.log4cats.console.ConsoleLoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

trait CommonAppPlatform extends IOApp {
  self: CommonApp =>

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      context <- CommonApp.Context.resource[IO](
        args = args,
        env = Env[IO],
        otel = CommonApp.Context.otelNoop,
        loggerFactory = ConsoleLoggerFactory.create[IO],
        scopeName = scopeName
      )
      exitCode <- run(context)
    } yield
      exitCode
  }.use(IO.pure)
}
