package de.lhns.common.s3

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.mtl.syntax.all.*
import cats.effect.std.syntax.all.*
import blobstore.Store as BlobStore
import blobstore.s3.{S3Blob, S3MetaInfo}
import blobstore.url.Url
import cats.Functor
import cats.effect.{Async, IO}
import cats.effect.std.AtomicCell
import de.lhns.common.s3.FakeS3BlobStore.Key
import fs2.Pipe
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

import java.nio.file.Path
import java.time.Instant
import scala.collection.immutable.{ArraySeq, HashMap}
import scala.util.chaining.*
import FakeS3BlobStore.*

class FakeS3BlobStore[F[_]: Async] private (private val objects: AtomicCell[F, Map[Key, (IArray[Byte], S3Blob)]])
    extends BlobStore[F, S3Blob] {

  override def list[A](url: Url[A], recursive: Boolean = false): fs2.Stream[F, Url[S3Blob]] =
    def filesWhere(p: ((Key, (IArray[Byte], S3Blob))) => Boolean) =
      objects.get.map(
        _.toSeq
          .filter(p)
          .map { entry => url.copy(path = blobstore.url.Path(entry._1.prefix.toString).as(entry._2._2)) }
      )

    val bucket = url.authority.toString
    val prefix = url.path.nioPath

    if (recursive) {
      fs2.Stream.evalSeq(
        filesWhere { case (entryKey, (_, _)) =>
          entryKey.bucket == bucket
          && (entryKey.prefix.nioPath.startsWith(prefix)
            || prefix.startsWith("")
            || prefix.startsWith("/"))
        }
      )
    } else fs2.Stream.evalSeq(for {
      files <- filesWhere { case (entryKey, (_, _)) =>
        entryKey.bucket == bucket
        && entryKey.prefix.nioPath.startsWith(prefix)
        && entryKey.prefix.nioPath.relativize(prefix).getNameCount == 1
      }

      folders <- objects.get.map(
        _.toSeq
          .filter { case (entryKey, (_, _)) =>
            entryKey.bucket == bucket
            && entryKey.prefix.nioPath.startsWith(prefix)
            && prefix.relativize(entryKey.prefix.nioPath).getNameCount > 1
          }
          .map { entry =>
            val folderName = prefix.relativize(entry._1.prefix.nioPath).getName(0).toString
            blobstore.url.Path.createRootless(prefix.resolve(folderName).names.mkString("/") + "/")
          }
          .distinct
          .map { folderPath =>
            url.copy(path = folderPath
              .as(S3Blob(bucket, folderPath.toString.stripSuffix("/") + "/", meta = None)))
          }
      )
    } yield files ++ folders)

  override def get[A](url: Url[A], chunkSize: Int): fs2.Stream[F, Byte] =
    fs2.Stream.evalSeq(
      objects.get.map(
        _.get(Key(url.authority.toString, url.path)).map(_._1)
          .getOrElse(throw NoSuchKeyException.builder().message(s"No such key: $url").build())
      )
        .map { iarr => ArraySeq.unsafeWrapArray(iarr.unsafeArray) }
    )

  override def put[A](url: Url[A], overwrite: Boolean = true, size: Option[Long] = None): Pipe[F, Byte, Unit] = {
    (input: fs2.Stream[F, Byte]) =>
      input.compile.toVector.flatMap { bytes =>
        val bucket = url.authority.toString
        val prefix = url.path
        val key = Key(bucket, prefix)
        val s3MetaInfo = S3MetaInfo.const(constSize = Some(bytes.length), constLastModified = Some(Instant.now()))
        objects.update { objects =>
          if (!overwrite && objects.contains(key))
            throw IllegalArgumentException(s"File at path '$url' already exist.")

          objects + (key -> (IArray.from(bytes), S3Blob(bucket, prefix.toString, Some(s3MetaInfo))))
        }
      }
        .pipe(fs2.Stream.eval)
  }

  override def move[A, B](src: Url[A], dst: Url[B]): F[Unit] =
    copy(src, dst) *> objects.update(_ - Key(src.authority.toString, src.path))

  override def copy[A, B](src: Url[A], dst: Url[B]): F[Unit] =
    objects.update { objects =>
      val srcKey = Key(src.authority.toString, src.path)
      val dstKey = Key(dst.authority.toString, dst.path)

      objects.get(srcKey) match
        case None => throw NoSuchKeyException.builder().message(s"No such key: $src").build()
        case Some((bytes, _)) =>
          val s3MetaInfo = S3MetaInfo.const(constSize = Some(bytes.length), constLastModified = Some(Instant.now()))
          objects + (dstKey -> (bytes, S3Blob(dstKey.bucket, dstKey.prefix.toString, Some(s3MetaInfo))))
    }

  override def remove[A](url: Url[A], recursive: Boolean): F[Unit] =
    val key = Key(url.authority.toString, url.path)
    objects.update { objects =>
      objects.get(key) match
        case None    => throw NoSuchKeyException.builder().message(s"No such key: $url").build()
        case Some(_) => objects - key
    }

  override def putRotate[A](computeUrl: F[Url[A]], limit: Long): Pipe[F, Byte, Unit] =
    throw UnsupportedOperationException("unimplemented")

  override def stat[A](url: Url[A]): fs2.Stream[F, Url[S3Blob]] =
    fs2.Stream.eval(
      objects.get.map(
        _.get(Key(url.authority.toString, url.path)) match
          case None        => throw NoSuchKeyException.builder().message(s"No such key: $url").build()
          case Some(entry) => url.copy(path = url.path.as(entry._2))
      )
    )
}
object FakeS3BlobStore {
  private case class Key(bucket: String, prefix: blobstore.url.Path[Any])

  def apply[F[_]: Async](): F[FakeS3BlobStore[F]] =
    AtomicCell[F].of(HashMap(): Map[Key, (IArray[Byte], S3Blob)]).map(new FakeS3BlobStore(_))

  extension(p: Path)
    private def names: Seq[String] = List.tabulate(p.getNameCount)(i => p.getName(i).toString)
}
