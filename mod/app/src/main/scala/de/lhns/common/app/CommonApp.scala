package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.{ExitCode, IO, Resource, ResourceApp}
import com.github.markusbernhardt.proxy.ProxySearch
import de.lhns.trustmanager.TrustManagers.*
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.slf4j.bridge.SLF4JBridgeHandler
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

import java.net.ProxySelector
import scala.util.chaining.*

abstract class CommonApp extends ResourceApp {
  override final def run(args: List[String]): Resource[IO, ExitCode] = {
    ProxySelector.setDefault(
      Option(new ProxySearch().tap { s =>
        s.addStrategy(ProxySearch.Strategy.JAVA)
        s.addStrategy(ProxySearch.Strategy.ENV_VAR)
      }.getProxySelector)
        .getOrElse(ProxySelector.getDefault)
    )

    Option(System.getenv("https_disable_hostname_verification"))
      .flatMap(_.toBooleanOption)
      .foreach { disableHostnameVerification =>
        System.getProperties
          .setProperty("jdk.internal.httpclient.disableHostnameVerification", disableHostnameVerification.toString)
      }

    setDefaultTrustManager(jreTrustManagerWithEnvVar)

    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    OtelJava.autoConfigured[IO]().flatMap { otelJava =>
      OpenTelemetryAppender.install(otelJava.underlying)

      val context = CommonApp.Context[IO](
        args = args,
        env = Env[IO],
        loggerFactory = Slf4jFactory.create[IO],
        tracerProvider = otelJava.tracerProvider,
        meterProvider = otelJava.meterProvider
      )

      run(context)
    }
  }

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
