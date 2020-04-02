package org.http4s.client.jdkhttpclient

import java.util.concurrent.Executors

import cats.effect._
import cats.effect.testing.specs2.CatsEffect
import cats.implicits._
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
