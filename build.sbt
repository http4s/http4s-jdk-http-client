lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "http4s-jdk-http-client",
    libraryDependencies ++= coreDeps
  )

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(GhpagesPlugin, MdocPlugin, ParadoxMaterialThemePlugin, ParadoxSitePlugin)
  .dependsOn(core)
  .settings(commonSettings, docsSettings)

val catsV = "2.6.1"
val catsEffectV = "3.1.1"
val fs2V = "3.0.4"
val scodecV = "1.1.27"
val http4sV = "1.0.0-M23"
val reactiveStreamsV = "1.0.3"
val vaultV = "3.0.3"
val caseInsensitiveV = "1.1.4"

val munitV = "0.7.26"
val munitCatsEffectV = "1.0.5"
val javaWebsocketV = "1.5.2"

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
) ++ Seq(
  "org.http4s" %% "http4s-blaze-server" % http4sV,
  "org.http4s" %% "http4s-dsl" % http4sV,
  "org.java-websocket" % "Java-WebSocket" % javaWebsocketV,
  "org.scalameta" %% "munit" % munitV,
  "org.typelevel" %% "munit-cats-effect-3" % munitCatsEffectV
).map(_ % Test)

enablePlugins(SonatypeCiReleasePlugin)
inThisBuild(
  Seq(
    crossScalaVersions := Seq("2.12.14", "2.13.6", "3.0.0"),
    scalaVersion := (ThisBuild / crossScalaVersions).value.head,
    baseVersion := "0.6",
    homepage := Some(url("https://github.com/http4s/http4s-jdk-http-client")),
    licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/http4s/http4s-jdk-http-client"),
        "git@github.com:http4s/http4s-jdk-http-client.git"
      )
    ),
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
    ),
    githubWorkflowJavaVersions := Seq("adopt@1.11", "adopt@1.16"),
    githubWorkflowBuild := Seq(
      WorkflowStep
        .Sbt(List("scalafmtCheckAll", "scalafmtSbtCheck"), name = Some("Check formatting")),
      WorkflowStep.Sbt(List("headerCheckAll"), name = Some("Check headers")),
      WorkflowStep.Sbt(List("Test/compile"), name = Some("Compile")),
      WorkflowStep
        .Sbt(List("core/mimaReportBinaryIssues"), name = Some("Check binary compatibility")),
      WorkflowStep.Sbt(
        List("unusedCompileDependenciesTest", "undeclaredCompileDependenciesTest"),
        name = Some("Check unused and undeclared compile dependencies")
      ),
      WorkflowStep.Sbt(List("test"), name = Some("Run tests")),
      WorkflowStep.Sbt(List("doc"), name = Some("Build docs"))
    ),
    githubWorkflowPublishPostamble := Seq(
      WorkflowStep.Run(
        List("""
             |eval "$(ssh-agent -s)"
             |echo "$SSH_PRIVATE_KEY" | ssh-add -
             |git config --global user.name "GitHub Actions CI"
             |git config --global user.email "ghactions@invalid"
             |sbt ++${{ matrix.scala }} docs/ghpagesPushSite
             """.stripMargin),
        name = Some("Publish docs"),
        env = Map(
          "SSH_PRIVATE_KEY" -> "${{ secrets.SSH_PRIVATE_KEY }}",
          "SBT_GHPAGES_COMMIT_MESSAGE" -> "Updated site: sha=${{ github.sha }} build=${{ github.run_id }}"
        ),
        cond = Some("github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v')")
      )
    ),
    githubWorkflowPublishTargetBranches := Seq(
      RefPredicate.Equals(Ref.Branch("main")),
      RefPredicate.StartsWith(Ref.Branch("series/")),
      RefPredicate.StartsWith(Ref.Tag("v"))
    )
  )
)

lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("munit.Framework")
)

lazy val generateNetlifyToml = taskKey[Unit]("Generate netlify.toml")

lazy val docsSettings =
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
    Compile / paradox / sourceDirectory := mdocOut.value,
    makeSite := makeSite.dependsOn(mdoc.toTask("")).dependsOn(generateNetlifyToml).value,
    ghpagesPushSite := (ghpagesPushSite.dependsOn(makeSite)).value,
    Compile / paradoxMaterialTheme ~= {
      _.withRepository(uri("https://github.com/http4s/http4s-jdk-http-client"))
        .withLogoUri(uri("https://http4s.org/images/http4s-logo.svg"))
    },
    git.remoteRepo := "git@github.com:http4s/http4s-jdk-http-client.git",
    Paradox / siteSubdirName := {
      if (isSnapshot.value) "latest"
      else version.value
    },
    makeSite / mappings ++= Seq(
      target.value / "netlify.toml" -> "netlify.toml"
    ),
    ghpagesCleanSite / includeFilter :=
      new FileFilter {
        def accept(f: File) =
          f.toPath.startsWith(
            (ghpagesRepository.value / (Paradox / siteSubdirName).value).toPath
          )
      } || "netlify.toml"
  )

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
    .collect { case v @ VersionNumber(_, Seq(), Seq()) =>
      v
    } match {
    case Seq() => None
    case vs => Some(vs.maxBy(_.numbers.toIterable))
  }
