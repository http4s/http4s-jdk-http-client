package org.http4s.client.jdkhttpclient

import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.syntax.all._
import org.http4s._
import org.http4s.client._
import org.http4s.syntax.all._
import org.http4s.server.blaze.BlazeServerBuilder

// This is a *manual* test for the body leak fixed in #335
// Run e.g. with `bloop run core-test --args -J-Xmx200M`
object BodyLeakExample extends IOApp {

  val app: HttpApp[IO] =
    Kleisli((_: Request[IO]) => IO.pure(Response[IO]().withEntity("Hello, HTTP")))

  def runRequest(client: Client[IO], counter: Ref[IO, Long]): IO[Unit] =
    client.status(
      Request[IO](method = Method.GET, uri = uri"http://127.0.0.1:8080")
    ) *> counter
      .updateAndGet(_ + 1L)
      .flatMap(value =>
        if (value % 1000L === 0L) {
          IO(println(s"Request count: ${value}"))
        } else {
          IO.unit
        }
      )

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](executionContext)
      .bindLocal(8080)
      .withHttpApp(app)
      .resource
      .use { _ =>
        for {
          client <- JdkHttpClient.simple[IO]
          counter <- Ref.of[IO, Long](0L)
          ec <- runRequest(client, counter).foreverM[ExitCode]
        } yield ec
      }

}
