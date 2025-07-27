package de.lhns.common.skunk

import cats.effect.std.Console
import cats.effect.syntax.all.*
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import cps.*
import cps.monads.catsEffect.given
import dumbo.logging.{LogLevel, Logger as DumboLogger}
import dumbo.{ConnectionConfig, DumboWithResourcesPartiallyApplied}
import fs2.io.net.Network
import org.typelevel.log4cats.{Logger, LoggerFactory}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer
import skunk.Session.Credentials
import skunk.{RedactionStrategy, SSL, Session, TypingStrategy}

import scala.concurrent.duration.Duration

object SkunkSessionPool {
  val defaultMigrations = "db/migration"

  def builder[
    F[_] : Async : Tracer : LoggerFactory : Network : Console
  ](
     migrations: Option[DumboWithResourcesPartiallyApplied[F]],
     host: String,
     port: Int = 5432,
     user: String,
     database: String,
     password: Option[String] = none,
     ssl: SSL = SSL.None,
   ): Resource[F, Session.Builder[F]] = async[Resource[F, _]] {
    given Logger[F] = LoggerFactory[F].getLogger

    given DumboLogger[F] = new DumboLogger[F] {
      override def apply(level: LogLevel, message: => String): F[Unit] = level match {
        case LogLevel.Info => Logger[F].info(message)
        case LogLevel.Warn => Logger[F].warn(message)
      }
    }

    val connectionConfig = ConnectionConfig(
      host = host,
      port = port,
      user = user,
      database = database,
      password = password,
      ssl = ssl match {
        case SSL.None => ConnectionConfig.SSL.None
        case SSL.Trusted => ConnectionConfig.SSL.Trusted
        case SSL.System => ConnectionConfig.SSL.System
      }
    )

    migrations match {
      case Some(dumbo) =>
        Tracer[F].span(
          "running database migration",
          Attribute("server.address", host),
          Attribute("server.port", port.toLong),
          Attribute("db.system", "postgresql"),
          Attribute("db.namespace", database)
        ).surround {
          dumbo(connectionConfig).runMigration.void
        }.toResource.await

      case None =>
    }

    Session.Builder[F]
      .withHost(host)
      .withPort(port)
      .withCredentials(Credentials(user, password))
      .withDatabase(database)
      .withSSL(ssl)
  }

  @deprecated("use SkunkSessionPool.builder instead")
  def apply[
    F[_] : Async : Tracer : LoggerFactory : Network : Console
  ](
     migrations: Option[DumboWithResourcesPartiallyApplied[F]],
     host: String,
     port: Int = 5432,
     user: String,
     database: String,
     password: Option[String] = none,
     max: Int,
     debug: Boolean = false,
     strategy: TypingStrategy = TypingStrategy.BuiltinsOnly,
     ssl: SSL = SSL.None,
     parameters: Map[String, String] = Session.DefaultConnectionParameters,
     commandCache: Int = 1024,
     queryCache: Int = 1024,
     parseCache: Int = 1024,
     readTimeout: Duration = Duration.Inf,
     redactionStrategy: RedactionStrategy = RedactionStrategy.OptIn,
   ): Resource[F, Resource[F, Session[F]]] =
    builder(
      migrations = migrations,
      host = host,
      port = port,
      user = user,
      database = database,
      password = password,
      ssl = ssl
    ).flatMap { builder =>
      builder
        .withDebug(debug)
        .withTypingStrategy(strategy)
        .withConnectionParameters(parameters)
        .withCommandCacheSize(commandCache)
        .withQueryCacheSize(queryCache)
        .withParseCacheSize(parseCache)
        .withReadTimeout(readTimeout)
        .withRedactionStrategy(redactionStrategy)
        .pooled(max)
    }
}
