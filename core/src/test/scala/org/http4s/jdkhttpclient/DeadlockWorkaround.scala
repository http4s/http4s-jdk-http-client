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

import javax.net.ssl.SSLHandshakeException

import cats.effect._
import munit.CatsEffectSuite
import org.http4s.implicits._

class DeadlockWorkaround extends CatsEffectSuite {

  test("fail to connect via TLSv1.3 on Java 11") {
    if (Runtime.version().feature() > 11) IO.pure(true)
    else
      for {
        http <- JdkHttpClient.simple[IO]
        ws <- JdkWSClient.simple[IO]
        testSSLFailure = (r: IO[Unit]) => r.intercept[SSLHandshakeException]
        _ <- testSSLFailure(http.expect[Unit](uri"https://tls13.1d.pw"))
        _ <- testSSLFailure(
          ws.connectHighLevel(WSRequest(uri"wss://tls13.1d.pw")).use(_ => IO.unit)
        )
      } yield ()
  }

}
