package de.lhns.common.http.server

import cats.Monad
import cats.data.OptionT
import cats.effect.Async
import cats.kernel.Monoid
import cats.mtl.Local
import cats.syntax.all.*
import de.lhns.common.http.Http4sContext
import de.lhns.common.http.Http4sContext.LocalHttp4sContextProvider
import org.http4s.{HttpRoutes, Request}
import org.typelevel.otel4s.context.LocalProvider
import sttp.apispec.openapi.OpenAPI
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.{OpenAPIDocsInterpreter, OpenAPIDocsOptions}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter

case class EndpointRoutes[F[_]](
                                 routes: HttpRoutes[F],
                                 endpoints: Seq[AnyEndpoint]
                               )

object EndpointRoutes {
  given [F[_] : Monad]: Monoid[EndpointRoutes[F]] = Monoid.instance(
    EndpointRoutes(HttpRoutes.empty, Seq.empty),
    (a, b) => EndpointRoutes(a.routes <+> b.routes, a.endpoints ++ b.endpoints)
  )

  private val openApiVersion = "3.0.3"

  object openApi {
    extension [F[_]](endpointRoutes: EndpointRoutes[F]) {
      def toOpenApi(
                     name: String,
                     version: String
                   ): OpenAPI =
        OpenAPIDocsInterpreter().toOpenAPI(
          endpointRoutes.endpoints,
          name,
          version
        ).openapi(openApiVersion)
    }
  }

  object swaggerUi {
    extension [F[_] : Async](endpointRoutes: EndpointRoutes[F]) {
      def toSwaggerEndpoint(
                             name: String,
                             version: String,
                             openAPIInterpreterOptions: OpenAPIDocsOptions = OpenAPIDocsOptions.default,
                             customiseDocsModel: OpenAPI => OpenAPI = identity,
                             swaggerUIOptions: SwaggerUIOptions = SwaggerUIOptions.default,
                             addServerWhenContextPathPresent: Boolean = true
                           ): EndpointRoutes[F] =
        EndpointRoutes(
          SwaggerInterpreter(
            openAPIInterpreterOptions = openAPIInterpreterOptions,
            customiseDocsModel = e => customiseDocsModel(e.openapi(openApiVersion)),
            swaggerUIOptions = swaggerUIOptions,
            addServerWhenContextPathPresent = addServerWhenContextPathPresent
          )
            .fromEndpoints(endpointRoutes.endpoints.toList, name, version) *
        )

      def withSwaggerEndpoint(
                               name: String,
                               version: String,
                               openAPIInterpreterOptions: OpenAPIDocsOptions = OpenAPIDocsOptions.default,
                               customiseDocsModel: OpenAPI => OpenAPI = identity,
                               swaggerUIOptions: SwaggerUIOptions = SwaggerUIOptions.default,
                               addServerWhenContextPathPresent: Boolean = true
                             ): EndpointRoutes[F] =
        endpointRoutes |+| toSwaggerEndpoint(
          name = name,
          version = version,
          openAPIInterpreterOptions = openAPIInterpreterOptions,
          customiseDocsModel = customiseDocsModel,
          swaggerUIOptions = swaggerUIOptions,
          addServerWhenContextPathPresent = addServerWhenContextPathPresent
        )
    }
  }

  def apply[F[_] : Async](serverEndpoints: ServerEndpoint[Fs2Streams[F], F]*): EndpointRoutes[F] =
    EndpointRoutes[F](
      routes = Http4sServerInterpreter[F]().toRoutes(serverEndpoints.toList),
      endpoints = serverEndpoints.map(_.endpoint)
    )

  def withHttp4sContext[
    F[_] : Async : LocalHttp4sContextProvider
  ](f: Http4sContext[F] ?=> Seq[ServerEndpoint[Fs2Streams[F], F]]): F[EndpointRoutes[F]] = {
    LocalProvider[F, Request[F]].local.map { (local: Local[F, Request[F]]) =>
      val http4sContext = new Http4sContext[F] {
        override def request: F[Request[F]] = local.ask
      }

      val serverEndpoints = f(using http4sContext)
      val routes = Http4sServerInterpreter[F]().toRoutes(serverEndpoints.toList)

      EndpointRoutes[F](
        routes = HttpRoutes[F] { request =>
          OptionT(local.scope {
            routes(request).value
          }(request))
        },
        endpoints = serverEndpoints.map(_.endpoint)
      )
    }
  }

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
