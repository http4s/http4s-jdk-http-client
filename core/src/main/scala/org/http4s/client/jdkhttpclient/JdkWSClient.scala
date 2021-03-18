/*
 * Copyright 2021 http4s.org
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

package org.http4s.client.jdkhttpclient

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, WebSocket => JWebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage}

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.effect.util.CompositeException
import cats.implicits._
import fs2.Chunk
import fs2.concurrent.Queue
import scodec.bits.ByteVector
import org.http4s.Header
import org.http4s.internal.unsafeToCompletionStage
import org.typelevel.ci._

/** A `WSClient` wrapper for the JDK 11+ websocket client.
  * It will reply to Pongs with Pings even in "low-level" mode.
  * Custom (non-GET) HTTP methods are ignored.
  */
object JdkWSClient {

  /** Create a new `WSClient` backed by a JDK 11+ http client. */
  def apply[F[_]](
      jdkHttpClient: HttpClient
  )(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): WSClient[F] =
    WSClient.defaultImpl(respondToPings = false) { case WSRequest(uri, headers, _) =>
      Resource
        .make {
          for {
            wsBuilder <- F.delay {
              val builder = jdkHttpClient.newWebSocketBuilder()
              val (subprotocols, hs) = headers.headers.partitionEither {
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
            queue <- Queue.noneTerminated[F, Either[Throwable, WSFrame]]
            handleReceive =
              (wsf: Either[Throwable, WSFrame]) =>
                unsafeToCompletionStage(for {
                  _ <- queue.enqueue1(wsf.some)
                  // if we encounter an error or receive a Close frame, we close the queue
                  _ <- wsf match {
                    case Left(_) | Right(_: WSFrame.Close) => queue.enqueue1(none)
                    case _ => F.unit
                  }
                } yield ())
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
            webSocket <- fromCompletableFutureShift(
              F.delay(wsBuilder.buildAsync(URI.create(uri.renderString), wsListener))
            )
            sendSem <- Semaphore[F](1L)
          } yield (webSocket, queue, sendSem)
        } { case (webSocket, queue, _) =>
          for {
            isOutputOpen <- F.delay(!webSocket.isOutputClosed)
            closeOutput = fromCompletableFutureShift(
              F.delay(webSocket.sendClose(JWebSocket.NORMAL_CLOSURE, ""))
            )
            _ <-
              closeOutput
                .whenA(isOutputOpen)
                .recover { case e: IOException if e.getMessage == "closed output" => () }
                .onError { case e: IOException =>
                  for {
                    chunk <- queue.tryDequeueChunk1(10)
                    errs = Chunk(chunk.flatten.toSeq: _*).flatten.collect { case Left(e) =>
                      e
                    }
                    _ <- F.raiseError[Unit](NonEmptyList.fromFoldable(errs) match {
                      case Some(nel) => new CompositeException(e, nel)
                      case None => e
                    })
                  } yield ()
                }
          } yield ()
        }
        .map { case (webSocket, queue, sendSem) =>
          // sending will throw if done in parallel
          val rawSend = (wsf: WSFrame) =>
            fromCompletableFutureShift(F.delay(wsf match {
              case WSFrame.Text(text, last) => webSocket.sendText(text, last)
              case WSFrame.Binary(data, last) => webSocket.sendBinary(data.toByteBuffer, last)
              case WSFrame.Ping(data) => webSocket.sendPing(data.toByteBuffer)
              case WSFrame.Pong(data) => webSocket.sendPong(data.toByteBuffer)
              case WSFrame.Close(statusCode, reason) => webSocket.sendClose(statusCode, reason)
            })).void
          new WSConnection[F] {
            override def send(wsf: WSFrame) =
              sendSem.withPermit(rawSend(wsf))
            override def sendMany[G[_]: Foldable, A <: WSFrame](wsfs: G[A]) =
              sendSem.withPermit(wsfs.traverse_(rawSend))
            override def receive =
              F.delay(webSocket.request(1)) *> queue.dequeue1.map(_.sequence).rethrow
            override def subprotocol =
              webSocket.getSubprotocol.some.filter(_.nonEmpty)
          }
        }
    }

  /** A `WSClient` wrapping the default `HttpClient`. */
  def simple[F[_]](implicit F: ConcurrentEffect[F], CS: ContextShift[F]): F[WSClient[F]] =
    JdkHttpClient.defaultHttpClient[F].map(apply(_))
}
