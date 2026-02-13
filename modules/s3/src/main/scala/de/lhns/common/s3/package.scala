package de.lhns.common

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.mtl.syntax.all.*
import cats.effect.std.syntax.all.*
import blobstore.s3.S3Store
import cats.effect.{Async, IO}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.http.nio.netty.{NettyNioAsyncHttpClient, ProxyConfiguration}
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import scala.jdk.CollectionConverters.*
import java.net.{InetSocketAddress, ProxySelector, URI}
import scala.annotation.unused

package object s3 {
  @unused
  def createS3Store[F[_] : Async](accessKeyId: String, secretAccessKey: String, s3Endpoint: URI): S3Store[F] =
    S3Store.builder[F](
      S3AsyncClient.builder()
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
          accessKeyId,
          secretAccessKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build)
        .endpointOverride(s3Endpoint)
        // some backends don't support the new aws s3 data-integrity feature
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(
          NettyNioAsyncHttpClient
            .builder()
            .proxyConfiguration {
              val builder = ProxyConfiguration.builder()
              ProxySelector.getDefault.select(s3Endpoint)
                .asScala
                .headOption
                .filter(_.`type`() == java.net.Proxy.Type.HTTP)
                .fold(builder) { proxy =>
                  val address = proxy.address().asInstanceOf[InetSocketAddress]
                  builder.host(address.getHostName).port(address.getPort)
                }
                .build()
            }
        )
        .build()
    ).unsafe
}