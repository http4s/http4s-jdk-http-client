package org.http4s.client.jdkhttpclient

import java.net.http.HttpClient

import cats.effect._
import cats.implicits._
import org.http4s.client.Client
import org.http4s.util.CaseInsensitiveString
import scala.concurrent.duration._

/** A builder for making JDK `Client`s. */
sealed trait JdkHttpClientBuilder {

  /** Add or replace a request timeout.
    *
    * If not set, a JDK `HttpClient` will not timeout per request.
    *
    * @see
    * [[https://docs.oracle.com/en/java/javase/15/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration)]]
    */
  def withRequestTimeout(duration: Duration): JdkHttpClientBuilder

  /** Get the currently set request timeout, if defined. */
  def requestTimeout: Option[Duration]

  /** Add or replace the connect timeout
    *
    * If not set, a JDK `HttpClient` will not timeout on connect.
    *
    * @see
    * [[https://docs.oracle.com/en/java/javase/15/docs/api/java.net.http/java/net/http/HttpClient.Builder.html#connectTimeout(java.time.Duration)]]
    */
  def withConnectTimeout(duration: Duration): JdkHttpClientBuilder

  /** Get the currently set request timeout, if defined. */
  def connectTimeout: Option[Duration]

  /** Set headers which are filtered from the Request.
    *
    * @note Unless you have an advanced use case and understand the
    *       implications this value with respect to the JDK `HttpClient` is
    *       recommended that you ''do not set this value''.
    *
    * @see
    * [[https://github.com/openjdk/jdk/blob/jdk-15+36/src/java.net.http/share/classes/jdk/internal/net/http/common/Utils.java#L169]]
    */
  def withIgnoredHeaders(headers: Set[CaseInsensitiveString]): JdkHttpClientBuilder

  /** Get the set of currently ignored headers. */
  def ignoredHeaders: Set[CaseInsensitiveString]

  /** Set the jdk `HttpClient.Builder` to use with this builder.
    *
    * @note If not set, a reasonable default will be used.
    *
    * This can be useful if there is a setting on the `HttpClient.Builder`
    * which is not yet supported on this builder directly.
    *
    * @see
    * [[https://docs.oracle.com/en/java/javase/15/docs/api/java.net.http/java/net/http/HttpClient.Builder.html]]
    */
  def withJdkHttpClientBuilder(client: HttpClient.Builder): JdkHttpClientBuilder

  /** Get the current `HttpClient.Builder` for this builder, if defined.
    *
    * @note If this is empty, a reasonable default will be used when invoking
    * [[#build]]
    */
  def jdkHttpClientBuilder: Option[HttpClient.Builder]

  // Final //

  /** Add headers to the set of ignored headers.
    *
    * @note Unless you have an advanced use case and understand the
    *       implications this value with respect to the JDK `HttpClient` is
    *       recommended that you ''do not add values''.
    *
    * @see
    * [[https://github.com/openjdk/jdk/blob/jdk-15+36/src/java.net.http/share/classes/jdk/internal/net/http/common/Utils.java#L169]]
    */
  final def addIgnoredHeaders(headers: CaseInsensitiveString*): JdkHttpClientBuilder =
    withIgnoredHeaders(
      ignoredHeaders ++ headers.toSet
    )

  /** Remove headers, if present, from the set of ignored headers.
    *
    * @note Unless you have an advanced use case and understand the
    *       implications this value with respect to the JDK `HttpClient` is
    *       recommended that you ''do not remove values''.
    *
    * @see
    * [[https://github.com/openjdk/jdk/blob/jdk-15+36/src/java.net.http/share/classes/jdk/internal/net/http/common/Utils.java#L169]]
    */
  final def removeIgnoredHeaders(headers: CaseInsensitiveString*): JdkHttpClientBuilder =
    withIgnoredHeaders(
      ignoredHeaders &~ headers.toSet
    )

  /** Build a new `Client` using the settings in this builder and return the
    * `Client` as well as the underlying `HttpClient`. This can be useful when
    * you wish to share the `HttpClient` with some other API,
    * e.g. [[JdkWSClient]].
    */
  final def build_[F[_]: ConcurrentEffect: ContextShift]: F[(HttpClient, Client[F])] =
    JdkHttpClient.fromBuilder_[F](this)

  /** Build a new `Client` using the settings in this builder. */
  final def build[F[_]: ConcurrentEffect: ContextShift]: F[Client[F]] =
    JdkHttpClient.fromBuilder[F](this)
}

object JdkHttpClientBuilder {
  private[this] final case class JdkHttpClientBuilderImpl(
      override val requestTimeout: Option[Duration],
      override val connectTimeout: Option[Duration],
      override val ignoredHeaders: Set[CaseInsensitiveString],
      override val jdkHttpClientBuilder: Option[HttpClient.Builder]
  ) extends JdkHttpClientBuilder {
    override def withRequestTimeout(duration: Duration): JdkHttpClientBuilder =
      copy(requestTimeout = duration.some)
    override def withConnectTimeout(duration: Duration): JdkHttpClientBuilder =
      copy(connectTimeout = duration.some)
    override def withIgnoredHeaders(headers: Set[CaseInsensitiveString]): JdkHttpClientBuilder =
      copy(ignoredHeaders = headers)
    override def withJdkHttpClientBuilder(builder: HttpClient.Builder): JdkHttpClientBuilder =
      copy(jdkHttpClientBuilder = builder.some)
  }

  // see jdk.internal.net.http.common.Utils#DISALLOWED_HEADERS_SET
  private[this] val restrictedHeaders: Set[CaseInsensitiveString] =
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

  /** A [[JdkHttpClientBuilder]] with the default settings. */
  lazy val default: JdkHttpClientBuilder =
    JdkHttpClientBuilderImpl(None, None, restrictedHeaders, None)
}
