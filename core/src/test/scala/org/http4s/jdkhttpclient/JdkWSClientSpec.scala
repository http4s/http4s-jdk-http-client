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
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.http4s._
import org.http4s.client.websocket._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci._
import scodec.bits.ByteVector

class JdkWSClientSpec extends CatsEffectSuite {

  val webSocket: IOFixture[WSClient[IO]] =
    ResourceSuiteLocalFixture("webSocket", JdkWSClient.simple[IO])
  val echoServerUri: IOFixture[Uri] =
    ResourceSuiteLocalFixture(
      "echoServerUri",
      EmberServerBuilder
        .default[IO]
        .withPort(port"0")
        .withHttpWebSocketApp { wsb =>
          HttpRoutes
            .of[IO] { case GET -> Root => wsb.build(identity) }
            .orNotFound
        }
        .build
        .map(s => httpToWsUri(s.baseUri))
    )

  override def munitFixtures: Seq[IOFixture[_]] = List(webSocket, echoServerUri)

  test("send and receive frames in low-level mode") {
    webSocket()
      .connect(WSRequest(echoServerUri()))
      .use { conn =>
        for {
          _ <- conn.send(WSFrame.Text("bar"))
          _ <- conn.sendMany(List(WSFrame.Binary(ByteVector(3, 99, 12)), WSFrame.Text("foo")))
          _ <- conn.send(WSFrame.Close(1000, "goodbye"))
          recv <- conn.receiveStream.compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Text("bar"),
          WSFrame.Binary(ByteVector(3, 99, 12)),
          WSFrame.Text("foo"),
          WSFrame.Close(1000, "")
        )
      )
  }

  test("send and receive frames in high-level mode") {
    webSocket()
      .connectHighLevel(WSRequest(echoServerUri()))
      .use { conn =>
        for {
          _ <- conn.send(WSFrame.Binary(ByteVector(15, 2, 3)))
          _ <- conn.sendMany(List(WSFrame.Text("foo"), WSFrame.Text("bar")))
          recv <- conn.receiveStream.take(3).compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Binary(ByteVector(15, 2, 3)),
          WSFrame.Text("foo"),
          WSFrame.Text("bar")
        )
      )
  }

  test("group frames by their `last` attribute in high-level mode") {
    webSocket()
      .connectHighLevel(WSRequest(echoServerUri()))
      .use { conn =>
        for {
          _ <- conn.sendMany(
            List(
              WSFrame.Text("1", last = false),
              WSFrame.Text("2", last = false),
              WSFrame.Text("3"),
              WSFrame.Binary(ByteVector(1)),
              WSFrame.Binary(ByteVector(2), last = false),
              WSFrame.Binary(ByteVector(3), last = false),
              WSFrame.Binary(ByteVector(4)),
              WSFrame.Text("4", last = false),
              WSFrame.Text("5"),
              WSFrame.Binary(ByteVector(5), last = false),
              WSFrame.Binary(ByteVector(6)),
              WSFrame.Text("6"),
              WSFrame.Binary(ByteVector(7), last = false)
            )
          )
          recv <- conn.receiveStream.take(6).compile.toList
        } yield recv
      }
      .assertEquals(
        List(
          WSFrame.Text("123"),
          WSFrame.Binary(ByteVector(1)),
          WSFrame.Binary(ByteVector(2, 3, 4)),
          WSFrame.Text("45"),
          WSFrame.Binary(ByteVector(5, 6)),
          WSFrame.Text("6")
        )
      )
  }

  test("automatically close the connection") {
    val closeFrame = WebSocketFrame.Close(1000, "").toTry.get
    val frames = for {
      ref <- Ref[IO].of(List.empty[WebSocketFrame])
      server = EmberServerBuilder
        .default[IO]
        .withPort(port"0")
        .withHttpWebSocketApp { wsb =>
          HttpRoutes
            .of[IO] { case GET -> Root =>
              wsb
                .withOnClose(ref.update(_ :+ closeFrame))
                .build(_.evalTap(f => ref.update(_ :+ f)))
            }
            .orNotFound
        }
        .build
        .map(s => WSRequest(httpToWsUri(s.baseUri)))
      _ <- server.use { req =>
        webSocket().connect(req).use(conn => conn.send(WSFrame.Text("hi ember"))) *>
          webSocket().connectHighLevel(req).use { conn =>
            conn.send(WSFrame.Text("hey ember"))
          }
      }
      frames <- ref.get
    } yield frames
    frames.assertEquals(
      List(
        WebSocketFrame.Text("hi ember"),
        closeFrame,
        WebSocketFrame.Text("hey ember"),
        closeFrame
      )
    )
  }

  test("send headers") {
    val sentHeaders = Headers(
      Header.Raw(ci"foo", "bar"),
      Header.Raw(ci"Sec-Websocket-Protocol", "proto"),
      Header.Raw(ci"aaaa", "bbbbb")
    )
    Ref[IO]
      .of(None: Option[Headers])
      .flatMap { ref =>
        EmberServerBuilder
          .default[IO]
          .withPort(port"0")
          .withHttpWebSocketApp { wsb =>
            HttpRoutes
              .of[IO] { case r @ GET -> Root =>
                ref.set(r.headers.some) *> wsb.build(Stream.empty, _ => Stream.empty)
              }
              .orNotFound
          }
          .build
          .use { server =>
            webSocket()
              .connect(WSRequest(httpToWsUri(server.baseUri)).withHeaders(sentHeaders))
              .use(_ => IO.unit)
          } *> ref.get
      }
      .map(_.map(recvHeaders => sentHeaders.headers.toSet.subsetOf(recvHeaders.headers.toSet)))
      .assertEquals(Some(true))
  }

  def httpToWsUri(uri: Uri): Uri = uri.copy(scheme = scheme"ws".some)

}
