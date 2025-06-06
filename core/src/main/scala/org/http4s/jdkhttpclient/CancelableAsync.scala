/*
 * Copyright 2019 http4s.org
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

package org.http4s.jdkhttpclient

import cats.effect.Async
import cats.effect.kernel.Cont
import cats.effect.kernel.MonadCancelThrow
import cats.~>

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

private[jdkhttpclient] object CancelableAsync {

  /** This is a direct copy-paste of [[cats.effect.kernel.AsyncPlatform.fromCompletableFuture]] with
    * `mayInterruptIfRunning` set to `true` instead of `false`.
    *
    * This is *really* important for JDK HTTP Client, since if you cancel a `CompletableFuture` with
    * `mayInterruptIfRunning` set to `false`, it will not cancel the actual HTTP request but the
    * CompletableFuture will still report a successful cancellation, leaking the connection which
    * will never be cleaned up, taking up resources until the JVM exits and blocking the graceful
    * finalization of the JDK HTTP Client.
    */
  def fromCompletableFuture[F[_], A](fut: F[CompletableFuture[A]])(implicit F: Async[F]): F[A] =
    F.cont {
      new Cont[F, A, A] {
        def apply[G[_]](implicit
            G: MonadCancelThrow[G]
        ): (Either[Throwable, A] => Unit, G[A], F ~> G) => G[A] = { (resume, get, lift) =>
          G.uncancelable { poll =>
            G.flatMap(poll(lift(fut))) { cf =>
              val go = F.delay {
                cf.handle[Unit] {
                  case (a, null) => resume(Right(a))
                  case (_, t) =>
                    resume(Left(t match {
                      case e: CompletionException if e.getCause ne null => e.getCause
                      case _ => t
                    }))
                }
              }

              val await = G.onCancel(
                poll(get),
                // if cannot cancel, fallback to get
                G.ifM(lift(F.delay(cf.cancel(true))))(G.unit, G.void(get))
              )

              G.productR(lift(go))(await)
            }
          }
        }
      }
    }
}
