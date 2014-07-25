package vim.scalacompletion.completion

import vim.scalacompletion._
import vim.scalacompletion.compiler.{MemberInfoExtractorFactory, MemberInfoExtractor, MemberInfo, Compiler}

import scala.reflect.internal.util.Position

trait CompletionHandlerFactory[T] {
  def create(compiler: Compiler): CompletionHandler[T]
}

class CompletionHandlerFactoryForMemberInfo(
      memberInfoExtractorFactory: MemberInfoExtractorFactory[MemberInfo]
    ) extends CompletionHandlerFactory[MemberInfo] {

  def create(compiler: Compiler): CompletionHandler[MemberInfo] =
    new CompletionHandler(new CompletionTypeDetector(compiler, compiler), compiler,
      memberInfoExtractorFactory.create(compiler), MemberInfoFilter,
      MemberRankCalculatorImpl, new PositionFactory)
}

class CompletionHandler[T](
                completionTypeDetector: CompletionTypeDetector,
                compiler: Compiler,
                extractor: MemberInfoExtractor[T],
                membersFilter: MemberFilter[T],
                memberRankCalculator: MemberRankCalculator[T],
                positionFactory: PositionFactory) extends WithLog {

  def complete(position: Position, prefix: Option[String] = None,
                                   maxResults: Option[Int] = None): Seq[T] = {
    val completionType = completionTypeDetector.detectAt(position)
    val members = completionType match {
      case CompletionType.Type(_) =>
        val positionOnWord = positionFactory.create(position.source, position.point - 1)
        compiler.typeCompletion(positionOnWord, extractor)
      case CompletionType.Scope(_) => compiler.scopeCompletion(position, extractor)
      case _ => Seq.empty
    }

    val membersFilterWithPrefix = (membersFilter.apply _).curried(prefix)
    val filteredMembers = members.filter(membersFilterWithPrefix)
    logg.debug(s"$completionType: Found ${members.length} members. ${filteredMembers.length} filtered.")

    val rankCalculatorWithPrefix = (memberRankCalculator.apply _).curried(prefix)
    val sortedByRank = filteredMembers.sortBy(-rankCalculatorWithPrefix(_))

    maxResults.map(sortedByRank.take) getOrElse sortedByRank
  }
}
