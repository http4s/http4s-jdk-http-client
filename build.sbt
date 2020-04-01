import sbtrelease._
import com.typesafe.sbt.git.JGit

lazy val `http4s-jdk-http-client` = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .settings(commonSettings, releaseSettings, skipOnPublishSettings)
  .settings(
    crossScalaVersions := Nil,
  )
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

val catsV = "2.1.1"
val catsEffectV = "2.1.2"
val fs2V = "2.3.0"
val scodecV = "1.1.14"
val http4sV = "0.21.2"
val reactiveStreamsV = "1.0.3"
val vaultV = "2.0.0"

val specs2V = "4.9.2"
val catsEffectTestingV = "0.4.0"
val javaWebsocketV = "1.4.1"

val kindProjectorV = "0.10.3"
val betterMonadicForV = "0.3.1"

lazy val scalaVersions = {
  import cats.data.NonEmptyList
  val str = scala.io.Source.fromFile("scalaVersions.json").mkString
  _root_.io.circe.parser.decode[NonEmptyList[String]](str).fold(throw _, identity)
}

// General Settings
lazy val commonSettings = Seq(
  organization := "org.http4s",

  scalaVersion := scalaVersions.head,
  crossScalaVersions := scalaVersions.toList.toSeq,
  scalacOptions += "-Yrangepos",

  scalacOptions in (Compile, doc) ++= Seq(
      "-groups",
      "-sourcepath", (baseDirectory in LocalRootProject).value.getAbsolutePath,
      "-doc-source-url", "https://github.com/http4s/http4s-jdk-http-client/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),

  addCompilerPlugin("org.typelevel" % "kind-projector" % kindProjectorV cross CrossVersion.binary),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForV),
  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                      % catsV,
    "org.typelevel"               %% "cats-kernel"                    % catsV,
    "org.typelevel"               %% "cats-effect"                    % catsEffectV,
    "co.fs2"                      %% "fs2-core"                       % fs2V,
    "co.fs2"                      %% "fs2-reactive-streams"           % fs2V,
    "org.scodec"                  %% "scodec-bits"                    % scodecV,
    "org.http4s"                  %% "http4s-core"                    % http4sV,
    "org.http4s"                  %% "http4s-client"                  % http4sV,
    "org.reactivestreams"         %  "reactive-streams"               % reactiveStreamsV,
    "io.chrisdavenport"           %% "vault"                          % vaultV,
    
    "org.http4s"                  %% "http4s-testing"                 % http4sV            % Test,
    "org.specs2"                  %% "specs2-core"                    % specs2V            % Test,
    "org.specs2"                  %% "specs2-scalacheck"              % specs2V            % Test,
    "com.codecommit"              %% "cats-effect-testing-specs2"     % catsEffectTestingV % Test,
    "org.http4s"                  %% "http4s-dsl"                     % http4sV            % Test,
    "org.http4s"                  %% "http4s-blaze-server"            % http4sV            % Test,
    "org.java-websocket"          %  "Java-WebSocket"                 % javaWebsocketV     % Test
  ),

  git.remoteRepo := "git@github.com:http4s/http4s-jdk-http-client.git",
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
      releaseStepCommandAndRemaining("+publishSigned"),
      releaseStepCommand("sonatypeBundleRelease"),
      releaseStepCommand("docs/ghpagesPushSite"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        sonatypePublishToBundle.value
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
    unmanagedSourceDirectories in Compile ++= {
      (unmanagedSourceDirectories in Compile).value.map { dir =>
        val sv = scalaVersion.value
        CrossVersion.partialVersion(sv) match {
          case Some((2, 13)) => file(dir.getPath ++ "-2.13")
          case _             => file(dir.getPath ++ "-2.11-2.12")
        }
      }
    },
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
    mimaFailOnNoPrevious := false,
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
    },
  )
}

lazy val generateNetlifyToml = taskKey[Unit]("Generate netlify.toml")

lazy val docsSettings = {
  ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox) ++
  Seq(
    crossScalaVersions := List(scalaVersion.value),
    mdocIn := (baseDirectory.value) / "src" / "main" / "mdoc", // 
    mdocVariables := Map(
      "VERSION" -> version.value,
      "BINARY_VERSION" -> binaryVersion(version.value),
      "HTTP4S_VERSION" -> http4sV,
      "HTTP4S_VERSION_SHORT" -> http4sV.split("\\.").take(2).mkString("."),
      "SCALA_VERSION" -> CrossVersion.binaryScalaVersion(scalaVersion.value),
      "SCALA_VERSIONS" -> formatCrossScalaVersions((core / crossScalaVersions).value.toList)
    ),
    scalacOptions in mdoc --= Seq(
      "-Xfatal-warnings",
      "-Ywarn-unused-import",
      "-Ywarn-numeric-widen",
      "-Ywarn-dead-code",
      "-Ywarn-unused:imports",
      "-Xlint:-missing-interpolator,_"
    ),

    generateNetlifyToml := {
      var toml = s"""
           |[[redirects]]
           |  from = "/*"
           |  to = "/latest/:splat"
           |  force = false
           |  status = 302
           |""".stripMargin
      latestStableVersion(baseDirectory.value).foreach { v =>
        toml += s"""
           |[[redirects]]
           |  from = "/stable/*"
           |  to = "/${v.string}/:splat"
           |  force = false
           |  status = 200
        """.stripMargin
      }
      IO.write(target.value / "netlify.toml", toml)
    },

    sourceDirectory in Paradox := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).dependsOn(generateNetlifyToml).value,
    Paradox / paradoxMaterialTheme ~= {
      _.withRepository(uri("https://github.com/http4s/http4s-jdk-http-client"))
       .withLogoUri(uri("https://http4s.org/images/http4s-logo.svg"))
    },
    siteSubdirName in Paradox := {
      if (isSnapshot.value) "latest"
      else version.value
    },
    mappings in makeSite ++= Seq(
      target.value / "netlify.toml" -> "netlify.toml",
    ),

    includeFilter in ghpagesCleanSite :=
      new FileFilter{
        def accept(f: File) =
          f.toPath.startsWith((ghpagesRepository.value / (siteSubdirName in Paradox).value).toPath)
      } || "netlify.toml"
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

def latestStableVersion(base: File): Option[Version] =
  JGit(base).tags.collect {
    case ref if ref.getName.startsWith("refs/tags/v") =>
      Version(ref.getName.substring("refs/tags/v".size))
  }.foldLeft(Option.empty[Version]) {
    case (latest, Some(v)) if v.qualifier.isEmpty =>
      def patch(v: Version) = v.subversions.drop(1).headOption.getOrElse(0)
      import Ordering.Implicits._
      implicit val versionOrdering: Ordering[Version] =
        Ordering[Seq[Int]].on(v => v.major +: v.subversions)
      Ordering[Option[Version]].max(latest, Option(v))
    case (latest, _) => latest
  }

addCommandAlias("validate", ";test ;mimaBinaryIssueFilters ;scalafmtCheckAll ;docs/makeSite")
