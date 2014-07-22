package vim.scalacompletion

import akka.actor.{Actor, ActorRef, ActorContext, Props}
import java.nio.file.Paths

class SourcesWatchActorFactory(context: ActorContext) {
  def create(facade: ActorRef, watchService: WatchService) = {
    context.actorOf(Props(new SourcesWatchActor(facade, watchService, new ScalaSourcesFinder)))
  }
}

object SourcesWatchActor {
  case class WatchDirs(dirs: Seq[String])
}

class SourcesWatchActor(facadeActor: ActorRef,
                        watchService: WatchService,
                        scalaSourcesFinder: ScalaSourcesFinder) extends Actor {
  import SourcesWatchActor._
  import FileSystemEvents._
  import FacadeActor._

  watchService.addObserver(self)

  def receive = {
    case WatchDirs(dirs) =>
      dirs.foreach { dir =>
        val path = Paths.get(dir)
        watchService.watchRecursively(path)
      }
    case Created(file) if scalaSourcesFinder.isScalaSource(file) =>
      facadeActor ! ReloadSources(Seq(file))
    case Modifyed(file) if scalaSourcesFinder.isScalaSource(file) =>
      facadeActor ! ReloadSources(Seq(file))
    case Deleted(file) if scalaSourcesFinder.isScalaSource(file) =>
      facadeActor ! RemoveSources(Seq(file))
  }
}
