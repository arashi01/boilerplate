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

import boilerplate.effect.EffIO

// The typed error rides `IO`'s channel, so `Eq`/`Cogen` reify it to an `Either` via `either`
// (needing a `TypeTest[Throwable, E]`, synthesised for the concrete law error) before comparing.
trait EffIOTestInstances extends CatsEffectTestInstances with EffIOGenerators:

  implicit def eqEffIO[E <: Throwable: Eq, A: Eq](using ticker: Ticker, tt: TypeTest[Throwable, E]): Eq[EffIO[E, A]] =
    Eq.by[EffIO[E, A], IO[Either[E, A]]](_.either)(using eqIOA[Either[E, A]])

  implicit def cogenEffIO[E <: Throwable: Cogen, A: Cogen](using ticker: Ticker, tt: TypeTest[Throwable, E]): Cogen[EffIO[E, A]] =
    cogenIO[Either[E, A]].contramap(_.either)

  implicit def prettyEffIO[E <: Throwable, A](using ticker: Ticker, tt: TypeTest[Throwable, E]): EffIO[E, A] => Pretty =
    eff => Pretty(_ => unsafeRun(eff.either).toString)

  implicit def isomorphismsEffIO[E <: Throwable]: Isomorphisms[EffIO.Of[E]] =
    Isomorphisms.invariant[EffIO.Of[E]]

  // Passes only when the effect completes successfully with `Right(true)`.
  implicit def effIOBooleanToProp[E <: Throwable](eff: EffIO[E, Boolean])(using ticker: Ticker, tt: TypeTest[Throwable, E]): Prop =
    Prop(unsafeRun(eff.either).fold(false, _ => false, _.fold(false)(_.fold(_ => false, identity))))
end EffIOTestInstances

object EffIOTestInstances extends EffIOTestInstances
