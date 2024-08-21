package de.lhns.common.http.client

import cats.effect.{Async, Resource}
import org.http4s.Uri
import org.http4s.client.Client
import sttp.tapir.PublicEndpoint
import sttp.tapir.client.http4s.Http4sClientInterpreter

class ClientOps {
  extension [F[_] : Async](client: Client[F]) {
    def endpointResource[I, E, O, R](
                                      e: PublicEndpoint[I, E, O, R],
                                      baseUri: Option[Uri]
                                    ): I => Resource[F, Either[E, O]] = {
      val fromInput = Http4sClientInterpreter[F]().toRequestThrowDecodeFailures(e, baseUri)
      { in =>
        val (request, fromResponse) = fromInput(in)
        client.run(request).evalMap(fromResponse)
      }
    }

    def endpoint[I, E, O, R](
                              e: PublicEndpoint[I, E, O, R],
                              baseUri: Option[Uri]
                            ): I => F[Either[E, O]] =
      val run = endpointResource[I, E, O, R](e, baseUri)
      { in =>
        run(in).use(Async[F].pure)
      }
  }
}
