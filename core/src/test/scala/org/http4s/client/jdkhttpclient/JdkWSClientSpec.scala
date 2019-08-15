package org.http4s.client.jdkhttpclient

import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.specs2.CatsEffect
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.specs2.mutable.Specification
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scodec.bits.ByteVector

class JdkWSClientSpec extends Specification with CatsEffect {

  implicit val timer: cats.effect.Timer[IO] = IO.timer(global)
  implicit val cs: cats.effect.ContextShift[IO] = IO.contextShift(global)

  val webSocket: WSClient[IO] = JdkWSClient.simple[IO].unsafeRunSync()

  val wsUri = uri"wss://echo.websocket.org"

  "A WebSocket client" should {
    "send and receive frames in low-level mode" in {
      webSocket
        .connect(WSRequest(wsUri))
        .use { conn =>
          for {
            _ <- conn.send(WSFrame.Text("bar"))
            _ <- conn.sendMany(List(WSFrame.Binary(ByteVector(3, 99, 12)), WSFrame.Text("foo")))
            _ <- conn.send(WSFrame.Close(1000, "goodbye"))
            recv <- conn.receiveStream.compile.toList
          } yield recv
        }
        .map(
          _ mustEqual List(
            WSFrame.Text("bar"),
            WSFrame.Binary(ByteVector(3, 99, 12)),
            WSFrame.Text("foo"),
            WSFrame.Close(1000, "goodbye")
          )
        )
    }

    "send and receive frames in high-level mode" in {
      webSocket
        .connectHighLevel(WSRequest(wsUri))
        .use { conn =>
          for {
            _ <- conn.send(WSFrame.Binary(ByteVector(15, 2, 3)))
            _ <- conn.sendMany(List(WSFrame.Text("foo"), WSFrame.Text("bar")))
            _ <- conn.sendClose()
            recv <- conn.receiveStream.compile.toList
          } yield recv
        }
        .map(
          _ mustEqual List(
            WSFrame.Binary(ByteVector(15, 2, 3)),
            WSFrame.Text("foo"),
            WSFrame.Text("bar")
          )
        )
    }

    "group frames by their `last` attribute in high-level mode" in {
      webSocket
        .connectHighLevel(WSRequest(wsUri))
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
            _ <- conn.sendClose()
            recv <- conn.receiveStream.compile.toList
          } yield recv
        }
        .map(
          _ mustEqual List(
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
      Queue.unbounded[IO, WebSocketFrame].flatMap { queue =>
        val routes = HttpRoutes.of[IO] {
          case GET -> Root =>
            WebSocketBuilder[IO].build(Stream.empty, _.evalTap(wsf => IO(System.out.println(wsf))).evalMap(queue.enqueue1))
        }

        def expect(wsf: WebSocketFrame) =
          queue.dequeue1.timeout(1.second).map(_ must_== wsf)

        BlazeServerBuilder[IO]
          .bindHttp(8080)
          .withHttpApp(routes.orNotFound)
          .resource
          .use { _ =>
            val req = WSRequest(uri"ws://localhost:8080")
            for {
              // _ <- webSocket.connect(req).use { conn =>
              //   conn.send(WSFrame.Text("hi blaze"))
              // }
              // _ <- expect(WebSocketFrame.Text("hi blaze"))
              // _ <- expect(WebSocketFrame.Close(1000, "").fold(throw _, identity))
              _ <- webSocket.connectHighLevel(req).use { conn =>
                conn.send(WSFrame.Text("hey blaze"))
              }
              _ <- expect(WebSocketFrame.Text("hey blaze"))
              _ <- expect(WebSocketFrame.Close(1000, "").fold(throw _, identity))
            } yield ok
          }
      }
    }

    "send headers" in {
      val sentHeaders = Headers.of(
        Header("foo", "bar"),
        Header("Sec-Websocket-Protocol", "proto"),
        Header("aaaa", "bbbbb")
      )
      Ref[IO]
        .of(None: Option[Headers])
        .flatMap { ref =>
          val routes = HttpRoutes.of[IO] {
            case r @ GET -> Root =>
              ref.set(r.headers.some) *> WebSocketBuilder[IO].build(Stream.empty, _ => Stream.empty)
          }
          BlazeServerBuilder[IO]
            .bindHttp(8081)
            .withHttpApp(routes.orNotFound)
            .resource
            .use { _ =>
              webSocket.connect(WSRequest(uri"ws://localhost:8081", sentHeaders)).use(_ => IO.unit)
            } *> ref.get
        }
        .map(_.map(recvHeaders => sentHeaders.toList.toSet.subsetOf(recvHeaders.toList.toSet)))
        .map(_ must beSome(true))
    }
  }

}
