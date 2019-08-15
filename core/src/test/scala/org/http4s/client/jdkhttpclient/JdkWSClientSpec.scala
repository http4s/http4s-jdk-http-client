package org.http4s.client.jdkhttpclient

import cats.effect._
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

class JdkWSClientSpec extends Specification with CatsEffect {

  implicit val timer: cats.effect.Timer[IO] = IO.timer(global)
  implicit val cs: cats.effect.ContextShift[IO] = IO.contextShift(global)

  val webSocket: WSClient[IO] = JdkWSClient.simple[IO].unsafeRunSync()

  val wsUri = uri"wss://echo.websocket.org"

  "A WebSocket client" should {

    "automatically close the connection" in {
      Queue.unbounded[IO, WebSocketFrame].flatMap { queue =>
        val routes = HttpRoutes.of[IO] {
          case GET -> Root =>
            WebSocketBuilder[IO].build(
              Stream.empty,
              _.evalTap(wsf => IO(System.out.println("RCV" + wsf)))
                .evalMap(queue.enqueue1(_) *> IO.delay(println("enqueued")))
            )
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
              _ <- webSocket.connectHighLevel(req).use { conn =>
                conn.send(WSFrame.Text("hey blaze"))
              }
              _ <- expect(WebSocketFrame.Text("hey blaze"))
              _ <- expect(WebSocketFrame.Close(1000, "").fold(throw _, identity))
              _ <- webSocket.connect(req).use { conn =>
                conn.send(WSFrame.Text("hi blaze"))
              }
              _ <- expect(WebSocketFrame.Text("hi blaze"))
              _ <- expect(WebSocketFrame.Close(1000, "").fold(throw _, identity))
            } yield ok
          }
      }
    }
  }

}
