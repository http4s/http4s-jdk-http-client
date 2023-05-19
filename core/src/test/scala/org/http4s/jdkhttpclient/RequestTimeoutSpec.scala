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

import cats.data._
import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder

import java.net.http.HttpTimeoutException
import scala.concurrent.duration._

class RequestTimeoutSpec extends CatsEffectSuite {
  private val app: HttpApp[IO] =
    Kleisli((_: Request[IO]) => IO.pure(Response[IO](body = fs2.Stream.never[IO])))

  override val munitIOTimeout: FiniteDuration = 5.seconds

  private val server = ResourceSuiteLocalFixture(
    "server",
    EmberServerBuilder
      .default[IO]
      .withPort(port"0")
      .withHttpApp(app)
      .build
      .map(_.baseUri)
  )
  private val client = ResourceSuiteLocalFixture(
    "client",
    Resource.eval(
      JdkHttpClient.simple[IO](
        requestTimeout = Some(1.second),
        connectionTimeout = Some(2.seconds)
      )
    )
  )

  override def munitFixtures: Seq[IOFixture[_]] = List(server, client)

  test("Must apply request timeout".ignore) {
    client().expect[String](server()).intercept[HttpTimeoutException]
  }

}
