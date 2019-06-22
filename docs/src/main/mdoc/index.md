# http4s-jdk-http-client

[![Build Status](https://travis-ci.com/http4s/http4s-jdk-http-client.svg?branch=master)](https://travis-ci.com/http4s/http4s-jdk-http-client) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-jdk-http-client_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-jdk-http-client_2.12)

`http4s-jdk-http-client` is a [http4s-client] implementation based on
the [`java.net.http.HttpClient`][Java HttpClient] introduced in Java
11.

## Installation

To use http4s-jdk-http-client in an existing SBT project, add the
following dependency to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-jdk-http-client" % "@VERSION@"
)
```

## Compatibility

* Requires Java 11 or greater
* Built for Scala @SCALA_VERSIONS@
* Works with http4s-client-@HTTP4S_VERSION@

## Creating the client

### Simple

A default JDK HTTP client can be created with a call to `simple` for
any [`ConcurrentEffect`][ConcurrentEffect] type, such as
[`cats.effect.IO`][IO]:

```scala mdoc:silent
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

### Custom clients

A JDK HTTP client can be passed to `JdkHttpClient.apply` for use as an
http4s-client backend.  It is a good idea to create the `HttpClient`
in an effect, as it creates a default executor and SSL context:

```scala mdoc:silent
import java.net.{InetSocketAddress, ProxySelector}
import java.net.http.HttpClient

val client0: IO[Client[IO]] = IO {
  HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .proxy(ProxySelector.of(new InetSocketAddress("www-proxy", 8080)))
    .build()
}.map(JdkHttpClient(_))
```

## Sharing

The client instance contains shared resources such as a connection
pool, and should be passed as an argument to code that uses it:

```scala mdoc
import cats.effect._
import cats.implicits._
import org.http4s._
  
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
def fetchStatusInefficiently[F[_]: ConcurrentEffect](uri: Uri): F[Status] =
  JdkHttpClient.simple[F].flatMap(_.status(Request[F](Method.GET, uri = uri)))
```

@@@

## Shutdown

Clients created with this back end do not need to be shut down.

## Further reading

For more details on the http4s-client, please see the [core client
documentation][client].

[http4s-client]: https://http4s.org/v@HTTP4S_VERSION@/client/
[Java HttpClient]: https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html
[ConcurrentEffect]: https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html
[IO]: https://typelevel.org/cats-effect/datatypes/io.html
