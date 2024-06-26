package de.lhns.common.app

import cats.effect.std.Env
import cats.effect.{ExitCode, IO, Resource}
import com.github.markusbernhardt.proxy.ProxySearch
import de.lhns.trustmanager.TrustManagers.*
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.slf4j.bridge.SLF4JBridgeHandler
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.otel4s.oteljava.OtelJava

import java.net.ProxySelector
import scala.util.chaining.*

trait CommonAppPlatform {
  self: CommonApp =>

  override def run(args: List[String]): Resource[IO, ExitCode] = {
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
}
