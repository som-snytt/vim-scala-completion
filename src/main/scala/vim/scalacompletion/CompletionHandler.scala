package vim.scalacompletion

import scala.reflect.internal.util.Position

trait CompletionHandlerFactory[T] {
  def create(compiler: Compiler): CompletionHandler[T]
}

class CompletionHandlerFactoryForMemberInfo(
      memberInfoExtractorFactory: MemberInfoExtractorFactory[MemberInfo]
    ) extends CompletionHandlerFactory[MemberInfo] {

  def create(compiler: Compiler): CompletionHandler[MemberInfo] =
    new CompletionHandler(new CompletionTypeDetector, compiler,
      memberInfoExtractorFactory.create(compiler), MemberInfoFilter,
      MemberRankCalculatorImpl)
}

class CompletionHandler[T](
                completionTypeDetector: CompletionTypeDetector,
                compiler: Compiler,
                extractor: MemberInfoExtractor[T],
                membersFilter: MemberFilter[T],
                memberRankCalculator: MemberRankCalculator[T]) extends WithLog {

  def complete(position: Position, maxResults: Option[Int] = None): Seq[T] = {
    val completionType = completionTypeDetector.detect(position)
    val members = completionType match {
      case CompletionType.Type => compiler.typeCompletion(position, extractor)
      case CompletionType.Scope => compiler.scopeCompletion(position, extractor)
      case _ => Seq.empty
    }

    val membersFilterWithPrefix = (membersFilter.apply _).curried(None)
    val filteredMembers = members.filter(membersFilterWithPrefix)
    logg.debug(s"$completionType: Found ${members.length} members. ${filteredMembers.length} filtered.")

    val rankCalculatorWithPrefix = (memberRankCalculator.apply _).curried(None)
    val sortedByRank = filteredMembers.sortBy(-rankCalculatorWithPrefix(_))

    maxResults.map(sortedByRank.take(_)) getOrElse sortedByRank
  }
}
