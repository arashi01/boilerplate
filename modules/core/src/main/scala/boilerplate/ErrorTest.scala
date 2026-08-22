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
package boilerplate

import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type
import scala.quoted.quotes

/** Runtime evidence that a `Throwable` is an `E`: `test` is the allocation-free form, `unapply` the
  * extractor. Instances are derived by the companion's macro for any concrete channel - a class, a
  * stable singleton, or a union of them.
  *
  * Deliberately not a `scala.reflect.TypeTest`: the compiler casts a `TypeTest` extractor's bound
  * result to one arm of a union, which throws `ClassCastException` where an arm is an `object`
  * (scala/scala3#22950, open at 3.9.0). This extractor binds the union itself, so a payload-free
  * error arm needs no class of its own.
  *
  * Refer to [[boilerplate.ErrorTest$ ErrorTest]] for the derivation and the infallible-channel
  * instance.
  */
trait ErrorTest[E <: Throwable]:
  def test(t: Throwable): Boolean

  final def unapply(t: Throwable): Option[E] =
    if test(t) then Some(t.asInstanceOf[E]) else None // scalafix:ok DisableSyntax.asInstanceOf

/** Provides the derivation and the infallible-channel instance for
  * [[boilerplate.ErrorTest ErrorTest]].
  */
object ErrorTest:
  /** The infallible channel admits nothing: every `Throwable` reaching an observer is a defect. */
  given nothing: ErrorTest[Nothing]:
    def test(t: Throwable): Boolean = false

  /** Derives the test for a concrete channel. A union is tested arm by arm; a class arm by
    * `isInstanceOf`, a stable singleton by identity, and an abstract arm by the evidence the call
    * site holds - so generic code carrying `using ErrorTest[E]` derives `ErrorTest[Concrete | E]`.
    * A channel that is not runtime-testable is refused with the remedy.
    */
  inline given derived: [E <: Throwable] => ErrorTest[E] = ${ derivedImpl[E] }

  private def derivedImpl[E <: Throwable: Type](using Quotes): Expr[ErrorTest[E]] =
    '{
      new ErrorTest[E]:
        def test(t: Throwable): Boolean = ${ arm[E]('t) }
    }

  private def arm[T: Type](t: Expr[Throwable])(using Quotes): Expr[Boolean] =
    import quotes.reflect.*
    // `isInstanceOf` on a class the compiler cannot reach statically - one declared inside a method
    // or a value's body - is rejected as uncheckable, so such an arm is refused with the macro's
    // own message rather than a diagnostic pointing into this file.
    def reachable(symbol: Symbol): Boolean =
      symbol.isNoSymbol || symbol.flags.is(Flags.Package) || (symbol.isClassDef && reachable(symbol.maybeOwner))
    val tpe = TypeRepr.of[T].dealias
    tpe match
      case OrType(a, b) =>
        a.asType match
          case '[x] =>
            b.asType match
              case '[y] => '{ ${ arm[x](t) } || ${ arm[y](t) } }
      case AndType(_, _) =>
        report.errorAndAbort(
          s"ErrorTest: ${tpe.show} is an intersection - what the compiler infers when it widens a union, as it " +
            "does for a continuation whose branches fail with unrelated arms, and when instantiating a type " +
            "parameter from a union it inferred. Observing it would capture unrelated failures as typed; name " +
            "the precise union instead - declare the value's type, or ascribe the branches."
        )
      case ref: TermRef =>
        // A stable singleton - an object or an enum case - is tested by identity, never by a class cast.
        val stable = Ref.term(ref).asExprOf[AnyRef]
        '{ $t eq $stable }
      case tr: TypeRef if tr.typeSymbol.isClassDef && reachable(tr.typeSymbol.maybeOwner) =>
        '{ $t.isInstanceOf[T & Throwable] } // scalafix:ok DisableSyntax.isInstanceOf
      case AppliedType(tycon, args)
          if tycon.typeSymbol.isClassDef && reachable(tycon.typeSymbol.maybeOwner) &&
            args.forall(_ match
              case TypeBounds(_, _) => true;
              case _ => false) =>
        '{ $t.isInstanceOf[T & Throwable] } // scalafix:ok DisableSyntax.isInstanceOf
      case other if other.typeSymbol.isAbstractType || other.typeSymbol.isTypeParam =>
        // An abstract arm takes its evidence from the call site, which holds it as a `using` parameter.
        Implicits.search(TypeRepr.of[ErrorTest[?]].typeSymbol.typeRef.appliedTo(tpe)) match
          case ok: ImplicitSearchSuccess => '{ ${ ok.tree.asExprOf[ErrorTest[?]] }.test($t) }
          case _                         =>
            report.errorAndAbort(
              s"ErrorTest: the error channel ${other.show} is abstract here, so no runtime test can be derived. " +
                s"Take `(using ErrorTest[${other.show}])` and let the call site, where the channel is concrete, derive it."
            )
      case other =>
        report.errorAndAbort(
          s"ErrorTest: ${other.show} is not runtime-testable (a parameterised, refined, or local type); " +
            "name a concrete class, object, or union of them."
        )
    end match
  end arm
end ErrorTest
