package org.http4s.client.jdkhttpclient

import cats._
import cats.data.{Chain, OptionT}
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}
import cats.implicits._
import fs2.{Pipe, Stream}
import org.http4s.{Headers, Method, Uri}
import scodec.bits.ByteVector

/**
  * A websocket request.
  *
  * @param uri The URI.
  * @param headers The headers to send. Put your `Sec-Websocket-Protocol` headers here if needed.
  *                Some websocket clients reject other WS-specific headers.
  * @param method The method of the intial HTTP request. Ignored by some clients.
  */
case class WSRequest(
    uri: Uri,
    headers: Headers = Headers.empty,
    method: Method = Method.GET
)

sealed trait WSFrame extends Product with Serializable

sealed trait WSControlFrame extends WSFrame

sealed trait WSDataFrame extends WSFrame

object WSFrame {
  final case class Close(statusCode: Int, reason: String) extends WSControlFrame
  final case class Ping(data: ByteVector) extends WSControlFrame
  final case class Pong(data: ByteVector) extends WSControlFrame
  final case class Text(data: String, last: Boolean = true) extends WSDataFrame
  final case class Binary(data: ByteVector, last: Boolean = true) extends WSDataFrame
}

trait WSConnection[F[_]] {
  /** Send a single websocket frame. The sending side of this connection has to be open. */
  def send(wsf: WSFrame): F[Unit]

  /** Send multiple websocket frames. Equivalent to multiple `send` calls, but at least as fast. */
  def sendMany[G[_]: Foldable, A <: WSFrame](wsfs: G[A]): F[Unit]

  /** A `Pipe` which sends websocket frames and emits a `()` for each chunk sent. */
  final def sendPipe: Pipe[F, WSFrame, Unit] = _.chunks.evalMap(sendMany(_))

  /** Wait for a single websocket frame to be received. Returns `None` if the receiving side is closed. */
  def receive: F[Option[WSFrame]]

  /** A stream of the incoming websocket frames. */
  final def receiveStream: Stream[F, WSFrame] = Stream.repeatEval(receive).unNoneTerminate

  /** The negotiated subprotocol, if any. */
  def subprotocol: Option[String]
}

trait WSConnectionHighLevel[F[_]] {
  /** Send a single websocket frame. The sending side of this connection has to be open. */
  def send(wsf: WSDataFrame): F[Unit]

  /** Send multiple websocket frames. Equivalent to multiple `send` calls, but at least as fast. */
  def sendMany[G[_]: Foldable, A <: WSDataFrame](wsfs: G[A]): F[Unit]

  /** A `Pipe` which sends websocket frames and emits a `()` for each chunk sent. */
  final def sendPipe: Pipe[F, WSDataFrame, Unit] = _.chunks.evalMap(sendMany(_))

  /** Send a Ping frame. */
  def sendPing(data: ByteVector = ByteVector.empty): F[Unit]

  /** Send a Close frame. The sending side of this connection will be closed. */
  def sendClose(reason: String = ""): F[Unit]

  /** Wait for a websocket frame to be received. Returns `None` if the receiving side is closed.
    * Fragmentation is handled automatically, the `last` attribute can be ignored.
    */
  def receive: F[Option[WSDataFrame]]

  /** A stream of the incoming websocket frames. */
  final def receiveStream: Stream[F, WSDataFrame] = Stream.repeatEval(receive).unNoneTerminate

  /** The negotiated subprotocol, if any. */
  def subprocotol: Option[String]

  /** The close frame, if available. */
  def closeFrame: TryableDeferred[F, WSFrame.Close]
}

trait WSClient[F[_]] {
  /** Establish a websocket connection. It will be closed automatically if necessary. */
  def connect(request: WSRequest): Resource[F, WSConnection[F]]

  /** Establish a "high level" websocket connection. You only get to handle Text and Binary frames.
    * Pongs will be replied automatically. Received frames are grouped by the `last` attribute. The
    * connection will be closed automatically.
    */
  def connectHighLevel(request: WSRequest): Resource[F, WSConnectionHighLevel[F]]
}

object WSClient {
  def defaultImpl[F[_]](
      respondToPings: Boolean
  )(f: WSRequest => Resource[F, WSConnection[F]])(implicit F: Concurrent[F]): WSClient[F] =
    new WSClient[F] {
      override def connect(request: WSRequest) = f(request)
      override def connectHighLevel(request: WSRequest) =
        for {
          (recvCloseFrame, outputOpen) <- Resource.liftF(
            Deferred.tryable[F, WSFrame.Close].product(Ref[F].of(false))
          )
          conn <- f(request)
        } yield new WSConnectionHighLevel[F] {
          override def send(wsf: WSDataFrame) = conn.send(wsf)
          override def sendMany[G[_]: Foldable, A <: WSDataFrame](wsfs: G[A]): F[Unit] =
            conn.sendMany(wsfs)
          override def sendPing(data: ByteVector) = conn.send(WSFrame.Ping(data))
          override def sendClose(reason: String) =
            conn.send(WSFrame.Close(1000, reason)) *> outputOpen.set(false)
          override def receive: F[Option[WSDataFrame]] = {
            def receiveDataFrame: OptionT[F, WSDataFrame] = OptionT(conn.receive).flatMap { wsf =>
              OptionT.liftF(wsf match {
                case WSFrame.Ping(data) if respondToPings => conn.send(WSFrame.Pong(data))
                case wsf: WSFrame.Close =>
                  recvCloseFrame.complete(wsf) *> outputOpen.get.flatMap(conn.send(wsf).whenA(_))
                case _ => F.unit
              }) >> (wsf match {
                case wsdf: WSDataFrame => OptionT.pure[F](wsdf)
                case _ => receiveDataFrame
              })
            }
            def defrag(text: Chain[String], binary: ByteVector): OptionT[F, WSDataFrame] =
              receiveDataFrame.flatMap {
                case WSFrame.Text(t, finalFrame) =>
                  val nextText = text :+ t
                  if (finalFrame) {
                    val sb = new StringBuilder(nextText.foldMap(_.length))
                    nextText.iterator.foreach(sb ++= _)
                    OptionT.pure[F](WSFrame.Text(sb.mkString))
                  } else
                    defrag(nextText, binary)
                case WSFrame.Binary(b, finalFrame) =>
                  val nextBinary = binary ++ b
                  if (finalFrame)
                    OptionT.pure[F](WSFrame.Binary(nextBinary))
                  else
                    defrag(text, nextBinary)
              }
            defrag(Chain.empty, ByteVector.empty).value
          }
          override def subprocotol: Option[String] = conn.subprotocol
          override def closeFrame: TryableDeferred[F, WSFrame.Close] = recvCloseFrame
        }
    }
}
