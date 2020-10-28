# http4s-jdk-http-client

[![Build Status](https://github.com/http4s/http4s-jdk-http-client/workflows/CI/badge.svg?branch=master)](https://github.com/http4s/http4s-jdk-http-client/actions) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@) [![Scaladoc](https://javadoc-badge.appspot.com/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@.svg?label=scaladoc)](https://javadoc-badge.appspot.com/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@)

## HTTP client

`http4s-jdk-http-client` contains a [http4s-client] implementation based on
the [`java.net.http.HttpClient`][Java HttpClient] introduced in Java
11.

### Installation

To use http4s-jdk-http-client in an existing SBT project, add the
following dependency to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-jdk-http-client" % "@VERSION@"
)
```

### Compatibility

* Requires Java 11 or greater
* Built for Scala @SCALA_VERSIONS@
* Works with http4s-client-@HTTP4S_VERSION@

@@@note { title='TLS 1.3 on Java 11' }
On Java 11, TLS 1.3 is disabled by default (when using `JdkHttpClient.simple`).
This is a workaround for a spurious bug, see [#200](https://github.com/http4s/http4s-jdk-http-client/issues/200).
@@@

### Creating the client

#### Simple

A default JDK HTTP client can be created with a call to `simple` for
any [`ConcurrentEffect`][ConcurrentEffect] type, such as
[`cats.effect.IO`][IO]:

```scala mdoc:silent:reset-class
import cats.effect.IO
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient

// A `Timer` and `ContextShift` are necessary for a `ConcurrentEffect[IO]`.
// They come for free when you use `cats.effect.IOApp`:
import cats.effect.{ContextShift, Timer}
import scala.concurrent.ExecutionContext.Implicits.global
implicit val timer: cats.effect.Timer[IO] = IO.timer(global)
implicit val cs: cats.effect.ContextShift[IO] = IO.contextShift(global)

val client: IO[Client[IO]] = JdkHttpClient.simple[IO]
```

#### Custom clients

If you need to customize the settings for the client you can create the client
via a `JdkHttpClientBuilder`. If there are settings on the `HttpClient.Builder`
which are not yet supported on the `JdkHttpClientBuilder`, you can set them
directly on the `HttpClient.Builder` and provide that to the
`JdkHttpClientBuilder`.

```scala mdoc:silent
import java.net.{InetSocketAddress, ProxySelector}
import java.net.http.HttpClient
import org.http4s.client.jdkhttpclient.JdkHttpClientBuilder

val client0: IO[Client[IO]] = JdkHttpClientBuilder.default.withJdkHttpClientBuilder(
    HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .proxy(ProxySelector.of(new InetSocketAddress("www-proxy", 8080)))
).build
```

### Sharing

The client instance contains shared resources such as a connection
pool, and should be passed as an argument to code that uses it:

```scala mdoc
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.implicits._

def fetchStatus[F[_]](c: Client[F], uri: Uri): F[Status] =
  c.status(Request[F](Method.GET, uri = uri))

client
  .flatMap(c => fetchStatus(c, uri"https://http4s.org/"))
  .attempt
  .unsafeRunSync()
```

@@@warning { title='Failure to share' }

Contrast with this alternate definition of `fetchStatus`, which would
create a new `HttpClient` instance on every invocation:

```scala mdoc
def fetchStatusInefficiently[F[_]: ConcurrentEffect: ContextShift](uri: Uri): F[Status] =
  JdkHttpClient.simple[F].flatMap(_.status(Request[F](Method.GET, uri = uri)))
```

@@@

### Restricted headers

The underlying `HttpClient` may disallow certain request headers like `Host`
or `Content-Length` to be set directly by the user. Therefore, you can pass a set
of ignored headers to `JdkHttpClient.apply`. By default, the set of restricted
headers of OpenJDK 11 is used.

In OpenJDK 12+, there are less restricted headers by default, and you can disable
the restriction for certain headers by passing
`-Djdk.httpclient.allowRestrictedHeaders=host,content-length` etc. to `java`.

### Shutdown

Clients created with this back end do not need to be shut down.

### Further reading

For more details on the http4s-client, please see the [core client
documentation][http4s-client].

## Websocket client

This package also contains a functional websocket client. Please note that
the API may change in the future.

### Creation

A `WSClient` is created
using an `HttpClient` as above. It is encouraged to use the same `HttpClient`
to construct a `Client[F]` and a `WSClient[F]`.

```scala mdoc
import org.http4s.client.jdkhttpclient._

val (http, webSocket) = JdkHttpClient.simple_[IO].map{
    case (jdkHttpClient, http4sHttpClient) =>
      (http4sHttpClient, JdkWSClient[IO](jdkHttpClient))}.unsafeRunSync()
```

If you do not need an HTTP client, you can also call `JdkWSClient.simple[IO]` as above.

### Overview

We have the following websocket frame hierarchy:

 - `WSFrame`
   - `WSControlFrame`
     - `WSFrame.Close`
     - `WSFrame.Ping`
     - `WSFrame.Pong`
   - `WSDataFrame`
     - `WSFrame.Text`
     - `WSFrame.Binary`

There are two connection modes: "low-level" and "high-level". Both manage the lifetime of a
websocket connection via a [`Resource`][Resource].
In the low-level mode, you can send and have to receive arbitrary `WSFrame`s.
The high-level mode does the following things for you:

 - Hides the control frames (you can still send Ping and Close frames).
 - Responds to Ping frames with Pongs and echoes Close frames (the received Close frame is exposed
   as a [`TryableDeferred`][TryableDeferred]). In fact, this currently also the case for the
   "low-level" mode, but this will change when other websocket backends are added.
 - Groups the data frames by their `last` attribute.

### Usage example

We use the "high-level" connection mode to build a simple websocket app.

```scala mdoc
webSocket
  .connectHighLevel(WSRequest(uri"wss://echo.websocket.org"))
  .use { conn =>
    for {
      // send a single Text frame
      _ <- conn.send(WSFrame.Text("reality"))
      // send multiple frames (both Text and Binary are possible)
      // "faster" than individual `send` calls
      _ <- conn.sendMany(List(
        WSFrame.Text("is often"),
        WSFrame.Text("disappointing.")
      ))
      received <- conn
        // a backpressured stream of incoming frames
        .receiveStream
        // we do not care about Binary frames (and will never receive any)
        .collect { case WSFrame.Text(str, _) => str }
        // send back the modified text
        .evalTap(str => conn.send(WSFrame.Text(str.toUpperCase)))
        .take(6)
        .compile
        .toList
    } yield received.mkString(" ")
  } // the connection is closed here
  .unsafeRunSync()
```

For an overview of all options and functions visit the [scaladoc].

[http4s-client]: https://http4s.org/v@HTTP4S_VERSION_SHORT@/client/
[Java HttpClient]: https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html
[ConcurrentEffect]: https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html
[IO]: https://typelevel.org/cats-effect/datatypes/io.html
[Resource]: https://typelevel.org/cats-effect/datatypes/resource.html
[TryableDeferred]: https://typelevel.org/cats-effect/api/cats/effect/concurrent/TryableDeferred.html
[scaladoc]: https://static.javadoc.io/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@/@VERSION@/org/http4s/client/jdkhttpclient/index.html
