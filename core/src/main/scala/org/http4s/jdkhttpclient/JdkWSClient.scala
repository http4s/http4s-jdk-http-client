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

import cats._
import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.implicits._
import fs2.CompositeFailure
import fs2.Stream
import org.http4s.Header
import org.http4s.client.websocket._
import org.typelevel.ci._
import scodec.bits.ByteVector

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.{WebSocket => JWebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** A `WSClient` wrapper for the JDK 11+ websocket client. It will reply to Pongs with Pings even in
  * "low-level" mode. Custom (non-GET) HTTP methods are ignored.
  */
object JdkWSClient {

  /** Create a new `WSClient` backed by a JDK 11+ http client. */
  def apply[F[_]](
      jdkHttpClient: HttpClient
  )(implicit F: Async[F]): WSClient[F] =
    WSClient(respondToPings = false) { req =>
      Dispatcher.sequential.flatMap { dispatcher =>
        Resource
          .makeFull { (poll: Poll[F]) =>
            for {
              wsBuilder <- F.delay {
                val builder = jdkHttpClient.newWebSocketBuilder()
                val (subprotocols, hs) = req.headers.headers.partitionEither {
                  case Header.Raw(ci"Sec-WebSocket-Protocol", p) => Left(p)
                  case h => Right(h)
                }
                hs.foreach { h => builder.header(h.name.toString, h.value); () }
                subprotocols match {
                  case head :: tail => builder.subprotocols(head, tail: _*)
                  case Nil =>
                }
                builder
              }
              queue <- Queue.unbounded[F, Either[Throwable, WSFrame]]
              closedDef <- Deferred[F, Unit]
              handleReceive =
                (wsf: Either[Throwable, WSFrame]) =>
                  dispatcher.unsafeToCompletableFuture(
                    queue.offer(wsf) *> (wsf match {
                      case Left(_) | Right(_: WSFrame.Close) => closedDef.complete(()).void
                      case _ => F.unit
                    })
                  )
              wsListener = new JWebSocket.Listener {
                override def onOpen(webSocket: JWebSocket): Unit = ()
                override def onClose(webSocket: JWebSocket, statusCode: Int, reason: String)
                    : CompletionStage[_] =
                  // The output side of this connection will be closed when the returned CompletionStage completes.
                  // Therefore, we return a never completing CompletionStage, so we can control when the output will
                  // be closed (as it is allowed to continue sending frames (as few as possible) after a close frame
                  // has been received).
                  handleReceive(WSFrame.Close(statusCode, reason).asRight)
                    .thenCompose[Nothing](_ => new CompletableFuture[Nothing])
                override def onText(webSocket: JWebSocket, data: CharSequence, last: Boolean)
                    : CompletionStage[_] =
                  handleReceive(WSFrame.Text(data.toString, last).asRight)
                override def onBinary(webSocket: JWebSocket, data: ByteBuffer, last: Boolean)
                    : CompletionStage[_] =
                  handleReceive(WSFrame.Binary(ByteVector(data), last).asRight)
                override def onPing(
                    webSocket: JWebSocket,
                    message: ByteBuffer
                ): CompletionStage[_] =
                  handleReceive(WSFrame.Ping(ByteVector(message)).asRight)
                override def onPong(
                    webSocket: JWebSocket,
                    message: ByteBuffer
                ): CompletionStage[_] =
                  handleReceive(WSFrame.Pong(ByteVector(message)).asRight)
                override def onError(webSocket: JWebSocket, error: Throwable): Unit = {
                  handleReceive(error.asLeft); ()
                }
              }
              webSocket <- poll(
                F.fromCompletableFuture(
                  F.delay(wsBuilder.buildAsync(URI.create(req.uri.renderString), wsListener))
                )
              )
              sendSem <- Semaphore[F](1L)
            } yield (webSocket, queue, closedDef, sendSem)
          } { case (webSocket, queue, _, _) =>
            for {
              isOutputOpen <- F.delay(!webSocket.isOutputClosed)
              closeOutput = F.fromCompletableFuture(
                F.delay(webSocket.sendClose(JWebSocket.NORMAL_CLOSURE, ""))
              )
              _ <-
                closeOutput
                  .whenA(isOutputOpen)
                  .recover { case e: IOException if e.getMessage == "closed output" => () }
                  .onError { case e: IOException =>
                    for {
                      errs <- Stream
                        .repeatEval(queue.tryTake)
                        .unNoneTerminate
                        .collect { case Left(t) => t }
                        .compile
                        .toList
                      _ <- F.raiseError[Unit](CompositeFailure.fromList(errs) match {
                        case Some(cf) => cf
                        case None => e
                      })
                    } yield ()
                  }
              // If the input side is still open (no close received from server), the JDK will not clean up the connection.
              // This also implies the client can't be shutdown on Java 21+ as it waits for all open connections
              // to be be closed. As we don't expect/handle anything coming on the input anymore
              // at this point, we can safely abort.
              _ <- F.delay(webSocket.abort())
            } yield ()
          }
          .map { case (webSocket, queue, closedDef, sendSem) =>
            // sending will throw if done in parallel
            val rawSend = (wsf: WSFrame) =>
              F.fromCompletableFuture(
                F.delay(wsf match {
                  case WSFrame.Text(text, last) => webSocket.sendText(text, last)
                  case WSFrame.Binary(data, last) => webSocket.sendBinary(data.toByteBuffer, last)
                  case WSFrame.Ping(data) => webSocket.sendPing(data.toByteBuffer)
                  case WSFrame.Pong(data) => webSocket.sendPong(data.toByteBuffer)
                  case WSFrame.Close(statusCode, reason) => webSocket.sendClose(statusCode, reason)
                })
              ).void
            new WSConnection[F] {
              override def send(wsf: WSFrame) =
                sendSem.permit.use(_ => rawSend(wsf))
              override def sendMany[G[_]: Foldable, A <: WSFrame](wsfs: G[A]) =
                sendSem.permit.use(_ => wsfs.traverse_(rawSend))
              override def receive = closedDef.tryGet.flatMap {
                case None => F.delay(webSocket.request(1)) *> queue.take.rethrow.map(_.some)
                case Some(()) => none[WSFrame].pure[F]
              }
              override def subprotocol =
                webSocket.getSubprotocol.some.filter(_.nonEmpty)
            }
          }
      }
    }

  /** A `WSClient` wrapping the default `HttpClient`, which shares the current
    * [[cats.effect.kernel.Async.executor executor]], sets the
    * [[org.http4s.client.defaults.ConnectTimeout default http4s connect timeout]], and disables
    * [[https://github.com/http4s/http4s-jdk-http-client/issues/200 TLS 1.3 on JDK 11]].
    *
    * * On Java 21 and higher, it actively closes the underlying client, releasing its resources
    * early. On earlier Java versions, closing the underlying client is not possible, so the release
    * is a no-op. On these Java versions (and there only), you can safely use
    * [[cats.effect.Resource allocated]] to avoid dealing with resource management.
    */
  def simple[F[_]](implicit F: Async[F]): Resource[F, WSClient[F]] =
    JdkHttpClient.defaultHttpClientResource[F].map(apply(_))
}
