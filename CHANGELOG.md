# changelog

This file summarizes **notable** changes for each release, but does not describe internal changes unless they are particularly exciting. This change log is ordered chronologically, so each release contains all changes described below it.

----

## v0.3.2 (2020-12-10)

This release is considered stable.

In particular, the dependency on fs2 is updated to 2.4.6 w.r.t. [#321](https://github.com/http4s/http4s-jdk-http-client/issues/321).

### Dependency updates

* cats-2.3.0
* cats-effect-2.3.0
* scodec-bits-1.1.12
* fs2-2.4.6
* http4s-0.21.13

## v0.3.1 (2020-08-10)

This release is considered stable.

### Bugfixes

* [#260](https://github.com/http4s/http4s-jdk-http-client/pull/260) Workaround for a spurious deadlock with Java 11 and TLS 1.3

### Dependency updates

* cats-effect-2.1.4
* fs2-2.4.2
* http4s-0.21.7
* scodec-bits-1.1.18
* scala-2.12.12 and scala-2.13.3

## v0.3.0 (2020-04-05)

This release is considered stable.

### Bugfixes

* [#191](https://github.com/http4s/http4s-jdk-http-client/pull/191) Now shifting back from the HTTP thread pool. This adds `ContextShift` bounds to client creation.
* [#207](https://github.com/http4s/http4s-jdk-http-client/pull/207) Fix connection leak if body is not consumed

### Dependency updates

* cats-core-2.1.1
* fs2-2.3.0
* http4s-0.21.3
* scodec-bits-1.1.14
* scala-2.12.11

### Documentation

* [#187](https://github.com/http4s/http4s-jdk-http-client/pull/187) mention restricted headers
* [#206](https://github.com/http4s/http4s-jdk-http-client/pull/206) document spurious deadlocks with Java 11 and TLS 1.3

## v0.2.0 (2020-02-09)

This release is considered stable.

### Dependency updates

* http4s-0.21.0

## v0.2.0-RC5 (2020-02-03)

This release is fully source compatible with v0.2.0-RC4, but has a binary-incompatible dependency on http4s.

### Dependency updates

* http4s-0.21.0-RC3

## v0.2.0-RC4 (2020-02-01)

This release is fully source compatible with v0.2.0-RC2, but has a binary-incompatible dependency on http4s.

### Dependency updates

* http4s-0.21.0-RC2
* scodec-bits-1.1.13

~~## v0.2.0-RC3 (2020-02-01)~~

Cursed release. Sonatype says it's there, Maven Central says it's not.

## v0.2.0-RC2 (2020-01-22)

### Dependency updates

* http4s-0.21.0-RC1

## v0.2.0-RC1 (2020-01-22)

### Enhancements

* [#139](https://github.com/http4s/http4s-jdk-http-client/pull/139): Re-raise error in finalizer of web socket listener

### Dependency updates

* cats-2.1.0
* fs2-2.2.1

## v0.2.0-M4 (2019-12-01)

### Dependency updates

* fs2-2.1.0
* http4s-0.21.0-M6

## v0.2.0-M3 (2019-10-02)

### Breaking changes

* [#65](https://github.com/http4s/http4s-jdk-http-client/pull/65): Add `receive[F[Option[WSDataFrame]]]` to the high-level interface. `receiveStream` is now final.

### Dependency updates

* cats-2.0.0-RC2
* fs2-2.0.1
* http4s-0.21.0-M5
* scala-2.12.9 (2.12 cross build)

## v0.2.0-M2 (2019-08-14)

### Enhancements

* [#53](https://github.com/http4s/http4s-jdk-http-client/pull/53): Adds an experimental WebSocket client. In the long term, the plan is to move the `WSClient` interface into http4s-core and unify the client and server frame ADT, but we encourage users to give this a try.

### Documentation

* [#44](https://github.com/http4s/http4s-jdk-http-client/pull/44): Fix supported Scala versions in docs
* [#45](https://github.com/http4s/http4s-jdk-http-client/pull/45): Generate `/stable` redirect in docs

### Dependency updates

* better-monadic-for-0.3.1
* cats-2.0.0-RC1
* cats-effect-2.0.0-RC1
* http4s-0.21.0-M4

## v0.2.0-M1 (2019-06-20)

### Cross-build changes

* Adds Scala 2.13 support. 
* Drops Scala 2.13.0-M5 support.

### Dependency updates

* cats-2.0.0-M4
* cats-effect-2.0.0-M4
* fs2-1.1.0-M1
* http4s-client-0.21.0-M1

## v0.1.0 (2019-06-20)

* Initial release
