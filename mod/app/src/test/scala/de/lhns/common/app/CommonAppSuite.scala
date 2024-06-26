package de.lhns.common.app

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all.*
import munit.{CatsEffectSuite, FunSuite}
import org.typelevel.log4cats.{Logger, LoggerFactory}

class CommonAppSuite extends CatsEffectSuite {
  object CommonAppTest extends CommonApp {
    override def run(context: CommonApp.Context[IO]): Resource[IO, ExitCode] = {
      import context.given

      given Logger[IO] = LoggerFactory[IO].getLogger

      Logger[IO].info("run").toResource.as(ExitCode.Success)
    }
  }

  test("create CommonApp") {
    CommonAppTest.run(List.empty).use_
  }
}
