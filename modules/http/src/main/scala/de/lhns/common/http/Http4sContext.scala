package de.lhns.common.http

import cats.Monad
import cats.data.OptionT
import cats.mtl.Local
import cats.syntax.all.*
import org.http4s.{HttpRoutes, Request}
import org.typelevel.otel4s.context.LocalProvider
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import java.nio.charset.Charset

trait Http4sContext[F[_]] {
  def request: F[Request[F]]
}

object Http4sContext {
  def apply[F[_]](implicit c: Http4sContext[F]): Http4sContext[F] = c

  type LocalHttp4sContextProvider[F[_]] = LocalProvider[F, Request[F]]

  def routes[F[_] : Monad : LocalHttp4sContextProvider](f: Http4sContext[F] ?=> HttpRoutes[F]): F[HttpRoutes[F]] =
    LocalProvider[F, Request[F]].local.map { (local: Local[F, Request[F]]) =>
      val http4sContext = new Http4sContext[F] {
        override def request: F[Request[F]] = local.ask
      }

      val routes = f(using http4sContext)

      HttpRoutes[F] { request =>
        OptionT(local.scope {
          routes(request).value
        }(request))
      }
    }

  object syntax {
    def http4sRequest[F[_] : Http4sContext, T](
                                                schema: Schema[T],
                                                format: CodecFormat,
                                                charset: Option[Charset] = None
                                              ): StreamBodyIO[fs2.Stream[F, Byte], F[Request[F]], Fs2Streams[F]] =
      streamBody(Fs2Streams[F])(schema, format, charset)
        .map(_ => Http4sContext[F].request)(requestF => fs2.Stream.eval(requestF).flatMap(_.body))
  }
}
