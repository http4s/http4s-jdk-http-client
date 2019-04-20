package org.http4s.client.jdkhttpclient

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.util
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, Flow}
import java.util.function.BiFunction

import cats.ApplicativeError
import cats.effect._
import cats.implicits._
import fs2.interop.reactivestreams._
import fs2.{Chunk, Stream}
import org.http4s.client.Client
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, Headers, HttpVersion, Request, Response, Status}
import org.reactivestreams.FlowAdapters

import scala.collection.JavaConverters._

object JdkHttpClient {

  /**
   * Creates a [[Client]] from an [[HttpClient]]. Note that the creation of an [[HttpClient]] is a
   * side effect.
   *
   * @param jdkHttpClient The [[HttpClient]].
   * @param ignoredHeaders A set of ignored request headers. Some headers (like Content-Length) are
   *                       "restricted" and cannot be set by the user. By default, the set of
   *                       restricted headers of the OpenJDK 11 is used.
   */
  def apply[F[_]](
      jdkHttpClient: HttpClient,
      ignoredHeaders: Set[CaseInsensitiveString] = restrictedHeaders
  )(implicit F: ConcurrentEffect[F]): Client[F] = {

    def convertRequest(req: Request[F]): F[HttpRequest] =
      convertHttpVersionFromHttp4s[F](req.httpVersion).map { version =>
        val rb = HttpRequest.newBuilder
          .method(
            req.method.name, {
              val publisher = FlowAdapters.toFlowPublisher(
                StreamUnicastPublisher(req.body.chunks.map(_.toByteBuffer)))
              if (req.isChunked)
                BodyPublishers.fromPublisher(publisher)
              else
                req.contentLength.fold(BodyPublishers.noBody)(
                  BodyPublishers.fromPublisher(publisher, _))
            }
          )
          .uri(URI.create(req.uri.renderString))
          .version(version)
        val headers = req.headers.iterator
          .filterNot(h => ignoredHeaders.contains(h.name))
          .flatMap(h => Iterator(h.name.value, h.value))
          .toArray
        (if (headers.isEmpty) rb else rb.headers(headers: _*)).build
      }

    def convertResponse(res: HttpResponse[Flow.Publisher[util.List[ByteBuffer]]]): F[Response[F]] =
      F.fromEither(Status.fromInt(res.statusCode)).map { status =>
        Response(
          status = status,
          headers = Headers(res.headers.map.asScala.flatMap {
            case (k, vs) => vs.asScala.map(Header(k, _))
          }.toList),
          httpVersion = res.version match {
            case HttpClient.Version.HTTP_1_1 => HttpVersion.`HTTP/1.1`
            case HttpClient.Version.HTTP_2 => HttpVersion.`HTTP/2.0`
          },
          body = FlowAdapters
            .toPublisher(res.body)
            .toStream[F]
            .flatMap(bs =>
              Stream.fromIterator(bs.asScala.map(Chunk.byteBuffer).iterator).flatMap(Stream.chunk))
        )
      }

    Client[F] { req =>
      Resource.liftF(
        convertRequest(req)
          .flatMap(r =>
            fromCompletableFuture(F.delay(jdkHttpClient.sendAsync(r, BodyHandlers.ofPublisher))))
          .flatMap(convertResponse))
    }
  }

  /**
   * A [[Client]] wrapping the default [[HttpClient]].
   */
  def simple[F[_]](implicit F: ConcurrentEffect[F]): F[Client[F]] =
    F.delay(HttpClient.newHttpClient()).map(apply(_))

  def convertHttpVersionFromHttp4s[F[_]](version: HttpVersion)(
      implicit F: ApplicativeError[F, Throwable]): F[HttpClient.Version] =
    version match {
      case HttpVersion.`HTTP/1.1` => HttpClient.Version.HTTP_1_1.pure[F]
      case HttpVersion.`HTTP/2.0` => HttpClient.Version.HTTP_2.pure[F]
      case _ => F.raiseError(new IllegalArgumentException("invalid HTTP version"))
    }

  // see jdk.internal.net.http.common.Utils#DISALLOWED_HEADERS_SET
  private val restrictedHeaders =
    Set(
      "connection",
      "content-length",
      "date",
      "expect",
      "from",
      "host",
      "upgrade",
      "via",
      "warning"
    ).map(CaseInsensitiveString(_))

  // TODO remove when added to http4s-core
  private[http4s] def fromCompletableFuture[F[_], A](fcf: F[CompletableFuture[A]])(
      implicit F: Concurrent[F]): F[A] =
    fcf.flatMap { cf =>
      F.cancelable(cb => {
        cf.handle[Unit](new BiFunction[A, Throwable, Unit] {
          override def apply(result: A, err: Throwable): Unit = err match {
            case null => cb(Right(result))
            case _: CancellationException => ()
            case ex: CompletionException if ex.getCause ne null => cb(Left(ex.getCause))
            case ex => cb(Left(ex))
          }
        })
        F.delay { cf.cancel(true); () }
      })
    }

}
