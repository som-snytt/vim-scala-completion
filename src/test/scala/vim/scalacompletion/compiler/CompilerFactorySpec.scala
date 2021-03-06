package vim.scalacompletion.compiler

import java.io.{File => JFile}

import org.specs2.mutable._

import scala.tools.nsc.reporters.StoreReporter

class CompilerFactorySpec extends Specification {
  val rtJarPath = "/rt.jar"
  val scalaLibJarPath = "/scala-library-2.11.1.jar"
  val scalazJarPath = "/scalaz-core_2.11-7.0.6.jar"

  args(skipAll = true)

  def jars = Seq(
    new JFile(getClass().getResource(rtJarPath).toURI),
    new JFile(getClass().getResource(scalaLibJarPath).toURI),
    new JFile(getClass().getResource(scalazJarPath).toURI)
  )

  val compilerFactory = new CompilerFactoryImpl()

  "compiler factory" should {
    "have expected classpath" in {
      val settings = compilerFactory.create(jars).settings

      settings.classpath.toString must contain(rtJarPath)
      settings.classpath.toString must contain(scalaLibJarPath)
      settings.classpath.toString must contain(scalazJarPath)
    }

    "have console reporter" in {
      val compiler = compilerFactory.create(jars)

      compiler.reporter must beAnInstanceOf[StoreReporter]
    }
  }
}
