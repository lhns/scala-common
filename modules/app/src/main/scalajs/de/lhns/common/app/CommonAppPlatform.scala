package de.lhns.common.app

import cats.effect.*
import cats.effect.std.Env
import org.typelevel.log4cats.console.ConsoleLoggerFactory

trait CommonAppPlatform extends IOApp {
  self: CommonApp =>

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      otel <- Resource.eval(CommonApp.Context.otelNoop[IO])
      context <- CommonApp.Context.resource[IO](
        args = args,
        env = Env[IO],
        otel = otel,
        loggerFactory = ConsoleLoggerFactory.create[IO],
        scopeName = scopeName
      )
      exitCode <- run(context)
    } yield
      exitCode
  }.use(IO.pure)
}
