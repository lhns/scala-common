package de.lhns.common.skunk

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{MonadCancelThrow, Resource}
import skunk.Session
import skunk.data.{TransactionAccessMode, TransactionIsolationLevel}

type ConnectionF[F[_], A] = Kleisli[F, Resource[F, Session[F]], A]

extension [F[_] : MonadCancelThrow, A](connectionF: ConnectionF[F, A]) {
  def transaction: ConnectionF[F, A] =
    Kleisli { sessionResource =>
      sessionResource.use { session =>
        session.transaction.use { _ =>
          connectionF(Resource.pure[F, Session[F]](session))
        }
      }
    }

  def transaction(isolationLevel: TransactionIsolationLevel, accessMode: TransactionAccessMode): ConnectionF[F, A] =
    Kleisli { sessionResource =>
      sessionResource.use { session =>
        session.transaction(isolationLevel, accessMode).use { _ =>
          connectionF(Resource.pure[F, Session[F]](session))
        }
      }
    }
}

object ConnectionF {
  def apply[F[_] : MonadCancelThrow, A](f: Session[F] => F[A]): ConnectionF[F, A] =
    Kleisli { sessionResource =>
      sessionResource.use { session =>
        f(session)
      }
    }

  def liftF[F[_], A](f: F[A]): ConnectionF[F, A] =
    Kleisli.liftF(f)
}

extension [F[_]](session: Session[F]) {
  def trans: FunctionK[ConnectionF[F, _], F] = new FunctionK[ConnectionF[F, _], F] {
    override def apply[A](fa: ConnectionF[F, A]): F[A] =
      fa(Resource.pure[F, Session[F]](session))
  }
}

extension [F[_]](sessionResource: Resource[F, Session[F]]) {
  def trans: FunctionK[ConnectionF[F, _], F] = new FunctionK[ConnectionF[F, _], F] {
    override def apply[A](fa: ConnectionF[F, A]): F[A] =
      fa(sessionResource)
  }
}
