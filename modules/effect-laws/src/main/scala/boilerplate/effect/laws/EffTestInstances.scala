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
package boilerplate.effect.laws

import cats.*
import cats.effect.*
import cats.effect.testkit.TestInstances as CatsEffectTestInstances
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen
import org.scalacheck.Prop
import org.scalacheck.util.Pretty

import boilerplate.effect.Eff

/** Test instances for [[boilerplate.effect.Eff Eff]] law testing.
  *
  * Provides `Eq`, `Cogen`, and `Pretty` instances required by discipline law tests.
  */
trait EffTestInstances extends CatsEffectTestInstances with EffGenerators:

  /** Equality for `Eff[IO, E, A]` based on running to completion and comparing outcomes.
    *
    * Uses the `Ticker` mechanism from cats-effect testkit to evaluate effects deterministically.
    */
  implicit def eqEff[E: Eq, A: Eq](using ticker: Ticker): Eq[Eff[IO, E, A]] =
    Eq.by[Eff[IO, E, A], IO[Either[E, A]]](_.either)(using eqIOA[Either[E, A]])

  /** Cogen for `Eff[IO, E, A]` based on running to outcome. */
  implicit def cogenEff[E: Cogen, A: Cogen](using ticker: Ticker): Cogen[Eff[IO, E, A]] =
    cogenIO[Either[E, A]].contramap(_.either)

  /** Pretty printer for `Eff` in test failure messages. */
  implicit def prettyEff[E, A](using ticker: Ticker): Eff[IO, E, A] => Pretty =
    eff => Pretty(_ => unsafeRun(eff.either).toString)

  /** Isomorphisms for `Eff.Of[IO, E]` required by Semigroupal tests. */
  implicit def isomorphismsEff[E]: Isomorphisms[Eff.Of[IO, E]] =
    Isomorphisms.invariant[Eff.Of[IO, E]]

  /** Converts `Eff[IO, E, Boolean]` to `Prop` for property assertions.
    *
    * The effect must complete successfully with `Right(true)` to pass.
    */
  implicit def effBooleanToProp[E](eff: Eff[IO, E, Boolean])(using ticker: Ticker): Prop =
    Prop(unsafeRun(eff.either).fold(false, _ => false, _.fold(false)(_.fold(_ => false, identity))))
end EffTestInstances

object EffTestInstances extends EffTestInstances

/** Test instances for `Eff[Option, E, A]` used in Foldable/Traverse law testing.
  *
  * Option is used as the base effect since it is both a Monad and Traverse, allowing us to test the
  * Foldable, Traverse, Bifoldable, and Bitraverse instances which require `Foldable[F]` or
  * `Traverse[F]` constraints that `IO` does not satisfy.
  */
trait EffOptionTestInstances:

  /** Arbitrary for `Eff[Option, E, A]` generating the full space of Option[Either[E, A]]. */
  implicit def arbitraryEffOption[E: Arbitrary, A: Arbitrary]: Arbitrary[Eff[Option, E, A]] =
    Arbitrary(
      for outer <- Arbitrary.arbOption[Either[E, A]].arbitrary
      yield Eff.lift[Option, E, A](outer)
    )

  /** Cogen for `Eff[Option, E, A]` based on the underlying Option[Either[E, A]]. */
  implicit def cogenEffOption[E: Cogen, A: Cogen]: Cogen[Eff[Option, E, A]] =
    Cogen[Option[Either[E, A]]].contramap(_.either)

  /** Equality for `Eff[Option, E, A]` based on the underlying Option[Either[E, A]]. */
  implicit def eqEffOption[E: Eq, A: Eq]: Eq[Eff[Option, E, A]] =
    Eq.by[Eff[Option, E, A], Option[Either[E, A]]](_.either)

  /** Pretty printer for `Eff[Option, E, A]` in test failure messages. */
  implicit def prettyEffOption[E, A]: Eff[Option, E, A] => Pretty =
    eff => Pretty(_ => eff.either.toString)

  /** Isomorphisms for `Eff.Of[Option, E]` required by Semigroupal tests. */
  implicit def isomorphismsEffOption[E]: Isomorphisms[Eff.Of[Option, E]] =
    Isomorphisms.invariant[Eff.Of[Option, E]]
end EffOptionTestInstances

object EffOptionTestInstances extends EffOptionTestInstances
