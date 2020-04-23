import cats.effect._
import java.nio.file.{Files, Paths}
import org.dhallj.core.Expr
import org.dhallj.core.converters.JsonConverter
import org.dhallj.imports.syntax._
import org.dhallj.parser.DhallParser
import org.dhallj.yaml.YamlConverter
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import sbt.{IO => _, _}
import scala.concurrent.ExecutionContext
import upickle.default.{ReadWriter, macroRW}

object Dhall {

  lazy val convertDhall = taskKey[Unit]("Generate YAML/JSON from Dhall.")

  private lazy val http = {
    implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
    JdkHttpClient.simple[IO].unsafeRunSync()
  }

  private def loadDhall(expr: String): Expr = {
    implicit val c: Client[IO] = http
    DhallParser
      .parse(expr)
      .normalize()
      .resolveImports[IO]
      .unsafeRunSync()
      .normalize()
  }

  val convertDhallTask = convertDhall := {
    val baseDir = (Keys.baseDirectory in LocalRootProject).value.absolutePath
    def convertYaml(from: String, to: String): Unit = {
      val dhall = loadDhall(s"$baseDir/dhall/$from.dhall")
      val yaml = YamlConverter.toYamlString(dhall)
      Files.writeString(Paths.get(s"$baseDir/$to"), yaml)
    }
    List("ci", "release", "dhall").foreach { file =>
      convertYaml(file, s".github/workflows/$file.yml")
    }
    convertYaml("mergify", s".mergify.yml")
  }

  case class ScalaVersions(default: String, all: List[String])
  object ScalaVersions { implicit val rw: ReadWriter[ScalaVersions] = macroRW }

  val scalaVersions = settingKey[ScalaVersions]("Read the Scala versions via Dhall")

  val scalaVersionsImpl = scalaVersions := {
    val baseDir = (Keys.baseDirectory in LocalRootProject).value.absolutePath
    val dhall = loadDhall(s"$baseDir/dhall/scalaVersions.dhall")
    val json = JsonConverter.toCompactString(dhall)
    upickle.default.read[ScalaVersions](json)
  }

}
