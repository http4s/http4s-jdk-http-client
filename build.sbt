lazy val `http4s-jdk-http-client` = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .settings(commonSettings, skipOnPublishSettings)
  .settings(
    crossScalaVersions := Nil
  )
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings, mimaSettings)
  .settings(
    name := "http4s-jdk-http-client",
    libraryDependencies ++= coreDeps
  )

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(GhpagesPlugin, MdocPlugin, ParadoxMaterialThemePlugin, ParadoxSitePlugin)
  .dependsOn(core)
  .settings(commonSettings, skipOnPublishSettings, docsSettings)

val catsV = "2.1.1"
val catsEffectV = "2.1.3"
val fs2V = "2.3.0"
val scodecV = "1.1.14"
val http4sV = "0.21.3"
val reactiveStreamsV = "1.0.3"
val vaultV = "2.0.0"

val specs2V = "4.9.3"
val catsEffectTestingV = "0.4.0"
val javaWebsocketV = "1.4.1"

val kindProjectorV = "0.10.3"
val betterMonadicForV = "0.3.1"

lazy val scalaVersions =
  upickle.default.read[List[String]](new File("scalaVersions.json"))

// format: off
val coreDeps = Seq(
  "org.typelevel"       %% "cats-core"                  % catsV,
  "org.typelevel"       %% "cats-effect"                % catsEffectV,
  "org.typelevel"       %% "cats-kernel"                % catsV,
  "co.fs2"              %% "fs2-core"                   % fs2V,
  "co.fs2"              %% "fs2-reactive-streams"       % fs2V,
  "org.http4s"          %% "http4s-client"              % http4sV,
  "org.http4s"          %% "http4s-core"                % http4sV,
  "org.reactivestreams" %  "reactive-streams"           % reactiveStreamsV,
  "org.scodec"          %% "scodec-bits"                % scodecV,
  "io.chrisdavenport"   %% "vault"                      % vaultV,
) ++ Seq(
  "com.codecommit"      %% "cats-effect-testing-specs2" % catsEffectTestingV,
  "org.http4s"          %% "http4s-blaze-server"        % http4sV,
  "org.http4s"          %% "http4s-dsl"                 % http4sV,
  "org.http4s"          %% "http4s-testing"             % http4sV,
  "org.java-websocket"  %  "Java-WebSocket"             % javaWebsocketV,
  "org.specs2"          %% "specs2-core"                % specs2V,
  "org.specs2"          %% "specs2-scalacheck"          % specs2V,
).map(_ % Test)
// format: on

// General Settings
lazy val commonSettings = Seq(
  organization := "org.http4s",
  scalaVersion := scalaVersions.head,
  crossScalaVersions := scalaVersions,
  scalacOptions += "-Yrangepos",
  scalacOptions in (Compile, doc) ++= Seq(
    "-groups",
    "-sourcepath",
    (baseDirectory in LocalRootProject).value.getAbsolutePath,
    "-doc-source-url",
    "https://github.com/http4s/http4s-jdk-http-client/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),
  addCompilerPlugin(
    ("org.typelevel" % "kind-projector" % kindProjectorV).cross(CrossVersion.binary)
  ),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForV),
  unmanagedSourceDirectories in Compile ++= {
    (unmanagedSourceDirectories in Compile).value.map { dir =>
      val sv = scalaVersion.value
      CrossVersion.partialVersion(sv) match {
        case Some((2, 13)) => file(dir.getPath ++ "-2.13")
        case _ => file(dir.getPath ++ "-2.11-2.12")
      }
    }
  },
  homepage := Some(url("https://github.com/http4s/http4s-jdk-http-client")),
  licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
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
)

lazy val mimaSettings = {
  def semverBinCompatVersions(major: Int, minor: Int, patch: Int): Set[(Int, Int, Int)] = {
    val majorVersions: List[Int] =
      if (major == 0 && minor == 0) List.empty[Int] // If 0.0.x do not check MiMa
      else List(major)
    val minorVersions: List[Int] =
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

  def mimaVersions(version: String): Set[String] =
    version match {
      case VersionNumber(Seq(major, minor, patch), _, _) =>
        semverBinCompatVersions(major.toInt, minor.toInt, patch.toInt)
          .map { case (maj, min, pat) => maj.toString + "." + min.toString + "." + pat.toString }
      case _ =>
        Set.empty[String]
    }
  // Safety Net For Exclusions
  lazy val excludedVersions: Set[String] = Set()

  // Safety Net for Inclusions
  lazy val extraVersions: Set[String] = Set()

  Seq(
    mimaFailOnNoPrevious := false,
    mimaFailOnProblem := mimaVersions(version.value).toList.nonEmpty,
    mimaPreviousArtifacts := (mimaVersions(version.value) ++ extraVersions)
      .diff(excludedVersions)
      .map { v =>
        val moduleN = moduleName.value + "_" + scalaBinaryVersion.value.toString
        organization.value % moduleN % v
      },
    mimaBinaryIssueFilters ++= {
      Seq()
    }
  )
}

lazy val generateNetlifyToml = taskKey[Unit]("Generate netlify.toml")

lazy val docsSettings = {
  ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox) ++
    Seq(
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
        val toml = latestStableVersion(baseDirectory.value)
          .map(v => s"""
           |[[redirects]]
           |  from = "/stable/*"
           |  to = "/$v/:splat"
           |  force = false
           |  status = 200
           |""".stripMargin)
          .getOrElse("") + s"""
           |[[redirects]]
           |  from = "/*"
           |  to = "/latest/:splat"
           |  force = false
           |  status = 302
           |""".stripMargin
        IO.write(target.value / "netlify.toml", toml)
      },
      sourceDirectory in Paradox := mdocOut.value,
      makeSite := makeSite.dependsOn(mdoc.toTask("")).dependsOn(generateNetlifyToml).value,
      Paradox / paradoxMaterialTheme ~= {
        _.withRepository(uri("https://github.com/http4s/http4s-jdk-http-client"))
          .withLogoUri(uri("https://http4s.org/images/http4s-logo.svg"))
      },
      git.remoteRepo := "git@github.com:http4s/http4s-jdk-http-client.git",
      siteSubdirName in Paradox := {
        if (isSnapshot.value) "latest"
        else version.value
      },
      mappings in makeSite ++= Seq(
        target.value / "netlify.toml" -> "netlify.toml"
      ),
      includeFilter in ghpagesCleanSite :=
        new FileFilter {
          def accept(f: File) =
            f.toPath.startsWith(
              (ghpagesRepository.value / (siteSubdirName in Paradox).value).toPath
            )
        } || "netlify.toml"
    )
}

lazy val skipOnPublishSettings = Seq(skip in publish := true)

def binaryVersion(version: String) =
  version match {
    case VersionNumber(Seq(0, minor, _*), _, _) => s"0.$minor"
    case VersionNumber(Seq(major, _, _*), _, _) if major > 0 => major.toString
  }

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

def latestStableVersion(base: File): Option[VersionNumber] =
  com.typesafe.sbt.git
    .JGit(base)
    .tags
    .flatMap { ref =>
      val name = ref.getName
      val tagName = name.stripPrefix("refs/tags/v")
      if (name != tagName) Some(VersionNumber(tagName)) else None
    }
    .collect {
      case v @ VersionNumber(_, Seq(), Seq()) => v
    } match {
    case Seq() => None
    case vs => Some(vs.maxBy(_.numbers.toIterable))
  }

addCommandAlias("validate", ";test ;mimaBinaryIssueFilters ;scalafmtCheckAll ;docs/makeSite")
