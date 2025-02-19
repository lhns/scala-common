package de.lhns.common.s3

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.mtl.syntax.all.*
import cats.effect.std.syntax.all.*
import blobstore.url.Url
import cats.effect.IO
import munit.CatsEffectSuite

//noinspection TypeAnnotation
class FakeS3BlobStoreTest extends CatsEffectSuite {
  val s = "hallo123".getBytes()

  extension [T](s1: Seq[T])
    def containsSameElementsInAnyOrder(s2: Seq[T]): Boolean =
      s1.length == s2.length && s2.forall(s1.contains(_))

  private def put(s3: FakeS3BlobStore[IO], s: String, url: String) =
    fs2.Stream[IO, Byte](s.getBytes*)
      .through(s3.put(Url.unsafe(url), overwrite = false))
      .compile.drain

  test("FakeS3BlobStore should put file")(for {
    s3 <- FakeS3BlobStore[IO]()
    _ <- put(s3, "hallo123", "s3://bucket/dir/file.txt")
    readFile <- s3.get(Url.unsafe("s3://bucket/dir/file.txt")).compile.toVector.map(_.toArray)
    _ = assertEquals(String(readFile), "hallo123")
  } yield ())

  test("FakeS3BlobStore should list files and directories that are direct children")(for {
    s3 <- FakeS3BlobStore[IO]()
    _ <- put(s3, "hallo", "s3://bucket1/dir/dir2/file.txt")
    _ <- put(s3, "hallo", "s3://bucket2/dir/dir3/file2.txt")
    _ <- put(s3, "hallo", "s3://bucket1/dir/file3.txt")
    _ <- put(s3, "hallo", "s3://bucket1/file4.txt")
    l <- s3.list(Url.unsafe("s3://bucket1/dir")).compile.toVector
    _ = assert(clue(l) containsSameElementsInAnyOrder clue(Seq(
      Url.unsafe("s3://bucket1/dir/file3.txt"),
      Url.unsafe("s3://bucket1/dir/dir2")
    )))
    _ = assertEquals(l.count(_.representation.isDir), 1)
    _ = assertEquals(l.count(!_.representation.isDir), 1)
  } yield ())

  test("FakeS3BlobStore should list files recursively but not directories")(for {
    s3 <- FakeS3BlobStore[IO]()
    _ <- put(s3, "hallo", "s3://bucket1/dir/dir2/file.txt")
    _ <- put(s3, "hallo", "s3://bucket1/dir/dir3/file2.txt")
    _ <- put(s3, "hallo", "s3://bucket1/dir/file3.txt")
    _ <- put(s3, "hallo", "s3://bucket1/file4.txt")
    _ <- put(s3, "hallo", "s3://bucket2/file5.txt")
    l <- s3.list(Url.unsafe("s3://bucket1/dir"), recursive = true).compile.toVector
    _ = assert(clue(l) containsSameElementsInAnyOrder clue(Seq(
      Url.unsafe("s3://bucket1/dir/file3.txt"),
      Url.unsafe("s3://bucket1/dir/dir2/file.txt"),
      Url.unsafe("s3://bucket1/dir/dir3/file2.txt")
    )))
  } yield ())

  test("FakeS3BlobStore should copy file")(for {
    s3 <- FakeS3BlobStore[IO]()
    _ <- put(s3, "hallo", "s3://bucket1/dir/file.txt")
    _ <- s3.copy(Url.unsafe("s3://bucket1/dir/file.txt"), Url.unsafe("s3://bucket1/dir/file2.txt"))
    l <- s3.list(Url.unsafe("s3://bucket1"), recursive = true).compile.toVector
    _ = assert(clue(l) containsSameElementsInAnyOrder clue(Seq(
      Url.unsafe("s3://bucket1/dir/file.txt"),
      Url.unsafe("s3://bucket1/dir/file2.txt")
    )))
    s <- s3.get(Url.unsafe("s3://bucket1/dir/file2.txt")).compile.toVector.map(_.toArray).map(String(_))
    _ = assertEquals(s, "hallo")
  } yield ())

  test("FakeS3BlobStore should move file")(for {
    s3 <- FakeS3BlobStore[IO]()
    _ <- put(s3, "hallo", "s3://bucket1/dir/file.txt")
    _ <- s3.move(Url.unsafe("s3://bucket1/dir/file.txt"), Url.unsafe("s3://bucket1/dir/file2.txt"))
    l <- s3.list(Url.unsafe("s3://bucket1"), recursive = true).compile.toVector
    _ = assert(clue(l) containsSameElementsInAnyOrder clue(Seq(
      Url.unsafe("s3://bucket1/dir/file2.txt")
    )))
    s <- s3.get(Url.unsafe("s3://bucket1/dir/file2.txt")).compile.toVector.map(_.toArray).map(String(_))
    _ = assertEquals(s, "hallo")
  } yield ())

  test("FakeS3BlobStore should remove file")(for {
    s3 <- FakeS3BlobStore[IO]()
    _ <- put(s3, "hallo", "s3://bucket1/dir/file.txt")
    _ <- s3.remove(Url.unsafe("s3://bucket1/dir/file.txt"))
    l <- s3.list(Url.unsafe("s3://bucket1"), recursive = true).compile.toVector
    _ = assert(clue(l).isEmpty)
  } yield ())
}
