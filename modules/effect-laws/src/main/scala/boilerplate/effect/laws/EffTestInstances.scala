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

import scala.reflect.TypeTest

import cats.*
import cats.effect.*
import cats.effect.testkit.TestInstances as CatsEffectTestInstances
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck.Cogen
import org.scalacheck.Prop
import org.scalacheck.util.Pretty

import boilerplate.effect.Eff

/** Test instances for [[boilerplate.effect.Eff Eff]] law testing (base effect `IO`).
  *
  * Provides `Eq`, `Cogen`, and `Pretty`. Under the beta model the typed error rides `IO`'s channel,
  * so equality/cogen reify it back to an `Either` via `either` (needing `TypeTest[Throwable, E]`,
  * synthesised for the concrete law error) before comparing.
  */
trait EffTestInstances extends CatsEffectTestInstances with EffGenerators:

  /** Equality for `Eff[IO, E, A]` based on running the reified `IO[Either[E, A]]` to an outcome. */
  implicit def eqEff[E <: Throwable: Eq, A: Eq](using ticker: Ticker, tt: TypeTest[Throwable, E]): Eq[Eff[IO, E, A]] =
    Eq.by[Eff[IO, E, A], IO[Either[E, A]]](_.either)(using eqIOA[Either[E, A]])

  /** Cogen for `Eff[IO, E, A]` based on the reified outcome. */
  implicit def cogenEff[E <: Throwable: Cogen, A: Cogen](using ticker: Ticker, tt: TypeTest[Throwable, E]): Cogen[Eff[IO, E, A]] =
    cogenIO[Either[E, A]].contramap(_.either)

  /** Pretty printer for `Eff` in test failure messages. */
  implicit def prettyEff[E <: Throwable, A](using ticker: Ticker, tt: TypeTest[Throwable, E]): Eff[IO, E, A] => Pretty =
    eff => Pretty(_ => unsafeRun(eff.either).toString)

  /** Isomorphisms for `Eff.Of[IO, E]` required by Semigroupal tests. */
  implicit def isomorphismsEff[E <: Throwable]: Isomorphisms[Eff.Of[IO, E]] =
    Isomorphisms.invariant[Eff.Of[IO, E]]

  /** Converts `Eff[IO, E, Boolean]` to `Prop` for property assertions.
    *
    * The effect must complete successfully with `Right(true)` to pass.
    */
  implicit def effBooleanToProp[E <: Throwable](eff: Eff[IO, E, Boolean])(using ticker: Ticker, tt: TypeTest[Throwable, E]): Prop =
    Prop(unsafeRun(eff.either).fold(false, _ => false, _.fold(false)(_.fold(_ => false, identity))))
end EffTestInstances

object EffTestInstances extends EffTestInstances
