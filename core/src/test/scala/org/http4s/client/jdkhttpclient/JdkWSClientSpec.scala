package org.http4s.client.jdkhttpclient

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.testing.specs2.CatsEffect
import cats.implicits._
import fs2.Stream
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.specs2.mutable.Specification
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

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
      for {
        ref <- Ref[IO].of(List.empty[WebSocketFrame])
        finished <- Deferred[IO, Unit]
        // we use Java-Websocket because Blaze has a bug concerning the handling of Close frames and shutting down
        server = new WebSocketServer(new InetSocketAddress("localhost", 8080)) {
          override def onOpen(conn: WebSocket, handshake: ClientHandshake) = ()
          override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) =
            ref
              .update(_ :+ WebSocketFrame.Close(code, reason).fold(throw _, identity))
              .unsafeRunSync()
          override def onMessage(conn: WebSocket, message: String) =
            ref.update(_ :+ WebSocketFrame.Text(message)).unsafeRunSync()
          override def onMessage(conn: WebSocket, message: ByteBuffer) =
            ref.update(_ :+ WebSocketFrame.Binary(ByteVector(message))).unsafeRunSync()
          override def onError(conn: WebSocket, ex: Exception) = println(s"WS error $ex")
          override def onStart() = {
            val req = WSRequest(uri"ws://localhost:8080")
            val p = for {
              _ <- webSocket.connect(req).use(conn => conn.send(WSFrame.Text("hi blaze")))
              _ <- Timer[IO].sleep(1.seconds)
              _ <- webSocket.connectHighLevel(req).use { conn =>
                conn.send(WSFrame.Text("hey blaze"))
              }
              _ <- Timer[IO].sleep(1.seconds)
              _ <- finished.complete(())
            } yield ()
            p.unsafeRunAsync(_ => ())
          }
        }
        frames <- IO(server.start())
          .bracket(_ => finished.get *> ref.get)(_ => IO(server.stop(0)))
      } yield frames mustEqual List(
        WebSocketFrame.Text("hi blaze"),
        WebSocketFrame.Close(1000, "").fold(throw _, identity),
        WebSocketFrame.Text("hey blaze"),
        WebSocketFrame.Close(1000, "").fold(throw _, identity)
      )
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
          val routes = HttpRoutes.of[IO] { case r @ GET -> Root =>
            ref.set(r.headers.some) *> WebSocketBuilder[IO].build(Stream.empty, _ => Stream.empty)
          }
          BlazeServerBuilder[IO](global)
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
