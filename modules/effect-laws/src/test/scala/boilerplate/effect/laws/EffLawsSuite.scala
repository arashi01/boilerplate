/*
 * Copyright (c) 2025 Boilerplate contributors.
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
import cats.data.EitherT
import cats.effect.*
import cats.effect.kernel.Outcome
import cats.effect.laws.ClockTests
import cats.effect.laws.GenSpawnTests
import cats.effect.laws.UniqueTests
import cats.effect.testkit.TestContext
import cats.laws.discipline.DeferTests
import cats.laws.discipline.MonadErrorTests
import cats.laws.discipline.SemigroupKTests
import cats.laws.discipline.arbitrary.*
import munit.DisciplineSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen
import org.scalacheck.Prop

import boilerplate.effect.Eff

/** Law tests for [[boilerplate.effect.Eff Eff]] typeclass instances.
  *
  * Uses cats-effect-laws to verify that our typeclass implementations conform to the expected laws.
  *
  * ==Test Coverage==
  *   - `GenSpawnTests` verifies Monad, MonadCancel, and fiber/race laws
  *   - `MonadErrorTests[E]` verifies typed error channel laws (raiseError, handleError, etc.)
  *   - `SemigroupKTests` verifies `combineK`/`alt` associativity
  *   - `ClockTests` verifies monotonic/realTime laws
  *   - `DeferTests` verifies deferred evaluation laws
  *   - `UniqueTests` verifies unique token generation laws
  *
  * Note: `GenConcurrent` extends `GenSpawn` with `ref`/`deferred` primitives but adds no new laws.
  * `GenTemporal` extends `GenConcurrent` with `sleep`/`timeout` but temporal laws require
  * specialized test infrastructure (e.g., `TimeT`). Since we delegate to `IO`, correctness follows
  * from upstream. Behavioural tests for these primitives are in `EffSuite`.
  */
class EffLawsSuite extends DisciplineSuite with EffTestInstances:

  // Use Int as both error type E and value types A, B, C for simplicity
  type E = Int
  type TestEff[A] = Eff[IO, E, A]

  implicit val ticker: Ticker = Ticker(TestContext())

  // --- Arbitrary instances for IO[Either[E, A]] (required for arbitraryEff) ---

  implicit def arbIOEither[A: Arbitrary]: Arbitrary[IO[Either[E, A]]] =
    Arbitrary(
      Arbitrary.arbitrary[Either[E, A]].map(IO.pure(_))
    )

  // --- Required Eq instances ---

  implicit def eqTestEff[A: Eq]: Eq[TestEff[A]] = eqEff[E, A]

  implicit def eqEitherThrowableUnit: Eq[TestEff[Either[Throwable, Unit]]] =
    eqEff[E, Either[Throwable, Unit]]

  implicit def eqEitherThrowableA: Eq[TestEff[Either[Throwable, Int]]] =
    eqEff[E, Either[Throwable, Int]]

  implicit def eqTuple3: Eq[TestEff[(Int, Int, Int)]] =
    eqEff[E, (Int, Int, Int)]

  implicit def eqOutcome: Eq[TestEff[Outcome[TestEff, Throwable, Int]]] =
    eqEff[E, Outcome[TestEff, Throwable, Int]]

  // --- Arbitrary instances for Eff ---

  implicit def arbTestEff[A: Arbitrary]: Arbitrary[TestEff[A]] =
    arbitraryEff[E, A]

  implicit val arbTestEffUnit: Arbitrary[TestEff[Unit]] =
    arbitraryEff[E, Unit]

  // A => B requires Cogen[A] to generate functions, and Arbitrary[B] for the result
  implicit def arbTestEffFunc[A: Cogen, B: Arbitrary]: Arbitrary[TestEff[A => B]] =
    Arbitrary(
      Arbitrary.arbFunction1[A, B].arbitrary.map(f => Eff.succeed[IO, E, A => B](f))
    )

  // --- Cogen instances ---

  implicit def cogenTestEff[A: Cogen]: Cogen[TestEff[A]] =
    cogenEff[E, A]

  // --- Prop conversion ---

  implicit def testEffBoolToProp(eff: TestEff[Boolean]): Prop =
    effBooleanToProp(eff)

  // --- Pretty instances ---

  implicit def prettyTestEff[A]: TestEff[A] => org.scalacheck.util.Pretty =
    prettyEff[E, A]

  // --- The actual law tests ---

  // GenSpawn tests include MonadCancel and Monad laws
  checkAll(
    "Eff[IO, Int, *].GenSpawn[Throwable]",
    GenSpawnTests[TestEff, Throwable].spawn[Int, Int, Int]
  )

  checkAll(
    "Eff[IO, Int, *].Defer",
    DeferTests[TestEff].defer[Int]
  )

  checkAll(
    "Eff[IO, Int, *].Clock",
    ClockTests[TestEff].clock
  )

  checkAll(
    "Eff[IO, Int, *].Unique",
    UniqueTests[TestEff].unique
  )

  // --- MonadError[E] laws for the typed error channel ---

  // Additional Eq instances for MonadErrorTests
  implicit def eqEitherEU: Eq[TestEff[Either[E, Unit]]] = eqEff[E, Either[E, Unit]]
  implicit def eqEitherEA: Eq[TestEff[Either[E, Int]]] = eqEff[E, Either[E, Int]]
  implicit def eqEitherTEff: Eq[EitherT[TestEff, E, Int]] = EitherT.catsDataEqForEitherT[TestEff, E, Int]

  checkAll(
    "Eff[IO, Int, *].MonadError[Int]",
    MonadErrorTests[TestEff, E].monadError[Int, Int, Int]
  )

  // --- SemigroupK laws for alt semantics ---

  checkAll(
    "Eff[IO, Int, *].SemigroupK",
    SemigroupKTests[TestEff].semigroupK[Int]
  )

  // Note: ParallelTests requires complex type matching for Nested[IO.Par, Either[E, *], *]
  // The Parallel instance is tested via the parMapN behavioural tests in EffSuite.
  // Law testing for Parallel on Eff would require significant infrastructure changes.
end EffLawsSuite
