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

val catsV = "2.3.1"
val catsEffectV = "2.3.1"
val fs2V = "2.5.0"
val scodecV = "1.1.23"
val http4sV = "0.21.15"
val reactiveStreamsV = "1.0.3"
val vaultV = "2.0.0"

val specs2V = "4.10.6"
val catsEffectTestingV = "0.5.0"
val javaWebsocketV = "1.5.1"

val coreDeps = Seq(
  "org.typelevel" %% "cats-core" % catsV,
  "org.typelevel" %% "cats-effect" % catsEffectV,
  "org.typelevel" %% "cats-kernel" % catsV,
  "co.fs2" %% "fs2-core" % fs2V,
  "co.fs2" %% "fs2-reactive-streams" % fs2V,
  "org.http4s" %% "http4s-client" % http4sV,
  "org.http4s" %% "http4s-core" % http4sV,
  "org.reactivestreams" % "reactive-streams" % reactiveStreamsV,
  "org.scodec" %% "scodec-bits" % scodecV,
  "io.chrisdavenport" %% "vault" % vaultV
) ++ Seq(
  "com.codecommit" %% "cats-effect-testing-specs2" % catsEffectTestingV,
  "org.http4s" %% "http4s-blaze-server" % http4sV,
  "org.http4s" %% "http4s-dsl" % http4sV,
  "org.http4s" %% "http4s-testing" % http4sV,
  "org.java-websocket" % "Java-WebSocket" % javaWebsocketV,
  "org.specs2" %% "specs2-core" % specs2V,
  "org.specs2" %% "specs2-scalacheck" % specs2V
).map(_ % Test)

enablePlugins(SonatypeCiReleasePlugin)
inThisBuild(
  Seq(
    crossScalaVersions := Seq("2.12.13", "2.13.4"),
    scalaVersion := (ThisBuild / crossScalaVersions).value.head,
    baseVersion := "0.3",
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
    githubWorkflowArtifactUpload := false,
    githubWorkflowJavaVersions := Seq("adopt@1.11", "adopt@1.15"),
    githubWorkflowBuildMatrixFailFast := Some(false),
    githubWorkflowBuild := Seq(
      WorkflowStep
        .Sbt(List("scalafmtCheckAll", "scalafmtSbtCheck"), name = Some("Check formatting")),
      WorkflowStep.Sbt(List("headerCheckAll"), name = Some("Check headers")),
      WorkflowStep.Sbt(List("test:compile"), name = Some("Compile")),
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
        )
      )
    ),
    githubWorkflowPublishTargetBranches := Seq(
      RefPredicate.Equals(Ref.Branch("main")),
      RefPredicate.StartsWith(Ref.Tag("v"))
    )
  )
)

lazy val commonSettings = Seq(
  unmanagedSourceDirectories in Compile ++= {
    (unmanagedSourceDirectories in Compile).value.map { dir =>
      val sv = scalaVersion.value
      CrossVersion.partialVersion(sv) match {
        case Some((2, 13)) => file(dir.getPath ++ "-2.13")
        case _ => file(dir.getPath ++ "-2.11-2.12")
      }
    }
  }
)

lazy val generateNetlifyToml = taskKey[Unit]("Generate netlify.toml")

lazy val docsSettings = {
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
