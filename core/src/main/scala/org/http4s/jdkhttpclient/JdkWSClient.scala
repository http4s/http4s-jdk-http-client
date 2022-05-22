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

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.{WebSocket => JWebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

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
import org.http4s.internal.unsafeToCompletionStage
import org.typelevel.ci._
import scodec.bits.ByteVector

/** A `WSClient` wrapper for the JDK 11+ websocket client. It will reply to Pongs with Pings even in
  * "low-level" mode. Custom (non-GET) HTTP methods are ignored.
  */
object JdkWSClient {

  /** Create a new `WSClient` backed by a JDK 11+ http client. */
  def apply[F[_]](
      jdkHttpClient: HttpClient
  )(implicit F: Async[F]): Resource[F, WSClient[F]] = Dispatcher[F].map { dispatcher =>
    WSClient(respondToPings = false) { req =>
      Resource
        .make {
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
                unsafeToCompletionStage(
                  queue.offer(wsf) *> (wsf match {
                    case Left(_) | Right(_: WSFrame.Close) => closedDef.complete(()).void
                    case _ => F.unit
                  }),
                  dispatcher
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
              override def onPing(webSocket: JWebSocket, message: ByteBuffer): CompletionStage[_] =
                handleReceive(WSFrame.Ping(ByteVector(message)).asRight)
              override def onPong(webSocket: JWebSocket, message: ByteBuffer): CompletionStage[_] =
                handleReceive(WSFrame.Pong(ByteVector(message)).asRight)
              override def onError(webSocket: JWebSocket, error: Throwable): Unit = {
                handleReceive(error.asLeft); ()
              }
            }
            webSocket <- F.fromCompletableFuture(
              F.delay(wsBuilder.buildAsync(URI.create(req.uri.renderString), wsListener))
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
                      .collect { case Left(e) => e }
                      .compile
                      .toList
                    _ <- F.raiseError[Unit](CompositeFailure.fromList(errs) match {
                      case Some(cf) => cf
                      case None => e
                    })
                  } yield ()
                }
          } yield ()
        }
        .map { case (webSocket, queue, closedDef, sendSem) =>
          // sending will throw if done in parallel
          val rawSend = (wsf: WSFrame) =>
            F.fromCompletableFuture(F.delay(wsf match {
              case WSFrame.Text(text, last) => webSocket.sendText(text, last)
              case WSFrame.Binary(data, last) => webSocket.sendBinary(data.toByteBuffer, last)
              case WSFrame.Ping(data) => webSocket.sendPing(data.toByteBuffer)
              case WSFrame.Pong(data) => webSocket.sendPong(data.toByteBuffer)
              case WSFrame.Close(statusCode, reason) => webSocket.sendClose(statusCode, reason)
            }))
              .void
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

  /** A `WSClient` wrapping the default `HttpClient`. */
  def simple[F[_]](implicit F: Async[F]): Resource[F, WSClient[F]] =
    Resource.eval(JdkHttpClient.defaultHttpClient[F]).flatMap(apply(_))
}
