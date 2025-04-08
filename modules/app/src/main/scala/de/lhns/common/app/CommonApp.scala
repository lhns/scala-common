package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.syntax.all.*
import cats.effect.{ExitCode, IO, Resource, ResourceApp}
import cats.mtl.Local
import cats.{Applicative, Monad}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.Otel4s
import org.typelevel.otel4s.baggage.{Baggage, BaggageManager}
import org.typelevel.otel4s.context.propagation.ContextPropagators
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

abstract class CommonApp extends ResourceApp with CommonAppPlatform {
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
    private[app] def otelNoop[F[_] : Applicative]: Otel4s[F] = new Otel4s[F] {
      override type Ctx = Unit

      trait NoopLocal[E] extends Local[F, E] {
        override def local[A](fa: F[A])(f: E => E): F[A] = fa

        override def applicative: Applicative[F] = Applicative[F]
      }

      override given localContext: Local[F, Unit] = new NoopLocal[Unit] {
        override def ask[E2 >: Unit]: F[E2] = Applicative[F].pure(())
      }

      override lazy val propagators: ContextPropagators[Ctx] = ContextPropagators.noop[Ctx]

      override lazy val meterProvider: MeterProvider[F] = MeterProvider.noop[F]

      override lazy val tracerProvider: TracerProvider[F] = TracerProvider.noop[F]

      override lazy val baggageManager: BaggageManager[F] = new BaggageManager[F] with NoopLocal[Baggage] {
        override def ask[E2 >: Baggage]: F[E2] = Applicative[F].pure(Baggage.empty)
      }
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
