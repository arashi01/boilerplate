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
import org.scalacheck.Cogen
import org.scalacheck.Prop
import org.scalacheck.util.Pretty

import boilerplate.effect.EffR

/** Test instances for [[boilerplate.effect.EffR EffR]] law testing.
  *
  * Provides `Eq`, `Cogen`, and `Pretty` instances required by discipline law tests. These run all
  * `EffR` computations with a fixed environment value of type `R`.
  */
trait EffRTestInstances extends CatsEffectTestInstances with EffRGenerators with EffGenerators:

  /** The fixed environment value used to run `EffR` computations in tests. */
  def testEnv[R]: R

  /** Equality for `EffR[IO, R, E, A]` based on running with `testEnv` and comparing outcomes.
    *
    * Uses the `Ticker` mechanism from cats-effect testkit to evaluate effects deterministically.
    */
  implicit def eqEffR[R, E: Eq, A: Eq](using ticker: Ticker): Eq[EffR[IO, R, E, A]] =
    Eq.by[EffR[IO, R, E, A], IO[Either[E, A]]](_.run(testEnv).either)(using eqIOA[Either[E, A]])

  /** Cogen for `EffR[IO, R, E, A]` based on running with `testEnv` to outcome. */
  implicit def cogenEffR[R, E: Cogen, A: Cogen](using ticker: Ticker): Cogen[EffR[IO, R, E, A]] =
    cogenIO[Either[E, A]].contramap(_.run(testEnv).either)

  /** Pretty printer for `EffR` in test failure messages. */
  implicit def prettyEffR[R, E, A](using ticker: Ticker): EffR[IO, R, E, A] => Pretty =
    effR => Pretty(_ => unsafeRun(effR.run(testEnv).either).toString)

  /** Isomorphisms for `EffR.Of[IO, R, E]` required by Semigroupal tests. */
  implicit def isomorphismsEffR[R, E]: Isomorphisms[EffR.Of[IO, R, E]] =
    Isomorphisms.invariant[EffR.Of[IO, R, E]]

  /** Converts `EffR[IO, R, E, Boolean]` to `Prop` for property assertions.
    *
    * The effect must complete successfully with `Right(true)` to pass.
    */
  implicit def effRBooleanToProp[R, E](effR: EffR[IO, R, E, Boolean])(using ticker: Ticker): Prop =
    Prop(unsafeRun(effR.run(testEnv).either).fold(false, _ => false, _.fold(false)(_.fold(_ => false, identity))))
end EffRTestInstances

object EffRTestInstances extends EffRTestInstances:
  /** Default test environment - Unit since many tests don't use the environment. */
  def testEnv[R]: R = ().asInstanceOf[R] // scalafix:ok DisableSyntax.asInstanceOf
