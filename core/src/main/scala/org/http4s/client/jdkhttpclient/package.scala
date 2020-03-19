package org.http4s.client

import java.util.concurrent.{
  CancellationException,
  CompletableFuture,
  CompletionException,
  CompletionStage
}

import cats.implicits._
import cats.effect._
import cats.effect.implicits._

package object jdkhttpclient {
  private[jdkhttpclient] def fromCompletionStage[F[_], CF[x] <: CompletionStage[x], A](
      fcs: F[CF[A]]
  )(implicit F: Async[F], CS: ContextShift[F]): F[A] =
    fcs.flatMap { cs =>
      F.async[A] { cb =>
          cs.handle[Unit] { (result, err) =>
            err match {
              case null => cb(Right(result))
              case _: CancellationException => ()
              case ex: CompletionException if ex.getCause ne null => cb(Left(ex.getCause))
              case ex => cb(Left(ex))
            }
          }
          ()
        }
        .guarantee(CS.shift)
    }

  private[jdkhttpclient] def unsafeToCompletionStage[F[_], A](
      fa: F[A]
  )(implicit F: Effect[F]): CompletionStage[A] = {
    val cf = new CompletableFuture[A]()
    F.runAsync(fa) {
        case Right(a) => IO { cf.complete(a); () }
        case Left(e) => IO { cf.completeExceptionally(e); () }
      }
      .unsafeRunSync()
    cf
  }
}
