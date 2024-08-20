package de.lhns.common.http.server

import cats.Monad
import cats.effect.Async
import cats.kernel.Monoid
import cats.syntax.all.*
import org.http4s.HttpRoutes
import sttp.apispec.openapi.circe.yaml.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.redoc.{Redoc, RedocUIOptions}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

case class EndpointRoutes[F[_]](
                                 routes: HttpRoutes[F],
                                 endpoints: Seq[AnyEndpoint]
                               )

object EndpointRoutes {
  given [F[_] : Monad]: Monoid[EndpointRoutes[F]] = Monoid.instance(
    EndpointRoutes(HttpRoutes.empty, Seq.empty),
    (a, b) => EndpointRoutes(a.routes <+> b.routes, a.endpoints ++ b.endpoints)
  )

  extension [F[_] : Async](endpointRoutes: EndpointRoutes[F]) {
    def toOpenApiEndpoints(
                            name: String,
                            version: String,
                            options: RedocUIOptions = RedocUIOptions.default
                          ): EndpointRoutes[F] = {
      val openApi = OpenAPIDocsInterpreter().toOpenAPI(
        endpointRoutes.endpoints,
        name,
        version
      )

      EndpointRoutes(Redoc[F](
        openApi.info.title,
        openApi.toYaml,
        options
      ) *)
    }

    def withOpenApiEndpoints(
                              name: String,
                              version: String,
                              options: RedocUIOptions = RedocUIOptions.default
                            ): EndpointRoutes[F] =
      endpointRoutes |+| toOpenApiEndpoints(
        name = name,
        version = version,
        options = options
      )
  }

  def apply[F[_] : Async](serverEndpoints: ServerEndpoint[Fs2Streams[F], F]*): EndpointRoutes[F] =
    EndpointRoutes[F](
      routes = Http4sServerInterpreter[F]().toRoutes(serverEndpoints.toList),
      endpoints = serverEndpoints.map(_.endpoint)
    )

  def undocumented[F[_]](routes: HttpRoutes[F]): EndpointRoutes[F] =
    EndpointRoutes[F](
      routes = routes,
      endpoints = Seq.empty
    )

  def health[F[_] : Async]: EndpointRoutes[F] = {
    import sttp.tapir.*

    EndpointRoutes(
      endpoint
        .get
        .in("health")
        .out(emptyOutput)
        .serverLogicSuccess(_ => ().pure[F])
    )
  }
}
