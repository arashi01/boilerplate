/*
 * Copyright (c) 2025, 2026 Boilerplate contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package boilerplate.effect

import scala.annotation.tailrec
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type
import scala.quoted.Varargs
import scala.quoted.quotes

/** A recipe for one service: how to build an `A` as an [[EffResource]], given the services it
  * depends on. Inert until [[Provider$.wire]] assembles a graph of them.
  *
  * `R` is the tuple of declared dependencies, read off the type by `wire` and never written by hand -
  * construct through the companion's `apply`, which takes either a dependency-free resource or a
  * function of up to eight dependencies. Publishing a provider at a wider service type is a
  * covariant ascription, `A` being covariant.
  *
  * Refer to [[boilerplate.effect.Provider$ Provider]] for construction and wiring.
  */
final class Provider[R <: Tuple, +E <: Throwable, +A] private (val build: R => EffResource[E, A])

/** Provides construction and compile-time wiring for [[boilerplate.effect.Provider Provider]]. */
object Provider:
  /** A provider with no dependencies. */
  def apply[E <: Throwable, A](resource: EffResource[E, A]): Provider[EmptyTuple, E, A] =
    new Provider(_ => resource)

  /** A provider of one dependency. */
  def apply[D1, E <: Throwable, A](build: D1 => EffResource[E, A]): Provider[Tuple1[D1], E, A] =
    new Provider(deps => build(deps._1))

  /** A provider of two dependencies. */
  def apply[D1, D2, E <: Throwable, A](build: (D1, D2) => EffResource[E, A]): Provider[(D1, D2), E, A] =
    new Provider(deps => build(deps._1, deps._2))

  /** A provider of three dependencies. */
  def apply[D1, D2, D3, E <: Throwable, A](build: (D1, D2, D3) => EffResource[E, A]): Provider[(D1, D2, D3), E, A] =
    new Provider(deps => build(deps._1, deps._2, deps._3))

  /** A provider of four dependencies. */
  def apply[D1, D2, D3, D4, E <: Throwable, A](
    build: (D1, D2, D3, D4) => EffResource[E, A]
  ): Provider[(D1, D2, D3, D4), E, A] =
    new Provider(deps => build(deps._1, deps._2, deps._3, deps._4))

  /** A provider of five dependencies. */
  def apply[D1, D2, D3, D4, D5, E <: Throwable, A](
    build: (D1, D2, D3, D4, D5) => EffResource[E, A]
  ): Provider[(D1, D2, D3, D4, D5), E, A] =
    new Provider(deps => build(deps._1, deps._2, deps._3, deps._4, deps._5))

  /** A provider of six dependencies. */
  def apply[D1, D2, D3, D4, D5, D6, E <: Throwable, A](
    build: (D1, D2, D3, D4, D5, D6) => EffResource[E, A]
  ): Provider[(D1, D2, D3, D4, D5, D6), E, A] =
    new Provider(deps => build(deps._1, deps._2, deps._3, deps._4, deps._5, deps._6))

  /** A provider of seven dependencies. */
  def apply[D1, D2, D3, D4, D5, D6, D7, E <: Throwable, A](
    build: (D1, D2, D3, D4, D5, D6, D7) => EffResource[E, A]
  ): Provider[(D1, D2, D3, D4, D5, D6, D7), E, A] =
    new Provider(deps => build(deps._1, deps._2, deps._3, deps._4, deps._5, deps._6, deps._7))

  /** A provider of eight dependencies. */
  def apply[D1, D2, D3, D4, D5, D6, D7, D8, E <: Throwable, A](
    build: (D1, D2, D3, D4, D5, D6, D7, D8) => EffResource[E, A]
  ): Provider[(D1, D2, D3, D4, D5, D6, D7, D8), E, A] =
    new Provider(deps => build(deps._1, deps._2, deps._3, deps._4, deps._5, deps._6, deps._7, deps._8))

  /** Assembles `providers` into the [[EffResource]] that builds `Target`, matching each declared
    * dependency to the provider whose output type is exactly that type.
    *
    * Argument order is irrelevant, each service is constructed once however many others depend on
    * it, finalisers run in reverse construction order, and the result's error channel is the union
    * of the providers' own. All of that is decided here, at compile time: the expansion is the
    * nested `EffResource` composition, with no registry and no lookup at run time.
    *
    * The set must be exact. A dependency with no provider, a service provided twice, a provider
    * unreachable from `Target`, a provider whose dependencies are not concrete types, and a
    * dependency cycle are each a compile error, reported together.
    *
    * {{{
    * val server: EffResource[ConfigError | DbError, Server] =
    *   Provider.wire[Server](serverProvider, dbProvider, cacheProvider, configProvider)
    * }}}
    */
  transparent inline def wire[Target](inline providers: Provider[?, ?, ?]*): EffResource[Throwable, Target] =
    ${ wireImpl[Target]('providers) }

  private def wireImpl[Target: Type](providers: Expr[Seq[Provider[?, ?, ?]]])(using Quotes): Expr[EffResource[Throwable, Target]] =
    import quotes.reflect.*

    val target = TypeRepr.of[Target].dealias
    def name(tpe: TypeRepr): String = tpe.show(using Printer.TypeReprShortCode)
    def fail(findings: List[String]): Nothing =
      report.errorAndAbort(s"Provider.wire[${name(target)}] failed:\n${findings.mkString("\n")}")

    val arguments = providers match
      case Varargs(exprs) => exprs.toList
      case _              => report.errorAndAbort(s"Provider.wire[${name(target)}] needs its providers written out at the call site.")

    // `position` is the 1-based argument index every diagnostic identifies a provider by.
    final case class Node(position: Int, inputs: List[TypeRepr], error: TypeRepr, output: TypeRepr, provider: Expr[Provider[?, ?, ?]])

    val emptyTuple = TypeRepr.of[EmptyTuple]
    def dependencies(tpe: TypeRepr): Option[List[TypeRepr]] =
      val widened = tpe.dealias
      if widened =:= emptyTuple then Some(Nil)
      else
        widened match
          case AppliedType(tycon, List(head, tail)) if tycon.typeSymbol.name == "*:" =>
            dependencies(tail).map(head.dealias :: _)
          case AppliedType(tycon, args) if tycon.typeSymbol.fullName.startsWith("scala.Tuple") =>
            Some(args.map(_.dealias))
          case _ => None

    val providerSymbol = TypeRepr.of[Provider[EmptyTuple, Nothing, Any]].typeSymbol
    val parsed = arguments.zipWithIndex.map { (expr, index) =>
      expr.asTerm.tpe.widen.dealias.baseType(providerSymbol) match
        case AppliedType(_, List(r, e, a)) =>
          (index + 1, dependencies(r), e.dealias, a.dealias, expr)
        case other =>
          report.errorAndAbort(s"Provider.wire[${name(target)}]: argument ${index + 1} is not a Provider (${other.show}).")
    }

    val abstractFindings = parsed.collect { case (position, None, _, _, _) =>
      s"  - abstract: argument $position has non-concrete dependencies (R is abstract) - wire requires concrete provider types"
    }
    val nodes = parsed.collect { case (position, Some(inputs), error, output, expr) => Node(position, inputs, error, output, expr) }

    def providerOf(tpe: TypeRepr): Option[Node] = nodes.find(_.output =:= tpe)

    val duplicateFindings =
      nodes
        .groupBy(n => n.output.show)
        .toList
        .sortBy((key, _) => key)
        .collect {
          case (_, group) if group.length > 1 =>
            val positions = group.map(_.position).sorted
            s"  - duplicate: ${name(group.head.output)} is provided by arguments ${positions.mkString(" and ")}" +
              " - remove one, or ascribe their outputs to distinct services"
        }

    // Requirements are matched on the EXACT declared type; a provider published at a subtype is
    // therefore not a match, and the note names the ascription that would make it one.
    def subtypeNotes(required: TypeRepr): List[String] =
      nodes.filter(n => n.output <:< required && !(n.output =:= required)).map { n =>
        s"      note: argument ${n.position} provides ${name(n.output)} <: ${name(required)};" +
          s" ascribe it to Provider[?, ?, ${name(required)}] to supply it"
      }

    val requirements = nodes.flatMap(n => n.inputs.map(i => (i, n)))
    val missingFindings =
      requirements
        .filter((required, _) => providerOf(required).isEmpty)
        .groupBy((required, _) => required.show)
        .toList
        .sortBy((key, _) => key)
        .flatMap { (_, group) =>
          val required = group.head._1
          val requiredBy = group.map((_, n) => s"${name(n.output)} (argument ${n.position})").mkString(", ")
          s"  - missing: no provider for ${name(required)}" :: s"      required by: $requiredBy" :: subtypeNotes(required)
        }

    val targetFindings =
      if providerOf(target).isDefined then Nil
      else s"  - missing: no provider for ${name(target)} - the wire target" :: subtypeNotes(target)

    // Reachability is only meaningful once the target has a provider; without one every node would
    // read as unused, burying the finding that matters.
    val unusedFindings =
      providerOf(target) match
        case None       => Nil
        case Some(root) =>
          @tailrec def reach(frontier: List[Node], seen: List[Node]): List[Node] =
            frontier match
              case Nil       => seen
              case n :: rest =>
                val next = n.inputs.flatMap(providerOf).filterNot(d => seen.exists(_.position == d.position))
                reach(next ++ rest, seen ++ next)
          val reachable = reach(List(root), List(root))
          nodes.filterNot(n => reachable.exists(_.position == n.position)).map { n =>
            s"  - unused: argument ${n.position} (provides ${name(n.output)}) is not reachable from ${name(target)} - remove it"
          }

    val findings = missingFindings ++ targetFindings ++ duplicateFindings ++ unusedFindings ++ abstractFindings
    if findings.nonEmpty then fail(findings)

    // Kahn's algorithm; anything left when no node is ready sits on a cycle.
    @tailrec def sort(pending: List[Node], done: List[Node]): List[Node] =
      if pending.isEmpty then done
      else
        pending.partition(n => n.inputs.forall(i => done.exists(_.output =:= i))) match
          case (Nil, stuck)  => fail(List(s"  - cycle: ${cyclePath(stuck)}"))
          case (ready, rest) => sort(rest, done ++ ready)

    def cyclePath(stuck: List[Node]): String =
      @tailrec def walk(current: Node, path: List[Node]): List[Node] =
        if path.exists(_.position == current.position) then path.dropWhile(_.position != current.position) :+ current
        else
          current.inputs.flatMap(i => stuck.filter(_.output =:= i)).headOption match
            case Some(next) => walk(next, path :+ current)
            case None       => path :+ current
      walk(stuck.head, Nil).map(n => name(n.output)).mkString(" -> ")

    val ordered = sort(nodes, Nil)

    val nothing = TypeRepr.of[Nothing]
    val errors = ordered.map(_.error).filterNot(_ =:= nothing).foldLeft(List.empty[TypeRepr]) { (acc, e) =>
      if acc.exists(_ =:= e) then acc else acc :+ e
    }
    val errorUnion = errors.reduceOption(OrType(_, _)).getOrElse(nothing)

    def tuple(types: List[TypeRepr], values: List[Term]): Term =
      if types.isEmpty then Ref(Symbol.requiredModule("scala.EmptyTuple"))
      else
        val module = Ref(Symbol.requiredModule(s"scala.Tuple${types.length}"))
        Apply(TypeApply(Select.unique(module, "apply"), types.map(t => Inferred(t))), values)

    val effResource = Ref(Symbol.requiredModule("boilerplate.effect.EffResource"))
    val resultType = TypeRepr.of[EffResource[Nothing, Any]] match
      case AppliedType(tycon, _) => AppliedType(tycon, List(errorUnion, target))
      case other                 => report.errorAndAbort(s"Provider.wire: unexpected EffResource shape ${other.show}")

    // The composition is emitted in `EffResource` terms throughout: the outermost `flatMap` is
    // applied at the error union, so the tree the transparent inline surfaces already carries the
    // precise result type without an ascription.
    def emit(remaining: List[Node], bound: List[(TypeRepr, Term)]): Term =
      remaining match
        case Nil =>
          val value = bound.collectFirst { case (t, v) if t =:= target => v }.get
          Apply(TypeApply(Select.unique(effResource, "pure"), List(Inferred(target))), List(value))
        case node :: rest =>
          val values = node.inputs.map(i => bound.collectFirst { case (t, v) if t =:= i => v }.get)
          val resource = Apply(Select.unique(Select.unique(node.provider.asTerm, "build"), "apply"), List(tuple(node.inputs, values)))
          val continuation = Lambda(
            Symbol.spliceOwner,
            MethodType(List("value"))(_ => List(node.output), _ => resultType),
            // Each node is bound exactly once and its value reused, so a service two others depend
            // on is constructed once - the diamond shares one instance by construction.
            (owner, args) => emit(rest, bound :+ (node.output, args.head.asExprOf[Any].asTerm)).changeOwner(owner)
          )
          // An extension method keeps two type-parameter groups: [E, A](self)[E2, B](f).
          Apply(
            TypeApply(
              Apply(
                TypeApply(Select.unique(effResource, "flatMap"), List(Inferred(node.error), Inferred(node.output))),
                List(resource)
              ),
              List(Inferred(errorUnion), Inferred(target))
            ),
            List(continuation)
          )

    emit(ordered, Nil).asExprOf[EffResource[Throwable, Target]]
  end wireImpl
end Provider
