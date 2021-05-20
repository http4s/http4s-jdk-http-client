/*
 * Copyright 2021 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s

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
      }.guarantee(CS.shift)
    }((cs, ec) =>
      (ec match {
        case ExitCase.Completed => F.unit
        case ExitCase.Error(e) =>
          F.delay(cs.completeExceptionally(e)).void
        case ExitCase.Canceled =>
          F.delay(cs.cancel(true)).void
      })
    )
}
