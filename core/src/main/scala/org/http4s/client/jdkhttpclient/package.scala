package org.http4s.client

import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

package object jdkhttpclient {

  /** Convert a [[java.util.concurrent.CompletableFuture]] into an effect type.
    *
    * If the effect type terminates in cancellation or error, the underlying
    * [[java.util.concurrent.CompletableFuture]] is terminated in an analogous
    * manner. This is important, otherwise a resource leak may occur.
    *
    * @note Finally, regardless of how the effect and
    *       [[java.util.concurrent.CompletableFuture]] complete, the result is
    *       shifted with the given [[cats.effect.ContextShift]].
    */
  private[jdkhttpclient] def fromCompletableFutureShift[F[_], A](
      fcs: F[CompletableFuture[A]]
  )(implicit F: Concurrent[F], CS: ContextShift[F]): F[A] =
    F.bracketCase(fcs) { cs =>
      F.async[A] { cb =>
        cs.handle[Unit] { (result, err) =>
          err match {
            case null => cb(Right(result))
            case _: CancellationException => ()
            case ex: CompletionException if ex.getCause ne null => cb(Left(ex.getCause))
            case ex => cb(Left(ex))
          }
        }; ();
      }
    }((cs, ec) =>
      (ec match {
        case ExitCase.Completed => F.unit
        case ExitCase.Error(e) =>
          F.delay(cs.completeExceptionally(e))
        case ExitCase.Canceled =>
          F.delay(cs.cancel(true))
      }).void.guarantee(CS.shift)
    )
}
