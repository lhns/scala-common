package de.lhns.common.http.client

import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.jdkhttpclient.JdkHttpClient
import cats.effect.syntax.all.*
import org.http4s.client.Client

object HttpClientPlatform {
  private[client] def resource[F[_]: Async : Network]: Resource[F, Client[F]] =
    JdkHttpClient.simple[F].toResource
}
