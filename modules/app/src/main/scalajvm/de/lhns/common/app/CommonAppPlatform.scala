package de.lhns.common.app

import cats.effect.*
import cats.effect.std.Env
import cats.effect.unsafe.IORuntime
import com.github.markusbernhardt.proxy.ProxySearch
import de.lhns.trustmanager.TrustManagers.*
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.slf4j.bridge.SLF4JBridgeHandler
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.otel4s.experimental.metrics.RuntimeMetrics
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava

import java.net.ProxySelector
import scala.util.chaining.*

trait CommonAppPlatform extends IOApp {
  self: CommonApp =>

  override def run(args: List[String]): IO[ExitCode] = {
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

    setDefaultTrustManager(
      insecureTrustManagerFromEnvVar
        .filter(_ => allowInsecure)
        .getOrElse(jreTrustManagerWithEnvVar)
    )

    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    OtelJava.autoConfigured[IO]().flatMap { otelJava =>
      OpenTelemetryAppender.install(otelJava.underlying)

      /*RuntimeMetrics.builder(otelJava.underlying)
        .enableAllFeatures()
        .enableExperimentalJmxTelemetry()
        .build()*/

      for {
        context <- CommonApp.Context.resource[IO](
          args = args,
          env = Env[IO],
          otel = otelJava,
          loggerFactory = Slf4jFactory.create[IO],
          scopeName = scopeName
        )
        _ <- {
          import context.given
          RuntimeMetrics.register[IO]
        }
        rt = Option(runtime).getOrElse(IORuntime.global)
        _ <- {
          import context.given
          IORuntimeMetrics.register[IO](rt.metrics, IORuntimeMetrics.Config.default)
        }
        exitCode <- run(context)
      } yield
        exitCode
    }
  }.use(IO.pure)
}
