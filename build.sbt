lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(core, docs)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "http4s-jdk-http-client",
    libraryDependencies ++= coreDeps
  )

lazy val docs = project
  .in(file("site"))
  .enablePlugins(Http4sOrgSitePlugin)
  .dependsOn(core)
  .settings(docsSettings)
  .settings(libraryDependencies ++= blazeServer)

val catsV = "2.7.0"
val catsEffectV = "3.3.0"
val fs2V = "3.2.2"
val scodecV = "1.1.30"
val http4sV = "0.23.10"
val reactiveStreamsV = "1.0.3"
val vaultV = "3.1.0"
val caseInsensitiveV = "1.2.0"

val munitV = "0.7.29"
val munitCatsEffectV = "1.0.6"
val javaWebsocketV = "1.5.2"

val blazeServer = Seq(
  "org.http4s" %% "http4s-blaze-server" % http4sV,
  "org.http4s" %% "http4s-dsl" % http4sV
)

val coreDeps = Seq(
  "org.typelevel" %% "cats-core" % catsV,
  "org.typelevel" %% "cats-effect" % catsEffectV,
  "org.typelevel" %% "cats-effect-kernel" % catsEffectV,
  "org.typelevel" %% "cats-effect-std" % catsEffectV,
  "org.typelevel" %% "cats-kernel" % catsV,
  "co.fs2" %% "fs2-core" % fs2V,
  "co.fs2" %% "fs2-reactive-streams" % fs2V,
  "org.http4s" %% "http4s-client" % http4sV,
  "org.http4s" %% "http4s-core" % http4sV,
  "org.reactivestreams" % "reactive-streams" % reactiveStreamsV,
  "org.scodec" %% "scodec-bits" % scodecV,
  "org.typelevel" %% "vault" % vaultV,
  "org.typelevel" %% "case-insensitive" % caseInsensitiveV
) ++ (blazeServer ++ Seq(
  "org.java-websocket" % "Java-WebSocket" % javaWebsocketV,
  "org.scalameta" %% "munit" % munitV,
  "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectV
)).map(_ % Test)

ThisBuild / crossScalaVersions := Seq("2.12.15", "2.13.7", "3.1.0")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head
ThisBuild / tlBaseVersion := "0.7"
ThisBuild / startYear := Some(2021)
ThisBuild / developers := List(
  Developer(
    "ChristopherDavenport",
    "Christopher Davenport",
    "chris@christopherdavenport.tech",
    url("https://github.com/ChristopherDavenport")
  ),
  Developer(
    "amesgen",
    "Alexander Esgen",
    "amesgen@amesgen.de",
    url("https://github.com/amesgen")
  ),
  Developer(
    "rossabaker",
    "Ross A. Baker",
    "ross@rossabaker.com",
    url("https://github.com/rossabaker")
  )
)

ThisBuild / githubWorkflowJavaVersions := Seq("11", "17").map(JavaSpec.temurin(_))
ThisBuild / tlCiReleaseBranches := Seq("series/0.7")
ThisBuild / tlSitePublishBranch := Some("series/0.7")

lazy val docsSettings =
  Seq(
    mdocVariables ++= Map(
      "HTTP4S_VERSION" -> http4sV,
      "HTTP4S_VERSION_SHORT" -> http4sV.split("\\.").take(2).mkString("."),
      "SCALA_VERSION" -> CrossVersion.binaryScalaVersion(scalaVersion.value),
      "SCALA_VERSIONS" -> formatCrossScalaVersions((core / crossScalaVersions).value.toList)
    ),
    tlSitePublish := List( // TODO remove after upstreamed
      WorkflowStep.Use(
        UseRef.Public("peaceiris", "actions-gh-pages", "v3.8.0"),
        Map(
          "github_token" -> s"$${{ secrets.GITHUB_TOKEN }}",
          "publish_dir" -> (ThisBuild / baseDirectory).value.toPath.toAbsolutePath
            .relativize(((Laika / target).value / "site").toPath)
            .toString,
          "keep_files" -> "true"
        )
      )
    )
  )

def formatCrossScalaVersions(crossScalaVersions: List[String]): String = {
  def go(vs: List[String]): String =
    vs match {
      case Nil => ""
      case a :: Nil => a
      case a :: b :: Nil => s"$a and $b"
      case a :: bs => s"$a, ${go(bs)}"
    }
  go(crossScalaVersions.map(CrossVersion.binaryScalaVersion))
}
