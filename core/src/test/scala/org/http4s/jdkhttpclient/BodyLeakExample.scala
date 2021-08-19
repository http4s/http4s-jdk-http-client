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

import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.syntax.all._
import org.http4s._
import org.http4s.client._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.syntax.all._

// This is a *manual* test for the body leak fixed in #335
// Run e.g. with `bloop run core-test --args -J-Xmx200M`
object BodyLeakExample extends IOApp {

  val app: HttpApp[IO] =
    Kleisli((_: Request[IO]) => IO.pure(Response[IO]().withEntity("Hello, HTTP")))

  def runRequest(client: Client[IO], counter: Ref[IO, Long]): IO[Unit] =
    client.status(
      Request[IO](method = Method.GET, uri = uri"http://127.0.0.1:8080")
    ) *> counter
      .updateAndGet(_ + 1L)
      .flatMap(value =>
        if (value % 1000L === 0L) {
          IO(println(s"Request count: $value"))
        } else {
          IO.unit
        }
      )

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](executionContext)
      .bindLocal(8080)
      .withHttpApp(app)
      .resource
      .use { _ =>
        for {
          client <- JdkHttpClient.simple[IO]
          counter <- Ref.of[IO, Long](0L)
          ec <- runRequest(client, counter).foreverM[ExitCode]
        } yield ec
      }

}
