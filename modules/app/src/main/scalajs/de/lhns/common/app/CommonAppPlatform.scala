package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.{ExitCode, IO, Resource, ResourceApp}
import org.typelevel.log4cats.console.ConsoleLoggerFactory
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

trait CommonAppPlatform extends ResourceApp {
  self: CommonApp =>

  override def run(args: List[String]): Resource[IO, ExitCode] =
    for {
      context <- CommonApp.Context.resource[IO](
        args = args,
        env = Env[IO],
        otel = CommonApp.Context.otelNoop,
        loggerFactory = ConsoleLoggerFactory.create[IO],
        scopeName = scopeName
      )
    } yield
      run(context)
}
