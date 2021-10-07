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

package org.http4s.jdkhttpclient

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import cats.data._
import cats.effect._
import cats.effect.std.Semaphore
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.server._
import org.http4s.blaze.server._

final class CompletableFutureTerminationTest extends CatsEffectSuite {
  import CompletableFutureTerminationTest._

  private val duration: FiniteDuration =
    FiniteDuration(50L, TimeUnit.MILLISECONDS)

  // This test ensures that converting from a
  // java.util.concurrent.CompletableFuture to an effect type, such as IO,
  // will properly terminate the CompletableFuture if the resulting effect is
  // terminated externally, e.g. with a timeout.
  //
  // This is _really_ important, because the JDK HttpClient may buffer
  // resources and/or continue execution if external termination events don't
  // cancel/completeExceptionally the CompletableFuture. In the best case this
  // would yield unnecessary CPU cycles, and in the worst it is a memory leak.
  //
  // This is _not_ an issue with the fs2 Reactive streams implementation. That
  // implementation _will_ terminate the result if `onError` or `onComplete`
  // is invoked or if the `fs2.Stream` value terminates in error. The issue is
  // that if the outer effect fails _before_ any data has started to be
  // processed in the CompletableFuture, then no cancellation or exception is
  // sent to the fs2 reactive streams wrapper. This causes a small amount of
  // data to be buffered, waiting to be read, but it will never be read.
  //
  // There is a note about this in the JavaDocs. The Response of a HttpClient
  // must be drained via an `onError` or `onComplete` observed by the response
  // body handler or a cancellation via the CompletableFuture.
  //
  // See: https://docs.oracle.com/en/java/javase/14/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html
  test("Terminating an effect generated from a CompletableFuture") {
    (Semaphore[IO](1L), Deferred[IO, Observation[HttpResponse[String]]], Semaphore[IO](1L)).tupled
      .flatMap { case (stallServer, observation, gotRequest) =>
        // Acquire the `stallServer` semaphore so that the server will not
        // return _any_ bytes until we release a permit.
        stallServer.acquire *>
          // Acquire the `gotRequest` semaphore. The server will release this
          // once it gets our Request. We wait until this happens to start our
          // timeout logic.
          gotRequest.acquire *>
          // Start a Http4s Server, it will be terminated at the conclusion of
          // this test.
          stallingServerR[IO](stallServer, gotRequest).use { (server: Server) =>
            // Call the server, using the JDK client. We call directly with
            // the JDK client because we need to have low level control over
            // the result to observe whether or not the
            // java.util.concurrent.CompletableFuture is still executing (and
            // holding on to resources).
            callServer[IO](server).flatMap((cf: CompletableFuture[HttpResponse[String]]) =>
              // Attach a handler onto the result. This will populate our
              // `observation` Deferred value when the CompletableFuture
              // finishes for any reason.
              //
              // We start executing this in the background, so that we
              // asynchronously populate our Observation.
              observeCompletableFuture(observation, cf).start.flatMap(fiber =>
                // Wait until we are sure the Http4s Server has received the
                // request.
                gotRequest.acquire *>
                  // Lift the CompletableFuture to a IO value and attach a
                  // (short) timeout to the termination.
                  //
                  // Important! The IO result _must_ be terminated via the
                  // timeout _before any bytes_ have been received by the JDK
                  // HttpClient in order to validate resource safety. Once we
                  // start getting bytes back, the CompletableFuture _is
                  // complete_ and we are in a different context.
                  //
                  // Notice that we release stallServer _after_ the
                  // timeout. _This is the crux of this entire test_. Once
                  // we release `stallServer`, the Http4s Server will
                  // attempt to send back an Http Response to our JDK
                  // client. If the CompletableFuture and associated
                  // resources were properly cleaned up after the
                  // timeoutTo terminated the running effect, then the JDK
                  // client connection will either be closed, or the
                  // attempt to invoke `complete` on the
                  // `CompletableFuture` will fail, in both cases
                  // releasing any resources being held. If not, then it
                  // will still receive bytes, meaning there is a resource
                  // leak.
                  fromCompletableFuture(IO(cf)).void
                    .timeoutTo(duration, stallServer.release) *>
                  // After the timeout has triggered, wait for the observation to complete.
                  fiber.join *>
                  // Check our observation. Whether or not there is an exception
                  // is not actually relevant to the success case. What _is_
                  // important is that there is no result. If there is a result,
                  // then that means that _after_ `timeoutTo` released
                  // `stallServer` the CompletableFuture for the Http response
                  // body still processed data, which indicates a resource leak.
                  observation.get.flatMap {
                    case Observation(None, _) => IO.pure(true)
                    case otherwise =>
                      IO.raiseError(new AssertionError(s"Expected no result, got $otherwise"))
                  }
              )
            )
          }
      }
  }
}

object CompletableFutureTerminationTest {

  /** ADT to contain the result of an invocation to
    * [[java.util.concurrent.CompletionStage#handleAsync]]
    *
    * @note
    *   [[scala.Option]] is used because either or both of these values may be `null` in the JRE
    *   API.
    *
    * @note
    *   This is ''not'' a disjunction, e.g. [[scala.Either]]. The JRE API dictates that both values
    *   might be non-null and both values might be `null`.
    */
  private final case class Observation[A](
      result: Option[A],
      t: Option[Throwable]
  )

  /** A resource which provides a Http4s Server, which can stall the generation of a http response
    * until signaled by test code.
    *
    * This provides the ability to prevent the server from returning a response until a permit is
    * released. It is meant to only process one test request.
    *
    * @param semaphore
    *   A permit will be acquired and released form this for each request. Drain it before sending a
    *   request to the server and then release a permit when you want to server to process the
    *   request.
    * @param gotRequest
    *   A permit will be released into this semaphore each time a request is received. After sending
    *   a request to this server, you can acquire a permit from this semaphore in your test code to
    *   ensure the server has received the request. This permit is acquired ''before'' one is
    *   acquired from `semaphore`.
    */
  private def stallingServerR[F[_]](
      semaphore: Semaphore[F],
      gotRequest: Semaphore[F]
  )(implicit F: Async[F]): Resource[F, Server] =
    BlazeServerBuilder[F]
      .withHttpApp(
        Kleisli(
          Function.const(
            gotRequest.release *>
              semaphore.permit.use(_ => F.pure(Response[F]()))
          )
        )
      )
      .bindAny()
      .resource

  /** Just a scala wrapper class to make it easier to generate a [[java.util.function.BiFunction]].
    */
  private final case class JBiFunction[A, B, C](f: A => B => C)
      extends java.util.function.BiFunction[A, B, C] {
    override def apply(a: A, b: B): C =
      f(a)(b)
  }

  /** Given a [[cats.effect.concurrent.Deferred]] value and a
    * [[java.util.concurrent.CompletableFuture]] value, attach a handler to the `CompletableFuture`
    * which reports an [[Observation]] of the result in all cases (success/cancellation/failure).
    */
  private def observeCompletableFuture[F[_], A](
      observe: Deferred[F, Observation[A]],
      cf: CompletableFuture[A]
  )(implicit F: Async[F]): F[Unit] =
    F.async_ { (cb: Either[Throwable, Observation[A]] => Unit) =>
      cf.handleAsync[Unit](
        JBiFunction[A, Throwable, Unit](result =>
          t => cb(Right(Observation(Option(result), Option(t))))
        )
      ); ();
    }.flatMap(observe.complete(_).void)

  /** Given a Http4s Server, make a GET request to `/` using a JDK HttpClient and return the result
    * in a [[java.util.concurrent.CompletableFuture]].
    */
  private def callServer[F[_]](
      server: Server
  )(implicit F: Sync[F]): F[CompletableFuture[HttpResponse[String]]] =
    for {
      jURI <- F.catchNonFatal(new URI(server.baseUri.renderString))
      client <- F.delay(HttpClient.newHttpClient)
      result <- F.delay(
        client.sendAsync(HttpRequest.newBuilder(jURI).build(), HttpResponse.BodyHandlers.ofString)
      )
    } yield result
}
