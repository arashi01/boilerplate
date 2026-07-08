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
import cats.laws.discipline.DeferTests
import cats.laws.discipline.MonadErrorTests
import cats.laws.discipline.SemigroupKTests
import cats.laws.discipline.arbitrary.*
import munit.DisciplineSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen
import org.scalacheck.Prop
import org.scalacheck.util.Pretty

import boilerplate.effect.Eff

/** Law tests for [[boilerplate.effect.Eff Eff]] typeclass instances via cats-effect-laws, verifying
  * the beta phantom instances (a representation cast of `IO`'s) are lawful.
  *
  * `GenConcurrent`/`GenTemporal` add no laws over `GenSpawn`; since every instance is a
  * representation cast of the corresponding `IO` instance, correctness follows from upstream, and
  * the primitives are exercised behaviourally in `EffSuite`.
  * `Foldable`/`Traverse`/`Bifoldable`/`Bitraverse`/`Bifunctor` no longer exist under beta (the
  * error is a `Throwable` in the channel, not a foldable value), so those law tests are gone.
  */
class EffLawsSuite extends DisciplineSuite with EffTestInstances:

  type E = LawError
  type TestEff[A] = Eff[IO, E, A]

  implicit val ticker: Ticker = Ticker(TestContext())

  implicit def arbIOEither[A: Arbitrary]: Arbitrary[IO[Either[E, A]]] =
    Arbitrary(Arbitrary.arbitrary[Either[E, A]].map(IO.pure(_)))

  implicit def eqTestEff[A: Eq]: Eq[TestEff[A]] = eqEff[E, A]

  implicit def eqEitherThrowableUnit: Eq[TestEff[Either[Throwable, Unit]]] =
    eqEff[E, Either[Throwable, Unit]]

  implicit def eqEitherThrowableA: Eq[TestEff[Either[Throwable, Int]]] =
    eqEff[E, Either[Throwable, Int]]

  implicit def eqTuple3: Eq[TestEff[(Int, Int, Int)]] =
    eqEff[E, (Int, Int, Int)]

  implicit def eqOutcome: Eq[TestEff[Outcome[TestEff, Throwable, Int]]] =
    eqEff[E, Outcome[TestEff, Throwable, Int]]

  implicit def arbTestEff[A: Arbitrary]: Arbitrary[TestEff[A]] =
    arbitraryEff[E, A]

  implicit val arbTestEffUnit: Arbitrary[TestEff[Unit]] =
    arbitraryEff[E, Unit]

  // A => B requires Cogen[A] to generate functions, and Arbitrary[B] for the result
  implicit def arbTestEffFunc[A: Cogen, B: Arbitrary]: Arbitrary[TestEff[A => B]] =
    Arbitrary(
      Arbitrary.arbFunction1[A, B].arbitrary.map(f => Eff.succeed[IO, E, A => B](f))
    )

  implicit def cogenTestEff[A: Cogen]: Cogen[TestEff[A]] =
    cogenEff[E, A]

  implicit def testEffBoolToProp(eff: TestEff[Boolean]): Prop =
    effBooleanToProp(eff)

  implicit def prettyTestEff[A]: TestEff[A] => Pretty =
    prettyEff[E, A]

  // GenSpawn tests include MonadCancel and Monad laws
  checkAll(
    "Eff[IO, LawError, *].GenSpawn[Throwable]",
    GenSpawnTests[TestEff, Throwable].spawn[Int, Int, Int]
  )

  checkAll(
    "Eff[IO, LawError, *].Defer",
    DeferTests[TestEff].defer[Int]
  )

  checkAll(
    "Eff[IO, LawError, *].Clock",
    ClockTests[TestEff].clock
  )

  checkAll(
    "Eff[IO, LawError, *].Unique",
    UniqueTests[TestEff].unique
  )

  implicit def eqEitherEU: Eq[TestEff[Either[E, Unit]]] = eqEff[E, Either[E, Unit]]
  implicit def eqEitherEA: Eq[TestEff[Either[E, Int]]] = eqEff[E, Either[E, Int]]
  implicit def eqEitherTEff: Eq[EitherT[TestEff, E, Int]] =
    EitherT.catsDataEqForEitherT[TestEff, E, Int]

  checkAll(
    "Eff[IO, LawError, *].MonadError[LawError]",
    MonadErrorTests[TestEff, E].monadError[Int, Int, Int]
  )

  checkAll(
    "Eff[IO, LawError, *].SemigroupK",
    SemigroupKTests[TestEff].semigroupK[Int]
  )

  checkAll(
    "Eff[IO, LawError, Int].Eq",
    EqTests[Eff[IO, E, Int]].eqv
  )
end EffLawsSuite
