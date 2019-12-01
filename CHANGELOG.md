# changelog

This file summarizes **notable** changes for each release, but does not describe internal changes unless they are particularly exciting. This change log is ordered chronologically, so each release contains all changes described below it.

----

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
