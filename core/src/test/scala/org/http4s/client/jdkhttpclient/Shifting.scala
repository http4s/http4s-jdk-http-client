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

package org.http4s.client.jdkhttpclient

import java.util.concurrent.Executors

import cats.effect._
import cats.effect.testing.specs2.CatsEffect
import org.http4s.implicits._
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext

class Shifting extends Specification with CatsEffect {
  val testThreadName = "test-thread-name"
  val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool { r =>
      val t = new Thread(r)
      t.setName(testThreadName)
      t
    })
  implicit val timer: cats.effect.Timer[IO] = IO.timer(ec)
  implicit val cs: cats.effect.ContextShift[IO] = IO.contextShift(ec)

  "The clients" should {
    "shift back from the HTTP thread pool" in {
      for {
        http <- JdkHttpClient.simple[IO]
        ws <- JdkWSClient.simple[IO]
        threadName = IO(Thread.currentThread().getName)
        name1 <- http.expect[String](uri"https://example.org") *> threadName
        name2 <- ws.connectHighLevel(WSRequest(uri"wss://echo.websocket.org")).use(_ => threadName)
      } yield List(name1, name2).forall(_ == testThreadName)
    }
  }
}
