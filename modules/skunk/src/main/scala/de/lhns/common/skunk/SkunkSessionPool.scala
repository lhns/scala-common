package de.lhns.common.skunk

import cats.syntax.all.none
import dumbo.{ConnectionConfig, Dumbo}
import skunk.util.{BrokenPipePool, Typer}
import skunk.{RedactionStrategy, SSL, Session}

import scala.concurrent.duration.Duration

object SkunkSessionPool {
  private val dumbo = Dumbo.withResourcesIn[F]("db/migration")

  def migratedPool[F[_]](
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
                        ): Resource[F, Session[F]] = async[Resource[F, _]] {
    val connectionConfig = ConnectionConfig(
      host = host,
      port = port,
      user = user,
      database = database,
      password = password,
      ssl = ssl
    )

    dumbo(connectionConfig).runMigration.void.toResource.await

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
