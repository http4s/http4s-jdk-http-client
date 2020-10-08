val dhalljV = "0.4.0"
val http4sEmberClientV = "0.21.7"
val upickleV = "1.2.1"

// scalafmt: { align.preset = most, trailingCommas = always }
libraryDependencies ++= Seq(
  "org.dhallj"   % "dhall-core"          % dhalljV,
  "org.dhallj"   % "dhall-parser"        % dhalljV,
  "org.dhallj"   % "dhall-yaml"          % dhalljV,
  "org.dhallj"  %% "dhall-imports"       % dhalljV,
  "org.http4s"  %% "http4s-ember-client" % http4sEmberClientV,
  "com.lihaoyi" %% "upickle"             % upickleV,
)
// scalafmt: { align.preset = none, trailingCommas = never }
