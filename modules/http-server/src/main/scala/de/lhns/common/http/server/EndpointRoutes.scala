package de.lhns.common.http.server

import cats.Monad
import cats.effect.Async
import cats.kernel.Monoid
import cats.syntax.all.*
import org.http4s.HttpRoutes
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.yaml.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.{SwaggerUI, SwaggerUIOptions}

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
    def toOpenApi(
                   name: String,
                   version: String
                 ): OpenAPI =
      OpenAPIDocsInterpreter().toOpenAPI(
        endpointRoutes.endpoints,
        name,
        version
      ).openapi("3.0.3")

    def withOpenApiEndpoints(
                              name: String,
                              version: String
                            )(openApi: OpenAPI => EndpointRoutes[F]): EndpointRoutes[F] =
      endpointRoutes |+| openApi(toOpenApi(
        name = name,
        version = version
      ))
  }

  object swaggerUi {
    extension (openApi: OpenAPI) {
      def swaggerUi[F[_] : Async](options: SwaggerUIOptions = SwaggerUIOptions.default): EndpointRoutes[F] =
        EndpointRoutes(SwaggerUI[F](openApi.toYaml, options) *)
    }
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
