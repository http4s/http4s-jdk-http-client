val dhalljV = "0.1.0"
val http4sJdkHttpClientV = "0.3.0"
val upickleV = "1.0.0"

// format: off
libraryDependencies ++= Seq(
  "org.dhallj"  %  "dhall-core"             % dhalljV,
  "org.dhallj"  %  "dhall-parser"           % dhalljV,
  "org.dhallj"  %  "dhall-yaml"             % dhalljV,
  "org.dhallj"  %% "dhall-imports"          % dhalljV,
  "org.http4s"  %% "http4s-jdk-http-client" % http4sJdkHttpClientV,
  "com.lihaoyi" %% "upickle"                % upickleV
)
// format: on
