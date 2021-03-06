package vim.scalacompletion.imports

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz._
import scalaz.std.map._
import scalaz.std.list._
import scalaz.std.set._
import scalaz.syntax.semigroup._
import scalaz.concurrent.Actor

trait IndexBuilder extends FqcnCollector {
  lazy val buildIndex: Set[Path] => Future[Index] = paths => {
    val indexPromise = Promise[Index]
    var index = Index()
    var pathsLeft = paths
    val collectionProcessManager = Actor[(Index, Path)]({
      case (indexPortion, path) =>
        index = index merge indexPortion
        pathsLeft = pathsLeft - path
        if (pathsLeft.isEmpty) {
          indexPromise success index
        }
    })
    paths.foreach { path =>
      Future {
        try {
          val stream = collectFqcnsFrom(path)
          collectionProcessManager ! (Index.fromSet(stream.toSet), path)
        } catch {
          case ex: Exception => indexPromise failure ex
        }
      }
    }
    indexPromise.future
  }
}

object Index {
  def fromSet(fqcns: Set[FQCN]): Index = Index(fqcns.groupBy(_.className).mapValues(_.map(_.scope)))
}

case class SourceFQCN(scope: String, className: String, file: String)

object SourceIndex {
  def fromSet(fqcns: Set[SourceFQCN]): SourceIndex = SourceIndex(fqcns)
}

case class SourceIndex(private val index: Set[SourceFQCN] = Set.empty) {
  def lookup(className: String): Set[String] =
    index.filter(_.className == className).map(_.scope)

  def removeSource(fileName: String): SourceIndex =
    copy(index = index.filterNot(_.file == fileName))

  def merge(other: SourceIndex): SourceIndex = copy(index = index |+| other.index)
}

case class Index(private val index: Map[String, Set[String]] = Map.empty) {
  def lookup(className: String): Set[String] =
    index.getOrElse(className, Set.empty)

  def merge(other: Index): Index = copy(index = index |+| other.index)
}

case class FQCN(scope: String, className: String)

trait FqcnCollection {
  protected lazy val createFilePathStreamFromJar: Path => Stream[String] =
    JarStream(_).filter(!_.isDirectory).map(_.getName)

  protected lazy val createFilePathStreamFromDir: Path => Stream[String] = path => {
    lazy val basePathAbsolute = path.toAbsolutePath.toString
    RecursiveDirectoryStream(path)
      .filterNot(p => Files.isDirectory(p))
      // remove base directory and separator
      .map(_.toAbsolutePath.toString.drop(basePathAbsolute.length + 1))
  }

  protected lazy val fqcnStreamFromFileNameStream: Stream[String] => Stream[FQCN] =
   _.filter(filterClassFiles)
    .map(filePathToFqcn)
    .map(fqcnStringToObject)

  protected lazy val filterClassFiles: String => Boolean = fileName => {
    fileName.endsWith(".class") &&
    // anonymous classes
    !fileName.contains("$$") &&
    // scala 'object'
    !fileName.endsWith("$.class") &&
    // some weird duplicate
    !fileName.endsWith("$class.class") &&
    // scala package class
    !fileName.endsWith("package.class")
  }

  protected lazy val filePathToFqcn: String => String = path => {
    // replace path separator with dot
    val replacedSeparator = path.replaceAll("/", "\\.")
    replacedSeparator
      // trim file extension
      .substring(0, replacedSeparator.lastIndexOf('.'))
      // replace nested classes separator with dot
      .replaceAll("\\$", "\\.")
  }

  protected lazy val fqcnStringToObject: String => FQCN = fqcn => {
    fqcn.splitAt(fqcn.lastIndexOf('.')) match {
      case (scope, className) =>
        // remove leading dot from class name if class is in package
        FQCN(scope, className.dropWhile(_ == '.'))
    }
  }
}

trait FqcnCollector extends FqcnCollection {
  lazy val collectFqcnsFrom: Path => Stream[FQCN] = path =>
    if (Files.isDirectory(path)) {
      collectFqcnsFromDir(path)
    } else {
      collectFqcnsFromJar(path)
    }

  lazy val collectFqcnsFromJar: Path => Stream[FQCN] = path =>
    fqcnStreamFromFileNameStream(createFilePathStreamFromJar(path))

  lazy val collectFqcnsFromDir: Path => Stream[FQCN] = path =>
    fqcnStreamFromFileNameStream(createFilePathStreamFromDir(path))
}

