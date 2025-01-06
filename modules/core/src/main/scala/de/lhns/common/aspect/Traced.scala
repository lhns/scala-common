package de.lhns.common.aspect

import cats.Show
import cats.arrow.FunctionK
import cats.syntax.all.*
import cats.tagless.aop.Aspect
import cats.tagless.syntax.all.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

def traced[Alg[_[_]], F[_] : Tracer](alg: Alg[F])(implicit aspect: Aspect.Domain[Alg, Show]): Alg[F] =
  alg
    .weaveDomain[Show]
    .mapK(new FunctionK[Aspect.Weave.Domain[F, Show, _], F] {
      override def apply[A](fa: Aspect.Weave.Domain[F, Show, A]): F[A] =
        Tracer[F].span(
          fa.codomain.name,
          fa.domain.flatMap(_.map { param =>
            import param.instance
            Attribute(param.name, param.target.value.show)
          }) *
        ).surround {
          fa.codomain.target
        }
    })
