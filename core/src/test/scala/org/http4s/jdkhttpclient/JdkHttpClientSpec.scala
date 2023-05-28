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

import cats.effect._
import org.http4s.Header
import org.http4s.Request
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.testkit.ClientRouteTestBattery
import org.http4s.client.testkit.testroutes.GetRoutes
import org.typelevel.ci._

import scala.concurrent.duration._

class JdkHttpClientSpec extends ClientRouteTestBattery("JdkHttpClient") {
  def clientResource: Resource[IO, Client[IO]] = Resource.eval(JdkHttpClient.simple[IO])

  // regression test for https://github.com/http4s/http4s-jdk-http-client/issues/395
  test("Don't error with empty body and explicit Content-Length: 0") {
    val address = server().addresses.head
    val path = GetRoutes.SimplePath
    val uri = Uri.fromString(s"http://$address$path").toOption.get
    val req: Request[IO] = Request(uri = uri)
      .putHeaders(Header.Raw(ci"Content-Length", "0"))
    val body = client().expect[String](req)
    body.assertEquals("simple path")
  }

  test("timeout request") {
    val address = server().addresses.head
    val path = GetRoutes.DelayedPath // 1s delay before response
    val uri = Uri.fromString(s"http://$address$path").toOption.get
    val req = Request[IO](uri = uri)
    val res = client().expect[String](req)
    res.as(false).timeoutTo(100.millis, IO.pure(true)).timed.flatMap { case (duration, result) =>
      IO {
        assert(clue(duration) < 1.second)
        assert(result)
      }
    }
  }
}
