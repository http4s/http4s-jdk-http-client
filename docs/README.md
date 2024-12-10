{% laika.versioned = true %}

# http4s-jdk-http-client

[![Build Status](https://github.com/http4s/http4s-jdk-http-client/workflows/CI/badge.svg?branch=master)](https://github.com/http4s/http4s-jdk-http-client/actions) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@) [![javadoc](https://javadoc.io/badge2/org.http4s/http4s-jdk-http-client_2.13/javadoc.svg)](https://javadoc.io/doc/org.http4s/http4s-jdk-http-client_2.13)

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

@:callout(warning)

**TLS 1.3 on Java 11.** On Java 11, TLS 1.3 is disabled by default (when using `JdkHttpClient.simple`).
This is a workaround for a spurious bug, see [#200](https://github.com/http4s/http4s-jdk-http-client/issues/200).

@:@

### Creating the client

#### Simple

A default JDK HTTP client can be created with a call to `simple` for
any [`Async`][Async] type, such as `cats.effect.IO`:

```scala mdoc:silent:reset-class
import cats.effect.{IO, Resource}
import org.http4s.client.Client
import org.http4s.jdkhttpclient.JdkHttpClient

// Here, we import the global runtime.
// It comes for free with `cats.effect.IOApp`:
import cats.effect.unsafe.implicits.global

val client: Resource[IO, Client[IO]] = JdkHttpClient.simple[IO]
```

#### Custom clients

A JDK HTTP client can be passed to `JdkHttpClient.apply` for use as an
http4s-client backend.  It is a good idea to create the `HttpClient`
in an effect, as it creates an SSL context:

```scala mdoc:silent
import java.net.{InetSocketAddress, ProxySelector}
import java.net.http.HttpClient
import java.time.Duration

val client0: IO[Client[IO]] = IO.executor.flatMap { exec =>
  IO {
    HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .proxy(ProxySelector.of(new InetSocketAddress("www-proxy", 8080)))
      .executor(exec)
      .connectTimeout(Duration.ofSeconds(10))
      .build()
  }
}.map(JdkHttpClient(_))
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
  .use(c => fetchStatus(c, uri"https://http4s.org/"))
  .unsafeRunSync()
```

@:callout(warning)

**Failure to share.**
Contrast with this alternate definition of `fetchStatus`, which would
create a new `HttpClient` instance on every invocation:

```scala mdoc
def fetchStatusInefficiently[F[_]: Async](uri: Uri): F[Status] =
  JdkHttpClient.simple[F].use(_.status(Request[F](Method.GET, uri = uri)))
```

@:@

### Restricted headers

The underlying `HttpClient` may disallow certain request headers like `Host`
or `Content-Length` to be set directly by the user. Therefore, you can pass a set
of ignored headers to `JdkHttpClient.apply`. By default, the set of restricted
headers of OpenJDK 11 is used.

In OpenJDK 12+, there are less restricted headers by default, and you can disable
the restriction for certain headers by passing
`-Djdk.httpclient.allowRestrictedHeaders=host,content-length` etc. to `java`.

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
import org.http4s.client.websocket._
import org.http4s.jdkhttpclient._

val (http, webSocket) =
  IO(HttpClient.newHttpClient())
    .map { httpClient =>
      (JdkHttpClient[IO](httpClient), JdkWSClient[IO](httpClient))
    }
    .unsafeRunSync()
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
   as a [`Deferred`][Deferred]). In fact, this currently also the case for the
   "low-level" mode, but this will change when other websocket backends are added.
 - Groups the data frames by their `last` attribute.

### Usage example

We use the "high-level" connection mode to build a simple websocket app.

```scala mdoc:invisible
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

implicit val loggerFactory: LoggerFactory[IO] = NoOpFactory[IO]

val echoServer = EmberServerBuilder.default[IO]
  .withPort(port"0")
  .withHttpWebSocketApp(wsb => HttpRoutes.of[IO] {
    case GET -> Root => wsb.build(identity)
  }.orNotFound)
  .build
  .map(s => s.baseUri.copy(scheme = scheme"ws".some))
```

```scala mdoc
echoServer.use { echoUri =>
  webSocket
    .connectHighLevel(WSRequest(echoUri))
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
  }
  .unsafeRunSync()
```

For an overview of all options and functions visit the [scaladoc].

[http4s-client]: https://http4s.org/v@HTTP4S_VERSION_SHORT@/client/
[Java HttpClient]: https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html
[Async]: https://typelevel.org/cats-effect/docs/typeclasses/async
[Resource]: https://typelevel.org/cats-effect/docs/std/resource
[Deferred]: https://typelevel.org/cats-effect/docs/std/deferred
[scaladoc]: https://static.javadoc.io/org.http4s/http4s-jdk-http-client_@SCALA_VERSION@/@VERSION@/org/http4s/jdkhttpclient/index.html
