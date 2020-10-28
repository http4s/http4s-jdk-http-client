package org.http4s.client.jdkhttpclient

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.time.temporal.ChronoUnit
import java.time.{Duration => JDuration}
import java.util
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

import cats._
import cats.effect._
import cats.implicits._
import fs2.concurrent.SignallingRef
import fs2.interop.reactivestreams._
import fs2.{Chunk, Stream}
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.compat.CollectionConverters._
import org.http4s.internal.fromCompletionStage
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, Headers, HttpVersion, Request, Response, Status}
import org.reactivestreams.FlowAdapters
import scala.concurrent.duration.Duration

object JdkHttpClient {

  /** Creates a `Client` from a [[JdkHttpClientBuilder]] and also returns the
    * underlying `HttpClient`. This can be useful when you wish to share the
    * `HttpClient` with some other API, e.g. [[JdkWSClient]].
    *
    * @note [[JdkHttpClientBuilder#build]] may be more convenient.
    */
  def fromBuilder_[F[_]](
      builder: JdkHttpClientBuilder
  )(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): F[(HttpClient, Client[F])] = {
    val jBuilder: HttpClient.Builder = {
      val b: HttpClient.Builder = builder.jdkHttpClientBuilder.getOrElse(HttpClient.newBuilder)
      builder.connectTimeout
        .flatMap(scalaDurationToJavaDuration)
        .fold(
          b
        )(connectTimeout => b.connectTimeout(connectTimeout))
    }

    defaultHttpClient(jBuilder.some)
      .map(jdkHttpClient =>
        (
          jdkHttpClient,
          http4sClientFromJdkClient(builder.requestTimeout, builder.ignoredHeaders, jdkHttpClient)
        )
      )
  }

  /** Creates a `Client` from a [[JdkHttpClientBuilder]].
    *
    * @note [[JdkHttpClientBuilder#build]] may be more convenient.
    */
  def fromBuilder[F[_]: ConcurrentEffect: ContextShift](
      builder: JdkHttpClientBuilder
  ): F[Client[F]] =
    fromBuilder_[F](builder).map(_._2)

  /** Creates a `Client` from an `HttpClient`. Note that the creation of an `HttpClient` is a
    * side effect.
    *
    * @param jdkHttpClient The `HttpClient`.
    * @param ignoredHeaders A set of ignored request headers. Some headers (like Content-Length) are
    *                       "restricted" and cannot be set by the user. By default, the set of
    *                       restricted headers of the OpenJDK 11 is used.
    */
  @deprecated(message = "Please use JdkHttpClientBuilder", since = "0.3.2")
  def apply[F[_]](
      jdkHttpClient: HttpClient,
      ignoredHeaders: Set[CaseInsensitiveString] = restrictedHeaders
  )(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): Client[F] =
    http4sClientFromJdkClient(None, ignoredHeaders, jdkHttpClient)

  /** A `Client` wrapping the default `HttpClient`.
    *
    * This is just a wrapper for `JdkHttpClientBuilder.default.build[F]`, thus
    * all the defaults from [[JdkHttpClientBuilder#default]] will apply.
    *
    * @note This returns the underlying `HttpClient` so it can be reused with
    *       other APIs, e.g. [[JdkWSClient]].
    */
  def simple_[F[_]](implicit
      F: ConcurrentEffect[F],
      CS: ContextShift[F]
  ): F[(HttpClient, Client[F])] =
    fromBuilder_[F](JdkHttpClientBuilder.default)

  /** A `Client` wrapping the default `HttpClient`.
    *
    * This is just a wrapper for `JdkHttpClientBuilder.default.build[F]`, thus
    * all the defaults from [[JdkHttpClientBuilder#default]] will apply.
    */
  def simple[F[_]](implicit F: ConcurrentEffect[F], CS: ContextShift[F]): F[Client[F]] =
    fromBuilder[F](JdkHttpClientBuilder.default)

  private[jdkhttpclient] def defaultHttpClient[F[_]](
      builderOpt: Option[HttpClient.Builder]
  )(implicit F: Sync[F]): F[HttpClient] =
    F.delay {
      val builder = builderOpt.getOrElse(HttpClient.newBuilder())
      // workaround for https://github.com/http4s/http4s-jdk-http-client/issues/200
      if (Runtime.version().feature() == 11) {
        val params = javax.net.ssl.SSLContext.getDefault().getDefaultSSLParameters()
        params.setProtocols(params.getProtocols().filter(_ != "TLSv1.3"))
        builder.sslParameters(params)
      }
      builder.build
    }

  def convertHttpVersionFromHttp4s[F[_]](
      version: HttpVersion
  )(implicit F: ApplicativeError[F, Throwable]): F[HttpClient.Version] =
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

  private def http4sClientFromJdkClient[F[_]](
      requestTimeout: Option[Duration],
      ignoredHeaders: Set[CaseInsensitiveString],
      jdkHttpClient: HttpClient
  )(implicit F: ConcurrentEffect[F], CS: ContextShift[F]): Client[F] = {
    val jRequestTimeout: Option[JDuration] =
      requestTimeout.flatMap(scalaDurationToJavaDuration)

    Client[F] { req =>
      for {
        req <- Resource.liftF(convertRequest(req, jRequestTimeout, ignoredHeaders))
        res <- Resource.liftF(
          fromCompletionStage(F.delay(jdkHttpClient.sendAsync(req, BodyHandlers.ofPublisher)))
        )
        res <- convertResponse[F](res)
      } yield res
    }
  }

  private def convertRequest[F[_]: ConcurrentEffect](
      req: Request[F],
      timeout: Option[JDuration],
      ignoredHeaders: Set[CaseInsensitiveString]
  ): F[HttpRequest] =
    convertHttpVersionFromHttp4s[F](req.httpVersion).map { version =>
      val rb =
        HttpRequest.newBuilder
          .method(
            req.method.name, {
              val publisher = FlowAdapters.toFlowPublisher(
                StreamUnicastPublisher(req.body.chunks.map(_.toByteBuffer))
              )
              if (req.isChunked)
                BodyPublishers.fromPublisher(publisher)
              else
                req.contentLength
                  .fold(BodyPublishers.noBody)(BodyPublishers.fromPublisher(publisher, _))
            }
          )
          .uri(URI.create(req.uri.renderString))
          .version(version)
      val headers = req.headers.iterator
        .filterNot(h => ignoredHeaders.contains(h.name))
        .flatMap(h => Iterator(h.name.value, h.value))
        .toArray
      withRequestTimeout(
        (if (headers.isEmpty) rb else rb.headers(headers: _*)),
        timeout
      ).build
    }

  private def convertResponse[F[_]](
      res: HttpResponse[Flow.Publisher[util.List[ByteBuffer]]]
  )(implicit F: ConcurrentEffect[F]): Resource[F, Response[F]] =
    Resource(
      (F.fromEither(Status.fromInt(res.statusCode)), SignallingRef[F, Boolean](false)).mapN {
        case (status, signal) =>
          Response(
            status = status,
            headers = Headers(res.headers.map.asScala.flatMap { case (k, vs) =>
              vs.asScala.map(Header(k, _))
            }.toList),
            httpVersion = res.version match {
              case HttpClient.Version.HTTP_1_1 => HttpVersion.`HTTP/1.1`
              case HttpClient.Version.HTTP_2 => HttpVersion.`HTTP/2.0`
            },
            body = FlowAdapters
              .toPublisher(res.body)
              .toStream[F]
              .interruptWhen(signal)
              .flatMap(bs => Stream.fromIterator(bs.iterator.asScala.map(Chunk.byteBuffer)))
              .flatMap(Stream.chunk)
          ) -> signal.set(true)
      }
    )

  /** Convert a `scala.concurrent.duration.Duration` to a `java.time.Duration`
    * for the purposes of settings a connect or request timeout on a
    * `java.net.http.HttpClient.Builder` or
    * `java.net.http.HttpRequest.Builder`.
    *
    * This function is total, but converting between from a Scala Duration to
    * a Java Duration is not a total function in general. The edge cases are
    * defined below.
    *
    *   - The Scala Duration value of `Duration.Zero` directly maps to the
    *     Java Duration value of `Duration.ZERO`.
    *   - The Scala Duration of `Duration.MinusInf` will be converted to the
    *     smallest Java Duration value,
    *     `Duration.ofSeconds(Long.MinValue)`. Java Duration values do not
    *     have a concept of a infinite duration, so this is as close
    *     to -infinity we can get. It likely doesn't make sense to use this
    *     value, but the code is more simple if we strive to do as much of a
    *     direct conversion as possible.
    *   - When the Scala Duration value is a non-zero finite Duration, it is
    *     converted directly to a Java Duration in a manner very similar to
    *     what is done in the Scala standard libraries 2.13.x
    *     `DurationConverters`. The differences being that if the `TimeUnit`
    *     doesn't match a known unit, then `None` is returned. This makes the
    *     code future proof in the case that the JDK adds more `TimeUnit`
    *     values in the future.
    *   - When the Scala Duration is `Duration.Undefined` or `Duration.Inf`,
    *     `None` is returned. This will result in the timeout not being set on
    *     the `HttpRequest.Builder`, which will use the default behavior of
    *     having no timeout.
    *
    * @note Technically a Java Duration can overflow and throw an error for
    *       Duration values with too large a magnitude, e.g. as of JDK 15
    *       `Duration.ofSeconds(Long.MaxValue).plusSeconds(1L) yields a
    *       `java.lang.ArithmeticException: long overflow` error. However this
    *       does not need to concern this function as finite Scala Duration
    *       values have an even smaller domain than Java Duration values. Thus
    *       it is impossible to create a Scala Duration which can trigger this
    *       overflow.
    *
    * @see
    * [[https://docs.oracle.com/en/java/javase/15/docs/api/java.base/java/time/Duration.html]]
    */
  private def scalaDurationToJavaDuration(d: Duration): Option[JDuration] =
    d match {
      case Duration.Zero =>
        JDuration.ZERO.some
      case Duration.MinusInf =>
        JDuration.ofSeconds(Long.MinValue).some
      case d if d.isFinite =>
        d.unit match {
          case TimeUnit.NANOSECONDS => JDuration.ofNanos(d.length).some
          case TimeUnit.MICROSECONDS => JDuration.of(d.length, ChronoUnit.MICROS).some
          case TimeUnit.MILLISECONDS => JDuration.ofMillis(d.length).some
          case TimeUnit.SECONDS => JDuration.ofSeconds(d.length).some
          case TimeUnit.MINUTES => JDuration.ofMinutes(d.length).some
          case TimeUnit.HOURS => JDuration.ofHours(d.length).some
          case TimeUnit.DAYS => JDuration.ofDays(d.length).some
          case _ => // In case a future JDK adds more time units
            none
        }
      case _ => // Duration.Undefined, Duration.Inf
        none
    }

  /** Given a `java.net.http.HttpRequest.Builder` and a
    * `Option[java.time.Duration]` set the timeout on the request builder if
    * the option is defined.
    */
  private def withRequestTimeout(
      rb: HttpRequest.Builder,
      d: Option[JDuration]
  ): HttpRequest.Builder =
    d.fold(
      rb
    )((jd: JDuration) => rb.timeout(jd))
}
