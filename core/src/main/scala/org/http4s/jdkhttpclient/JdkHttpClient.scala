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
import cats.effect.syntax.all._
import cats.implicits._
import fs2.Chunk
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.interop.reactivestreams._
import org.http4s.Entity
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpVersion
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.client.Client
import org.reactivestreams.FlowAdapters
import org.typelevel.ci.CIString

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.ByteBuffer
import java.util
import java.util.concurrent.Flow
import scala.jdk.CollectionConverters._

object JdkHttpClient {

  /** Creates a `Client` from an `HttpClient`.
    *
    * @param jdkHttpClient
    *   The `HttpClient`.
    * @param ignoredHeaders
    *   A set of ignored request headers. Some headers (like Content-Length) are "restricted" and
    *   cannot be set by the user. By default, the set of restricted headers of the OpenJDK 11 is
    *   used.
    */
  def apply[F[_]](
      jdkHttpClient: HttpClient,
      ignoredHeaders: Set[CIString] = restrictedHeaders
  )(implicit F: Async[F]): Client[F] = {
    def convertRequest(req: Request[F]): Resource[F, HttpRequest] = for {
      version <- Resource.eval(convertHttpVersionFromHttp4s[F](req.httpVersion))
      bodyPublisher <- req.entity match {
        case Entity.Empty => Resource.pure[F, BodyPublisher](BodyPublishers.noBody())
        case Entity.Strict(bytes) =>
          Resource.pure[F, BodyPublisher](BodyPublishers.ofInputStream(() => bytes.toInputStream))
        case Entity.Default(body, _) =>
          StreamUnicastPublisher(body.chunks.map(_.toByteBuffer))
            .map(FlowAdapters.toFlowPublisher(_))
            .map { publisher =>
              if (req.isChunked)
                BodyPublishers.fromPublisher(publisher)
              else
                req.contentLength match {
                  case Some(length) if length > 0L =>
                    BodyPublishers.fromPublisher(publisher, length)
                  case _ => BodyPublishers.noBody
                }
            }
      }
      rb = HttpRequest.newBuilder
        .method(req.method.name, bodyPublisher)
        .uri(URI.create(req.uri.renderString))
        .version(version)
      headers = req.headers.headers.iterator
        .filterNot(h => ignoredHeaders.contains(h.name))
        .flatMap(h => Iterator(h.name.toString, h.value))
        .toArray
    } yield (if (headers.isEmpty) rb else rb.headers(headers: _*)).build

    // Convert the JDK HttpResponse into a http4s Response value.
    //
    // Aside form converting between the JDK types and the http4s types, this
    // function also ensures that the body of the response is properly
    // handled.
    //
    // From the JDK docs for HttpResponse#BodyHandlers.ofPublisher,
    // https://docs.oracle.com/en/java/javase/15/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html#ofPublisher()
    //
    // > When the HttpResponse object is returned, the response headers will
    // > have been completely read, but the body may not have been fully
    // > received yet. The HttpResponse.body() method returns a
    // > Publisher<List<ByteBuffer>> from which the body response bytes can be
    // > obtained as they are received. The publisher can and must be subscribed
    // > to only once.
    //
    // Of particular note is the final sentence, "The publisher can and must
    // be subscribed to only once.".
    //
    // This poses a bit of a problem for us in the cases where the body is not
    // inspected, e.g. org.http4s.client.Client#status or any function which
    // doesn't inspect the response body in
    // org.http4s.client.Client#run. Functions such as these will never
    // attempt to pull from the fs2.Stream, and it will just silently leave
    // scope and in doing so never subscribe to the JDK HttpResponse body
    // Publisher.  This is because functions provided by fs2 for converting a
    // reactive streams publisher into a fs2.Stream explicitly and
    // intentionally _do not_ subscribe to the publisher until the first
    // attempt to pull from the Stream.
    //
    // https://github.com/typelevel/fs2/blob/v2.5.0/reactive-streams/src/main/scala/fs2/interop/reactivestreams/StreamSubscriber.scala#L64
    //
    // In the general case, this is fine and probably even ideal. After all if
    // you are never going to pull from a Stream, why do all the setup work?
    //
    // Unfortunately, the reactive streams semantics for the JDK client are
    // not the "general case". In order to not leak resources, there _must_ be
    // _exactly one_ subscription to the body publisher _and_ it must either
    // be read until it is exhausted or `.cancel` _must_ be invoked.
    //
    // Making matters more complicated, fs2's implementation does not provide
    // any way to directly invoke this type of operation, e.g. subscribe and
    // then immediately cancel.
    //
    // Thus, in order to solve this problem and satisfy the JDK HttpResponse's
    // API so as to not leak resources, we do the following.
    //
    // We create a Deferred[F, Unit] and bracket its creation as well as
    // the invocation of the effect which yields the JDK HttpResponse. Using
    // the lower level fs2 reactive streams APIs, we ensure the attempt to
    // subscribe to the Publisher first completes the Deferred. The
    // code for this is similar to the body of the fromPublisher method in fs2.
    //
    // https://github.com/typelevel/fs2/blob/v2.5.0/reactive-streams/src/main/scala/fs2/interop/reactivestreams/package.scala#L55
    //
    // In the release section of bracket on the response, we check if the
    // Deferred has been completed. If that is not the case that means
    // that Publisher was never subscribed to, either due to an error or more
    // likely because the calling code didn't care about the body of the
    // request. In this case we subscribe to the body and then immediately
    // cancel the subscription, freeing the resources.  If the Deferred
    // has already been completed, we do nothing.
    //
    // There are a couple items worth giving special attention to here.
    //
    // * It is important that we attach the finalizer which can run
    // AlwaysCancelingSubscriber as soon as we run the effect to trigger the
    // response. We _could_ attach it later, e.g. when we attach the body
    // interruption signal, but this happens after a number of other side
    // effects are run. If anything triggers abnormal termination _after_ we
    // have the HttpResponse, but _before_ we've attached this finalizer, then
    // we will have a resource leak.
    //
    // * Interrupting the response body stream alone will not trigger
    // subscription and cancellation. Subscription only happens after someone
    // attempts to pull from the Stream, which doesn't happen in cases where
    // the body is discarded. Interrupting the fs2.Stream with a second
    // Resource is still required to cleanup the fs2.Stream scopes, whether or
    // not the Publisher was ever subscribed to.
    def convertResponse(
        responseF: F[HttpResponse[Flow.Publisher[util.List[ByteBuffer]]]]
    ): Resource[F, Response[F]] =
      Resource
        .make(
          (Deferred[F, Unit], responseF).tupled
        ) { case (subscription, response) =>
          subscription.tryGet.flatMap {
            case None =>
              // Indicates response was never subscribed to. In this case, in
              // order to conform to the API contract from the
              // HttpResponse.BodyHandlers.ofPublisher, we must subscribe to
              // the body and then immediately cancel the subscription (or
              // read the entire body). If we do not do this we will have a
              // resource leak.
              //
              // This is actually a pretty common case. Any HTTP response for
              // which the caller doesn't care about the body,
              // e.g. Client#status, will trigger this case.
              F.delay(
                response.body.subscribe(new AlwaysCancelingSubscriber)
              )
            case _ =>
              F.unit
          }.uncancelable
        }
        .flatMap { case (subscription, res) =>
          val body: Stream[F, util.List[ByteBuffer]] =
            Stream
              .eval(StreamSubscriber[F, util.List[ByteBuffer]](1))
              .flatMap(s =>
                s.sub.stream(
                  // Complete the TrybleDeferred so that we indicate we have
                  // subscribed to the Publisher.
                  //
                  // This only happens _after_ someone attempts to pull from the
                  // body and will never happen if the body is never pulled
                  // from. In that case, the AlwaysCancelingSubscriber handles
                  // cleanup.
                  F.uncancelable { _ =>
                    subscription.complete(()) *>
                      F.delay(FlowAdapters.toPublisher(res.body).subscribe(s))
                  }
                )
              )
          Resource(
            (F.fromEither(Status.fromInt(res.statusCode)), SignallingRef[F, Boolean](false)).mapN {
              case (status, signal) =>
                Response(
                  status = status,
                  headers = Headers(res.headers.map.asScala.flatMap { case (k, vs) =>
                    vs.asScala.map(Header.Raw(CIString(k), _))
                  }.toList),
                  httpVersion = res.version match {
                    case HttpClient.Version.HTTP_1_1 => HttpVersion.`HTTP/1.1`
                    case HttpClient.Version.HTTP_2 => HttpVersion.`HTTP/2`
                  },
                  entity = Entity(
                    body
                      .interruptWhen(signal)
                      .flatMap(bs =>
                        Stream.fromIterator(bs.iterator.asScala.map(Chunk.byteBuffer), 1)
                      )
                      .flatMap(Stream.chunk)
                  )
                ) -> signal.set(true)
            }
          )
        }

    Client[F] { req =>
      for {
        req <- convertRequest(req)
        res = F.fromCompletableFuture(
          F.delay(jdkHttpClient.sendAsync(req, BodyHandlers.ofPublisher))
        )
        res <- convertResponse(res)
      } yield res
    }
  }

  /** A `Client` wrapping the default `HttpClient`.
    */
  def simple[F[_]](implicit F: Async[F]): F[Client[F]] =
    defaultHttpClient[F].map(apply(_))

  private[jdkhttpclient] def defaultHttpClient[F[_]](implicit F: Async[F]): F[HttpClient] =
    F.executor.flatMap { exec =>
      F.delay {
        val builder = HttpClient.newBuilder()
        // workaround for https://github.com/http4s/http4s-jdk-http-client/issues/200
        if (Runtime.version().feature() == 11) {
          val params = javax.net.ssl.SSLContext.getDefault().getDefaultSSLParameters()
          params.setProtocols(params.getProtocols().filter(_ != "TLSv1.3"))
          builder.sslParameters(params)
        }

        builder.executor(exec)

        builder.build()
      }
    }

  def convertHttpVersionFromHttp4s[F[_]](
      version: HttpVersion
  )(implicit F: ApplicativeThrow[F]): F[HttpClient.Version] =
    version match {
      case HttpVersion.`HTTP/1.1` => HttpClient.Version.HTTP_1_1.pure[F]
      case HttpVersion.`HTTP/2` => HttpClient.Version.HTTP_2.pure[F]
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
    ).map(CIString(_))
}
