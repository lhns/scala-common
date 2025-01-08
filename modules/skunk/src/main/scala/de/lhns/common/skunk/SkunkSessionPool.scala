package de.lhns.common.skunk

import cats.effect.std.Console
import cats.effect.syntax.all.*
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import cps.*
import cps.monads.catsEffect.given
import dumbo.{ConnectionConfig, DumboWithResourcesPartiallyApplied}
import fs2.io.net.Network
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer
import skunk.util.{BrokenPipePool, Typer}
import skunk.{RedactionStrategy, SSL, Session}

import scala.concurrent.duration.Duration

object SkunkSessionPool {
  val defaultMigrations = "db/migration"

  def apply[
    F[_] : Async : Tracer : Network : Console
  ](
     migrations: Option[DumboWithResourcesPartiallyApplied[F]],
     host: String,
     port: Int = 5432,
     user: String,
     database: String,
     password: Option[String] = none,
     max: Int,
     debug: Boolean = false,
     strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
     ssl: SSL = SSL.None,
     parameters: Map[String, String] = Session.DefaultConnectionParameters,
     commandCache: Int = 1024,
     queryCache: Int = 1024,
     parseCache: Int = 1024,
     readTimeout: Duration = Duration.Inf,
     redactionStrategy: RedactionStrategy = RedactionStrategy.OptIn,
   ): Resource[F, Resource[F, Session[F]]] = async[Resource[F, _]] {
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

    BrokenPipePool.pooled(
      host = host,
      port = port,
      user = user,
      database = database,
      password = password,
      max = max,
      debug = debug,
      strategy = strategy,
      ssl = ssl,
      parameters = parameters,
      commandCache = commandCache,
      queryCache = queryCache,
      parseCache = parseCache,
      readTimeout = readTimeout,
      redactionStrategy = redactionStrategy
    ).await
  }
}
