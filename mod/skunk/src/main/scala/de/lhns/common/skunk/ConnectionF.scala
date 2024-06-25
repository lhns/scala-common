package de.lhns.common.skunk

import cats.arrow.FunctionK
import cats.data.Kleisli
import skunk.Session

type ConnectionF[F[_], A] = Kleisli[F, Session[F], A]

def ConnectionF[F[_], A](f: Session[F] => F[A]): ConnectionF[F, A] = Kleisli(f)

extension[F[_]](session: Session[F]) {
  def trans: FunctionK[ConnectionF[F, _], F] = new FunctionK[ConnectionF[F, _], F] {
    override def apply[A](fa: ConnectionF[F, A]): F[A] =
      fa(session)
  }
}
