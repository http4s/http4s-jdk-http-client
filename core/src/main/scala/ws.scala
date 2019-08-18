package org.http4s
package blazecore
package websocket

import cats.effect._
import cats.effect.concurrent.Deferred
import cats.implicits._
import fs2._
import fs2.concurrent.SignallingRef
import java.util.concurrent.atomic.AtomicBoolean
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage, TrunkBuilder}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.Execution.{directec, trampoline}
import org.http4s.internal.unsafeRunAsync
import org.http4s.websocket.{WebSocket, WebSocketFrame}
import org.http4s.websocket.WebSocketFrame._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

private[http4s] class Http4sWSStage[F[_]](
    ws: WebSocket[F],
    _sentClose: AtomicBoolean,
    _deadSignal: SignallingRef[F, Boolean]
)(implicit F: ConcurrentEffect[F], val ec: ExecutionContext)
    extends TailStage[WebSocketFrame] {

  val _ = (_sentClose, _deadSignal)

  def name: String = "Http4s WebSocket Stage"

  private val pendingClose = Deferred.unsafe[F, Close]

  //////////////////////// Source and Sink generators ////////////////////////
  def snk: Pipe[F, WebSocketFrame, Unit] = {
    def go(s: Stream[F, WebSocketFrame]): Pull[F, Unit, Unit] =
      s.pull.uncons1.flatMap {
        case Some((close: Close, _)) =>
          Pull.eval(pendingClose.complete(close)).attempt >> Pull.done
        case Some((frame, tail)) =>
          Pull.eval(writeFrame(frame, directec)) >> go(tail)
        case None =>
          Pull.done
      }
    go(_).stream
  }

  private[this] def writeFrame(frame: WebSocketFrame, ec: ExecutionContext): F[Unit] =
    F.async[Unit] { cb =>
      channelWrite(frame).onComplete {
        case Success(res) => cb(Right(res))
        case Failure(t) => cb(Left(t))
      }(ec)
    }

  private[this] def readFrameTrampoline: F[WebSocketFrame] = F.async[WebSocketFrame] { cb =>
    channelRead().onComplete {
      case Success(ws) => cb(Right(ws))
      case Failure(exception) => cb(Left(exception))
    }(trampoline)
  }

  /** Read from our websocket.
    *
    * To stay faithful to the RFC, the following must hold:
    *
    * - If we receive a ping frame, we MUST reply with a pong frame
    * - If we receive a pong frame, we don't need to forward it.
    * - If we receive a close frame, it means either one of two things:
    *   - We sent a close frame prior, meaning we do not need to reply with one. Just end the stream
    *   - We are the first to receive a close frame, so we try to atomically check a boolean flag,
    *     to prevent sending two close frames. Regardless, we set the signal for termination of
    *     the stream afterwards
    *
    * @return A websocket frame, or a possible IO error.
    */
  private[this] def handleRead(): F[WebSocketFrame] =
    readFrameTrampoline.flatMap {
      case close: Close =>
        pendingClose.complete(close).attempt.as(close)
      case Ping(d) =>
        //Reply to ping frame immediately
        writeFrame(Pong(d), trampoline) >> handleRead()
      case _: Pong =>
        //Don't forward pong frame
        handleRead()
      case rest =>
        F.pure(rest)
    }

  /** The websocket input stream
    *
    * Note: On receiving a close, we MUST send a close back, as stated in section
    * 5.5.1 of the websocket spec: https://tools.ietf.org/html/rfc6455#section-5.5.1
    *
    * @return
    */
  def inputstream: Stream[F, WebSocketFrame] =
    Stream.repeatEval(handleRead())

  //////////////////////// Startup and Shutdown ////////////////////////

  override protected def stageStartup(): Unit = {
    super.stageStartup()

    val wsStream = inputstream
      .through(ws.receive)
      .onFinalize(F.delay(println("Running onClose")))
      .onFinalize(ws.onClose.attempt.void)
      .onFinalize(pendingClose.complete(Close(1000, "").fold(throw _, identity)).attempt.void)
      .onFinalize(pendingClose.get.flatMap(writeFrame(_, directec)))
      .compile
      .drain

    unsafeRunAsync(wsStream) {
      case Left(EOF) =>
        IO(stageShutdown())
      case Left(t) =>
        IO(logger.error(t)("Error closing Web Socket"))
      case Right(_) =>
        // Nothing to do here
        IO.unit
    }
  }
}

object Http4sWSStage {
  def bufferingSegment[F[_]](stage: Http4sWSStage[F]): LeafBuilder[WebSocketFrame] =
    TrunkBuilder(new SerializingStage[WebSocketFrame]).cap(stage)
}
