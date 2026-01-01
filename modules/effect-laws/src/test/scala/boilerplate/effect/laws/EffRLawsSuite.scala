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
import boilerplate.effect.EffR

/** Law tests for [[boilerplate.effect.EffR EffR]] typeclass instances.
  *
  * Uses cats-effect-laws to verify that our typeclass implementations conform to the expected laws.
  * The environment type `R` is fixed to `Unit` for testing purposes.
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
  * from upstream. Behavioural tests for these primitives are in `EffRSuite`.
  */
class EffRLawsSuite extends DisciplineSuite with EffRTestInstances:

  // Use Unit as environment type, Int as error type E and value types A, B, C
  type R = Unit
  type E = Int
  type TestEffR[A] = EffR[IO, R, E, A]

  // Provide the fixed environment for running EffR computations
  override def testEnv[R0]: R0 = ().asInstanceOf[R0] // scalafix:ok DisableSyntax.asInstanceOf

  implicit val ticker: Ticker = Ticker(TestContext())

  // --- Arbitrary instances for IO[Either[E, A]] (required for arbitraryEff -> arbitraryEffR) ---

  implicit def arbIOEither[A: Arbitrary]: Arbitrary[IO[Either[E, A]]] =
    Arbitrary(
      Arbitrary.arbitrary[Either[E, A]].map(IO.pure(_))
    )

  // --- Required Eq instances ---

  implicit def eqTestEffR[A: Eq]: Eq[TestEffR[A]] = eqEffR[R, E, A]

  implicit def eqEitherThrowableUnit: Eq[TestEffR[Either[Throwable, Unit]]] =
    eqEffR[R, E, Either[Throwable, Unit]]

  implicit def eqEitherThrowableA: Eq[TestEffR[Either[Throwable, Int]]] =
    eqEffR[R, E, Either[Throwable, Int]]

  implicit def eqTuple3: Eq[TestEffR[(Int, Int, Int)]] =
    eqEffR[R, E, (Int, Int, Int)]

  implicit def eqOutcome: Eq[TestEffR[Outcome[TestEffR, Throwable, Int]]] =
    eqEffR[R, E, Outcome[TestEffR, Throwable, Int]]

  // --- Arbitrary instances for EffR ---

  // First need Arbitrary[Eff] which is used by arbitraryEffR
  implicit def arbEff[A: Arbitrary]: Arbitrary[Eff[IO, E, A]] =
    arbitraryEff[E, A]

  implicit def arbTestEffR[A: Arbitrary]: Arbitrary[TestEffR[A]] =
    arbitraryEffR[R, E, A]

  implicit val arbTestEffRUnit: Arbitrary[TestEffR[Unit]] =
    arbitraryEffR[R, E, Unit]

  // A => B requires Cogen[A] to generate functions, and Arbitrary[B] for the result
  implicit def arbTestEffRFunc[A: Cogen, B: Arbitrary]: Arbitrary[TestEffR[A => B]] =
    Arbitrary(
      Arbitrary.arbFunction1[A, B].arbitrary.map(f => EffR.succeed[IO, R, E, A => B](f))
    )

  // --- Cogen instances ---

  implicit def cogenTestEffR[A: Cogen]: Cogen[TestEffR[A]] =
    cogenEffR[R, E, A]

  // --- Prop conversion ---

  implicit def testEffRBoolToProp(effR: TestEffR[Boolean]): Prop =
    effRBooleanToProp(effR)

  // --- Pretty instances ---

  implicit def prettyTestEffR[A]: TestEffR[A] => org.scalacheck.util.Pretty =
    prettyEffR[R, E, A]

  // --- The actual law tests ---

  // GenSpawn tests include MonadCancel and Monad laws
  checkAll(
    "EffR[IO, Unit, Int, *].GenSpawn[Throwable]",
    GenSpawnTests[TestEffR, Throwable].spawn[Int, Int, Int]
  )

  checkAll(
    "EffR[IO, Unit, Int, *].Defer",
    DeferTests[TestEffR].defer[Int]
  )

  checkAll(
    "EffR[IO, Unit, Int, *].Clock",
    ClockTests[TestEffR].clock
  )

  checkAll(
    "EffR[IO, Unit, Int, *].Unique",
    UniqueTests[TestEffR].unique
  )

  // --- MonadError[E] laws for the typed error channel ---

  // Additional Eq instances for MonadErrorTests
  implicit def eqEitherEU: Eq[TestEffR[Either[E, Unit]]] = eqEffR[R, E, Either[E, Unit]]
  implicit def eqEitherEA: Eq[TestEffR[Either[E, Int]]] = eqEffR[R, E, Either[E, Int]]
  implicit def eqEitherTEffR: Eq[EitherT[TestEffR, E, Int]] = EitherT.catsDataEqForEitherT[TestEffR, E, Int]

  checkAll(
    "EffR[IO, Unit, Int, *].MonadError[Int]",
    MonadErrorTests[TestEffR, E].monadError[Int, Int, Int]
  )

  // --- SemigroupK laws for alt semantics ---

  checkAll(
    "EffR[IO, Unit, Int, *].SemigroupK",
    SemigroupKTests[TestEffR].semigroupK[Int]
  )

  // Note: ParallelTests requires complex type matching for the reader-wrapped parallel type.
  // The Parallel instance is tested via the parMapN behavioural tests in EffRSuite.
  // Law testing for Parallel on EffR would require significant infrastructure changes.
end EffRLawsSuite
