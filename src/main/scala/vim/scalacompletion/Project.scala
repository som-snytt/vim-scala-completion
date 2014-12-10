package vim.scalacompletion

import java.io.{File => JFile}
import java.nio.file.Paths
import akka.actor.{Actor, ActorRef, ActorLogging}
import akka.pattern.ask
import akka.util.Timeout
import vim.scalacompletion.compiler._
import vim.scalacompletion.imports.Index
import vim.scalacompletion.imports.IndexBuilder
import vim.scalacompletion.completion._
import vim.scalacompletion.filesystem.{ScalaSourcesFinder, WatchService,
                                       SourcesWatchActor, SourcesWatchActorFactory}
import scala.concurrent.duration._
import scala.concurrent.Future
import collection.JavaConversions._

object Project {
  case class CompleteAt(name: String, path: String,
    lineIdx: Int, columnIdx: Int, prefix: Option[String])
  case class LookupPackagesForClass(className: String)
  case class CompletionResult[T](members: Seq[T])

  case class ReloadSources(sources: Seq[JFile])
  case class RemoveSources(sources: Seq[JFile])
  case class Init(configPath: String)
  case object Initialized
}

trait Project[MemberInfoType] extends Actor with ActorLogging {
  import Project._

  val configLoader: ConfigLoader
  val sourceFileFactory: SourceFileFactory
  val scalaSourcesFinder: ScalaSourcesFinder
  val sourcesWatchActorFactory: SourcesWatchActorFactory
  val compilerFactory: CompilerFactory
  val completionHandlerFactory: CompletionHandlerFactory[MemberInfoType]
  val indexBuilder: IndexBuilder

  var compiler: Compiler = _
  var sourcesWatcher: ActorRef = _
  var completionHandler: CompletionHandler[MemberInfoType] = _
  var importsIndex: Future[Index] = _

  //TODO: move timeouts to one place
  implicit val timeout = Timeout(25.seconds)
  implicit val ec = context.dispatcher

  def receive = {
    case Init(configPath) => init(configPath)
    case CompleteAt(name, path, lineIdx, columnIdx, prefix) =>
      completeAt(name, path, lineIdx, columnIdx, prefix)
    case ReloadSources(sources) => reloadSources(sources)
    case RemoveSources(sources) => removeSources(sources)
    case LookupPackagesForClass(className) =>
      if (importsIndex.isCompleted) {
        val originalSender = sender
        importsIndex.map { index =>
          originalSender ! index.lookup(className)
        }
      } else {
        sender ! Set.empty
      }
  }

  override def postRestart(ex: Throwable) = {
    context.parent ! Projects.Restarted(self)
    log.warning("Project restarted after failure", ex)
  }

  def completeAt(name: String, path: String, lineIdx: Int, columnIdx: Int, prefix: Option[String]) = {
    log.debug(s"Completion requested at ($lineIdx, columnIdx) in: $name")
    val source = sourceFileFactory.createSourceFile(name, path)
    compiler.reloadSources(List(source))

    val lineOffset = source.lineToOffset(lineIdx)
    val position = source.position(lineOffset + columnIdx)
    val members = completionHandler.complete(position, prefix, Some(50))
    sender ! CompletionResult(members)
  }

  def init(configPath: String) = {
    val config = configLoader.load(configPath) // TODO: not exists?
    val classpath = config.getStringList("vim.scala-completion.classpath")
    val sourcesDirs = config.getStringList("vim.scala-completion.src-directories")

    compiler = compilerFactory.create(classpath.map(new JFile(_)))
    sourcesWatcher = sourcesWatchActorFactory.create(self)
    completionHandler = completionHandlerFactory.create(compiler)
    reloadAllSourcesInDirs(sourcesDirs)

    //TODO: find better place
    //TODO: get from project?
    //TODO: include other libraries from boot classpath?
    def locateRtJar = {
      System.getProperty("sun.boot.class.path")
        .split(":")
        .find(_.endsWith("rt.jar"))
        .get
    }

    importsIndex = indexBuilder.buildIndex((classpath :+ locateRtJar).map(p => Paths.get(p)).toSet)

    importsIndex.foreach { _ =>
      log.info("Index created")
    }

    val originalSender = sender
    (sourcesWatcher ? SourcesWatchActor.WatchDirs(sourcesDirs)).foreach { _ =>
      originalSender ! Initialized
    }
  }

  def reloadSources(sourcesJFiles: Seq[JFile]) = {
    val sources = filesToSourceFiles(sourcesJFiles)
    compiler.reloadSources(sources)
  }

  def removeSources(sourcesJFiles: Seq[JFile]) = {
    val sources = filesToSourceFiles(sourcesJFiles)
    compiler.removeSources(sources)
  }

  private def reloadAllSourcesInDirs(dirs: Seq[String]) = {
    val sourcesJFiles = scalaSourcesFinder.findIn(dirs.map(new JFile(_)).toList)
    reloadSources(sourcesJFiles)
  }

  private def filesToSourceFiles(sourcesJFiles: Seq[JFile]) = {
    sourcesJFiles.map { file =>
      val canonicalPath = file.getCanonicalPath
      sourceFileFactory.createSourceFile(canonicalPath)
    }.toList
  }
}
