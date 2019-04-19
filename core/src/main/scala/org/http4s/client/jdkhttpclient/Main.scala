package org.http4s.client.jdkhttpclient

import cats.implicits._
import cats.effect._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    IO(println("I am a new project!")).as(ExitCode.Success)
  }

}