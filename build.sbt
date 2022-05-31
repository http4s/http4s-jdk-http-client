import com.typesafe.tools.mima.core._

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(core, docs)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "http4s-jdk-http-client",
    libraryDependencies ++= coreDeps,
    mimaBinaryIssueFilters ++= Seq(
      // package private, due to #641
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.jdkhttpclient.JdkHttpClient.defaultHttpClient"
      )
    )
  )

lazy val docs = project
  .in(file("site"))
  .enablePlugins(Http4sOrgSitePlugin)
  .dependsOn(core)
  .settings(docsSettings)
  .settings(libraryDependencies ++= blazeServer)

ThisBuild / mergifyStewardConfig := Some(
  MergifyStewardConfig(action = MergifyAction.Merge(method = Some("squash")))
)
ThisBuild / mergifyRequiredJobs += "site"
ThisBuild / mergifyLabelPaths += "docs" -> file("docs")

val catsV = "2.7.0"
val catsEffectV = "3.3.12"
val fs2V = "3.2.7"
val scodecV = "1.1.31"
val http4sV = "1.0.0-M33"
val reactiveStreamsV = "1.0.3"
val vaultV = "3.1.0"
val caseInsensitiveV = "1.2.0"

val http4sBlazeV = "1.0.0-M33"
val munitV = "0.7.29"
val munitCatsEffectV = "1.0.7"
val javaWebsocketV = "1.5.3"

val blazeServer = Seq(
  "org.http4s" %% "http4s-blaze-server" % http4sBlazeV,
  "org.http4s" %% "http4s-dsl" % http4sBlazeV
)

val coreDeps = Seq(
  "org.typelevel" %% "cats-core" % catsV,
  "org.typelevel" %% "cats-effect" % catsEffectV,
  "org.typelevel" %% "cats-effect-kernel" % catsEffectV,
  "org.typelevel" %% "cats-effect-std" % catsEffectV,
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

val scala213 = "2.13.8"
ThisBuild / crossScalaVersions := Seq(scala213, "3.1.2")
ThisBuild / scalaVersion := scala213
ThisBuild / tlBaseVersion := "1.0"
ThisBuild / startYear := Some(2019)
ThisBuild / developers := List(
  tlGitHubDev("ChristopherDavenport", "Christopher Davenport"),
  tlGitHubDev("amesgen", "Alexander Esgen"),
  tlGitHubDev("rossabaker", "Ross A. Baker")
)

ThisBuild / tlJdkRelease := Some(11)
ThisBuild / githubWorkflowJavaVersions := Seq("11", "17").map(JavaSpec.temurin(_))
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlSitePublishBranch := Some("main")

lazy val docsSettings =
  Seq(
    tlSiteApiModule := Some((core / projectID).value),
    tlSiteApiPackage := Some("org.http4s.jdkhttpclient"),
    tlSiteHeliumConfig ~= {
      import laika.rewrite._
      _.site.versions(
        Versions(
          currentVersion = Version("1.x", "1.x"),
          olderVersions = Seq(
            Version("0.7.x", "0.7"),
            Version("0.6.x", "0.6.0-M7"),
            Version("0.5.x", "0.5.0"),
            Version("0.4.x", "0.4.0")
          ),
          renderUnversioned = true
        )
      )
    },
    mdocVariables ++= Map(
      "HTTP4S_VERSION" -> http4sV,
      "HTTP4S_VERSION_SHORT" -> http4sV.split("\\.").take(2).mkString("."),
      "SCALA_VERSION" -> CrossVersion.binaryScalaVersion(scalaVersion.value),
      "SCALA_VERSIONS" -> formatCrossScalaVersions((core / crossScalaVersions).value.toList)
    ),
    unusedCompileDependenciesFilter -= moduleFilter()
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
