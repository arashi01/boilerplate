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
package boilerplate.effect.testkit

import scala.reflect.TypeTest

import cats.*
import cats.effect.*
import cats.effect.testkit.TestInstances as CatsEffectTestInstances
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck.Cogen
import org.scalacheck.Prop
import org.scalacheck.util.Pretty

import boilerplate.effect.Eff

// The typed error rides `IO`'s channel, so `Eq`/`Cogen` reify it to an `Either` via `either`
// (needing a `TypeTest[Throwable, E]`, synthesised for the concrete law error) before comparing.
trait EffTestInstances extends CatsEffectTestInstances with EffGenerators:

  given eqEff[E <: Throwable: Eq, A: Eq](using ticker: Ticker, tt: TypeTest[Throwable, E]): Eq[Eff[E, A]] =
    Eq.by[Eff[E, A], IO[Either[E, A]]](_.either.absolve)(using eqIOA[Either[E, A]])

  given cogenEff[E <: Throwable: Cogen, A: Cogen](using ticker: Ticker, tt: TypeTest[Throwable, E]): Cogen[Eff[E, A]] =
    cogenIO[Either[E, A]].contramap(_.either.absolve)

  given prettyEff[E <: Throwable, A](using ticker: Ticker, tt: TypeTest[Throwable, E]): (Eff[E, A] => Pretty) =
    eff => Pretty(_ => unsafeRun(eff.either.absolve).toString)

  given isomorphismsEff[E <: Throwable]: Isomorphisms[Eff.Of[E]] =
    Isomorphisms.invariant[Eff.Of[E]]

  // Passes only when the effect completes successfully with `Right(true)`.
  given effBooleanToProp[E <: Throwable](using ticker: Ticker, tt: TypeTest[Throwable, E]): Conversion[Eff[E, Boolean], Prop] =
    eff => Prop(unsafeRun(eff.either.absolve).fold(false, _ => false, _.fold(false)(_.fold(_ => false, identity))))
end EffTestInstances

object EffTestInstances extends EffTestInstances
