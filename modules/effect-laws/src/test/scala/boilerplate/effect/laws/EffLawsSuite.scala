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
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.BifoldableTests
import cats.laws.discipline.BifunctorTests
import cats.laws.discipline.BitraverseTests
import cats.laws.discipline.DeferTests
import cats.laws.discipline.FoldableTests
import cats.laws.discipline.MonadErrorTests
import cats.laws.discipline.SemigroupKTests
import cats.laws.discipline.TraverseTests
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
  *   - `EqTests` verifies equality reflexivity, symmetry, transitivity
  *   - `FoldableTests` verifies left/right fold consistency (using Option as base)
  *   - `TraverseTests` verifies traverse laws (using Option as base)
  *   - `BifoldableTests` verifies bifold laws (using Option as base)
  *   - `BitraverseTests` verifies bitraverse laws (using Option as base)
  *
  * Note: `GenConcurrent` extends `GenSpawn` with `ref`/`deferred` primitives but adds no new laws.
  * `GenTemporal` extends `GenConcurrent` with `sleep`/`timeout` but temporal laws require
  * specialized test infrastructure (e.g., `TimeT`). Since we delegate to `IO`, correctness follows
  * from upstream. Behavioural tests for these primitives are in `EffSuite`.
  */
class EffLawsSuite extends DisciplineSuite with EffTestInstances with EffOptionTestInstances:

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

  // --- Bifunctor laws ---

  // For Bifunctor tests, we need to vary both type parameters
  type TestEffBi[E, A] = Eff[IO, E, A]

  implicit def arbTestEffBi[E: Arbitrary, A: Arbitrary]: Arbitrary[TestEffBi[E, A]] =
    Arbitrary(
      for either <- Arbitrary.arbitrary[Either[E, A]]
      yield Eff.from[IO, E, A](either)
    )

  implicit def eqTestEffBi[E: Eq, A: Eq]: Eq[TestEffBi[E, A]] = eqEff[E, A]

  checkAll(
    "Eff[IO, *, *].Bifunctor",
    BifunctorTests[TestEffBi].bifunctor[Int, Int, Int, String, String, String]
  )

  // ---------------------------------------------------------------------------
  // Data Typeclass Laws - Eq/PartialOrder with IO
  // ---------------------------------------------------------------------------
  // Eq and PartialOrder instances delegate to the underlying F[Either[E, A]].
  // Since we have Eq[IO[Either[E, A]]] via cats-effect testkit, we can test these.

  checkAll(
    "Eff[IO, Int, Int].Eq",
    EqTests[Eff[IO, E, Int]].eqv
  )

  // PartialOrder requires PartialOrder[IO[Either[E, A]]] which we derive from Eq
  // Note: IO doesn't have a natural PartialOrder, so we define one for testing
  // based on the Eq instance (all equal or incomparable).

  // ---------------------------------------------------------------------------
  // Data Typeclass Laws - Foldable/Traverse with Option
  // ---------------------------------------------------------------------------
  // Foldable and Traverse instances require Foldable[F]/Traverse[F].
  // IO is an effect type, not a data container, so it doesn't have these instances.
  // We use Option as the base effect to test these instances.
  // The required Arbitrary/Eq instances come from EffOptionTestInstances trait.

  // Option-based effect type for Foldable/Traverse tests
  type OptEff[A] = Eff[Option, E, A]
  type OptEffBi[E, A] = Eff[Option, E, A]

  // Use type aliases to match the EffOptionTestInstances implicit resolution
  implicit def arbOptEff[A: Arbitrary]: Arbitrary[OptEff[A]] = arbitraryEffOption[E, A]
  implicit def arbOptEffBi[EE: Arbitrary, A: Arbitrary]: Arbitrary[OptEffBi[EE, A]] = arbitraryEffOption[EE, A]
  implicit def eqOptEff[A: Eq]: Eq[OptEff[A]] = eqEffOption[E, A]
  implicit def eqOptEffBi[EE: Eq, A: Eq]: Eq[OptEffBi[EE, A]] = eqEffOption[EE, A]

  checkAll(
    "Eff[Option, Int, *].Foldable",
    FoldableTests[OptEff].foldable[Int, Int]
  )

  checkAll(
    "Eff[Option, Int, *].Traverse",
    TraverseTests[OptEff].traverse[Int, Int, Int, Int, Option, Option]
  )

  checkAll(
    "Eff[Option, *, *].Bifoldable",
    BifoldableTests[OptEffBi].bifoldable[Int, Int, Int]
  )

  checkAll(
    "Eff[Option, *, *].Bitraverse",
    BitraverseTests[OptEffBi].bitraverse[Option, Int, Int, Int, String, String, String]
  )

  // Note: ParallelTests requires complex type matching for Nested[IO.Par, Either[E, *], *]
  // The Parallel instance is tested via the parMapN behavioural tests in EffSuite.
  // Law testing for Parallel on Eff would require significant infrastructure changes.
end EffLawsSuite
