package de.lhns.common.http.client

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import fs2.io.net.Network
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client

object HttpClientPlatform {
  private[client] def resource[F[_]: Async : Network]: Resource[F, Client[F]] =
    FetchClientBuilder[F].resource
}
