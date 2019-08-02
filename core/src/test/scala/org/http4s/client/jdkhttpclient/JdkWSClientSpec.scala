package org.http4s.client.jdkhttpclient

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.Stream
import org.http4s._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.websocket.WebSocketFrame
import org.specs2.mutable.Specification
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class JdkWSClientSpec extends Specification {

  implicit val timer: cats.effect.Timer[IO] = IO.timer(global)
  implicit val cs: cats.effect.ContextShift[IO] = IO.contextShift(global)

  val webSocket: WSClient[IO] = JdkWSClient.simple[IO].unsafeRunSync()

  val wsUri = uri"wss://echo.websocket.org"

  "A WebSocket client" should {
    "send and receive frames in low-level mode" in {
      val p = webSocket.connect(WSRequest(wsUri)).use { conn =>
        for {
          _ <- conn.send(WSFrame.Text("bar"))
          _ <- conn.sendMany(List(WSFrame.Binary(ByteVector(3, 99, 12)), WSFrame.Text("foo")))
          _ <- conn.send(WSFrame.Close(1000, "goodbye"))
          recv <- conn.receiveStream.compile.toList
        } yield recv
      }
      p.unsafeRunTimed(3.seconds) must beSome(
        List(
          WSFrame.Text("bar"),
          WSFrame.Binary(ByteVector(3, 99, 12)),
          WSFrame.Text("foo"),
          WSFrame.Close(1000, "goodbye")
        )
      )
    }

    "send and receive frames in high-level mode" in {
      val p = webSocket.connectHighLevel(WSRequest(wsUri)).use { conn =>
        for {
          _ <- conn.send(WSFrame.Binary(ByteVector(15, 2, 3)))
          _ <- conn.sendMany(List(WSFrame.Text("foo"), WSFrame.Text("bar")))
          _ <- conn.sendClose()
          recv <- conn.receiveStream.compile.toList
        } yield recv
      }
      p.unsafeRunTimed(3.seconds) must beSome(
        List(
          WSFrame.Binary(ByteVector(15, 2, 3)),
          WSFrame.Text("foo"),
          WSFrame.Text("bar")
        )
      )
    }

    "group frames by their `last` attribute in high-level mode" in {
      val p = webSocket.connectHighLevel(WSRequest(wsUri)).use { conn =>
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
          _ <- conn.sendClose()
          recv <- conn.receiveStream.compile.toList
        } yield recv
      }
      p.unsafeRunTimed(3.seconds) must beSome(
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

    "automatically close the connection" in {
      val p = {
        import org.http4s.dsl.io._
        import org.http4s.implicits._
        import org.http4s.server.websocket._
        Ref[IO].of(List.empty[WebSocketFrame]).flatMap { ref =>
          val routes = HttpRoutes.of[IO] {
            case GET -> Root =>
              WebSocketBuilder[IO].build(Stream.empty, _.evalMap(wsf => ref.update(_ :+ wsf)))
          }
          BlazeServerBuilder[IO]
            .bindHttp(8080)
            .withHttpApp(routes.orNotFound)
            .resource
            .use { _ =>
              val req = WSRequest(uri"ws://localhost:8080")
              for {
                _ <- webSocket.connect(req).use { conn =>
                  conn.send(WSFrame.Text("hi blaze"))
                }
                _ <- Timer[IO].sleep(1.second)
                _ <- webSocket.connectHighLevel(req).use { conn =>
                  conn.send(WSFrame.Text("hey blaze"))
                }
                _ <- Timer[IO].sleep(1.second)
              } yield ()
            } *> ref.get
        }
      }
      p.unsafeRunTimed(4.seconds) must beSome(
        List(
          WebSocketFrame.Text("hi blaze"),
          WebSocketFrame.Close(1000, "").fold(throw _, identity),
          WebSocketFrame.Text("hey blaze"),
          WebSocketFrame.Close(1000, "").fold(throw _, identity)
        )
      )
    }
  }

}
