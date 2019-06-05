lazy val `http4s-jdk-http-client` = project.in(file("."))
  .settings(commonSettings, releaseSettings, skipOnPublishSettings)
  .settings(crossScalaVersions := Nil)
  .aggregate(core)

lazy val core = project.in(file("core"))
  .settings(commonSettings, releaseSettings, mimaSettings)
  .settings(
    name := "http4s-jdk-http-client"
  )

lazy val docs = project.in(file("docs"))
  .enablePlugins(GhpagesPlugin, MdocPlugin, ParadoxMaterialThemePlugin, ParadoxSitePlugin)
  .dependsOn(core)
  .settings(commonSettings, skipOnPublishSettings, docsSettings)

lazy val contributors = Seq(
  "ChristopherDavenport"  -> "Christopher Davenport",
  "amesgen"               -> "Alexander Esgen",  
  "rossabaker"            -> "Ross A. Baker",
)

val catsV = "1.6.1"
val catsEffectV = "1.3.1"
val fs2V = "1.0.4"
val http4sV = "0.20.1"
val reactiveStreamsV = "1.0.2"

val specs2V = "4.5.1"

val kindProjectorV = "0.9.9"
val betterMonadicForV = "0.3.0-M4"

// General Settings
lazy val commonSettings = Seq(
  organization := "org.http4s",

  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value, "2.13.0-M5"),
  scalacOptions += "-Yrangepos",

  scalacOptions in (Compile, doc) ++= Seq(
      "-groups",
      "-sourcepath", (baseDirectory in LocalRootProject).value.getAbsolutePath,
      "-doc-source-url", "https://github.com/http4s/http4s-jdk-http-client/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),

  addCompilerPlugin("org.spire-math" % "kind-projector" % kindProjectorV cross CrossVersion.binary),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForV),
  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                      % catsV,
    "org.typelevel"               %% "cats-effect"                    % catsEffectV,
    "co.fs2"                      %% "fs2-core"                       % fs2V,
    "co.fs2"                      %% "fs2-io"                         % fs2V,
    "co.fs2"                      %% "fs2-reactive-streams"           % fs2V,
    "org.http4s"                  %% "http4s-client"                  % http4sV,
    "org.reactivestreams"         %  "reactive-streams-flow-adapters" % reactiveStreamsV,
    
    "org.http4s"                  %% "http4s-testing"                 % http4sV       % Test,
    "org.specs2"                  %% "specs2-core"                    % specs2V       % Test,
    "org.specs2"                  %% "specs2-scalacheck"              % specs2V       % Test
  ),

  git.remoteRepo := "git@github.com:http4s/http4s-jdk-http4s-client.git",
)

lazy val releaseSettings = {
  import ReleaseTransformations._
  Seq(
    releaseCrossBuild := true,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      // For non cross-build projects, use releaseStepCommand("publishSigned")
      releaseStepCommandAndRemaining("+publishSigned"),
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges
    ),
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    credentials ++= (
      for {
        username <- Option(System.getenv().get("SONATYPE_USERNAME"))
        password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
      } yield
        Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          username,
          password
        )
    ).toSeq,
    publishArtifact in Test := false,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/http4s/http4s-jdk-http-client"),
        "git@github.com:http4s/http4s-jdk-http-client.git"
      )
    ),
    homepage := Some(url("https://github.com/http4s/http4s-jdk-http-client")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    publishMavenStyle := true,
    pomIncludeRepository := { _ =>
      false
    },
    pomExtra := {
      <developers>
        {for ((username, name) <- contributors) yield
        <developer>
          <id>{username}</id>
          <name>{name}</name>
          <url>http://github.com/{username}</url>
        </developer>
        }
      </developers>
    },
    pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray),
  )
}

lazy val mimaSettings = {
  import sbtrelease.Version

  def semverBinCompatVersions(major: Int, minor: Int, patch: Int): Set[(Int, Int, Int)] = {
    val majorVersions: List[Int] =
      if (major == 0 && minor == 0) List.empty[Int] // If 0.0.x do not check MiMa
      else List(major)
    val minorVersions : List[Int] =
      if (major >= 1) Range(0, minor).inclusive.toList
      else List(minor)
    def patchVersions(currentMinVersion: Int): List[Int] = 
      if (minor == 0 && patch == 0) List.empty[Int]
      else if (currentMinVersion != minor) List(0)
      else Range(0, patch - 1).inclusive.toList

    val versions = for {
      maj <- majorVersions
      min <- minorVersions
      pat <- patchVersions(min)
    } yield (maj, min, pat)
    versions.toSet
  }

  def mimaVersions(version: String): Set[String] = {
    Version(version) match {
      case Some(Version(major, Seq(minor, patch), _)) =>
        semverBinCompatVersions(major.toInt, minor.toInt, patch.toInt)
          .map{case (maj, min, pat) => maj.toString + "." + min.toString + "." + pat.toString}
      case _ =>
        Set.empty[String]
    }
  }
  // Safety Net For Exclusions
  lazy val excludedVersions: Set[String] = Set()

  // Safety Net for Inclusions
  lazy val extraVersions: Set[String] = Set()

  Seq(
    mimaFailOnProblem := mimaVersions(version.value).toList.headOption.isDefined,
    mimaPreviousArtifacts := (mimaVersions(version.value) ++ extraVersions)
      .filterNot(excludedVersions.contains(_))
      .map{v => 
        val moduleN = moduleName.value + "_" + scalaBinaryVersion.value.toString
        organization.value % moduleN % v
      },
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._
      import com.typesafe.tools.mima.core.ProblemFilters._
      Seq()
    }
  )
}

lazy val docsSettings = {
  ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox) ++
  Seq(
    crossScalaVersions := List(scalaVersion.value),
    mdocIn := (baseDirectory.value) / "src" / "main" / "mdoc", // 
    mdocVariables := Map(
      "VERSION" -> version.value,
      "BINARY_VERSION" -> binaryVersion(version.value),
      "HTTP4S_VERSION" -> "0.20",
      "SCALA_VERSIONS" -> formatCrossScalaVersions(crossScalaVersions.value.toList)
    ),
    scalacOptions in mdoc --= Seq(
      "-Xfatal-warnings",
      "-Ywarn-unused-import",
      "-Ywarn-numeric-widen",
      "-Ywarn-dead-code",
      "-Ywarn-unused:imports",
      "-Xlint:-missing-interpolator,_"
    ),

    sourceDirectory in Paradox := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).value,
    Paradox / paradoxMaterialTheme ~= {
      _.withRepository(uri("https://github.com/http4s/http4s-jdk-http-client"))
       .withLogoUri(uri("https://http4s.org/images/http4s-logo.svg"))
    },

    ghpagesCommitOptions := {
      val sha = sys.env.getOrElse("TRAVIS_COMMIT", "???")
      val build = sys.env.getOrElse("TRAVIS_BUILD_NUMBER", "???")
      List(
        s"--author=Travis CI <travis-ci@invalid>",
        "-m", s"Updated site: sha=${sha} build=${build}"
      )
    },
  )
}

lazy val skipOnPublishSettings = Seq(
  skip in publish := true,
  publish := (()),
  publishLocal := (()),
  publishArtifact := false,
  publishTo := None
)

def binaryVersion(version: String) =
  version match {
    case VersionNumber(Seq(0, minor, _*), _, _) => s"0.$minor"
    case VersionNumber(Seq(major, _, _*), _, _) if major > 0 => major.toString
  }

def formatCrossScalaVersions(crossScalaVersions: List[String]): String = {
  def go(vs: List[String]): String = {
    vs match {
      case Nil => ""
      case a :: Nil => a
      case a :: b :: Nil => s"$a and $b"
      case a :: bs => s"$a, ${go(bs)}"
    }
  }
  go(crossScalaVersions.map(CrossVersion.binaryScalaVersion))
}

addCommandAlias("validate", ";test ;mimaBinaryIssueFilters ;scalafmtCheckAll ;docs/makeSite")
