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

import boilerplate.effect.EffIO

// `GenConcurrent`, `GenTemporal`, `Sync`, and `Async` all resolve from the single `Async` instance by
// subtyping, so `GenSpawnTests` exercises the shared structure. `Foldable`/`Traverse`/`Bifunctor` do
// not apply - the error is a `Throwable` in the channel, not a foldable value. Behavioural tests are
// in `EffIOSuite`.
class EffIOLawsSuite extends DisciplineSuite with EffIOTestInstances:

  type E = LawError
  type TestEffIO[A] = EffIO[E, A]

  implicit val ticker: Ticker = Ticker(TestContext())

  implicit def arbIOEither[A: Arbitrary]: Arbitrary[IO[Either[E, A]]] =
    Arbitrary(
      Arbitrary.arbitrary[Either[E, A]].map(IO.pure(_))
    )

  implicit def eqTestEffIO[A: Eq]: Eq[TestEffIO[A]] = eqEffIO[E, A]

  implicit def eqEitherThrowableUnit: Eq[TestEffIO[Either[Throwable, Unit]]] =
    eqEffIO[E, Either[Throwable, Unit]]

  implicit def eqEitherThrowableA: Eq[TestEffIO[Either[Throwable, Int]]] =
    eqEffIO[E, Either[Throwable, Int]]

  implicit def eqTuple3: Eq[TestEffIO[(Int, Int, Int)]] =
    eqEffIO[E, (Int, Int, Int)]

  implicit def eqOutcome: Eq[TestEffIO[Outcome[TestEffIO, Throwable, Int]]] =
    eqEffIO[E, Outcome[TestEffIO, Throwable, Int]]

  implicit def arbTestEffIO[A: Arbitrary]: Arbitrary[TestEffIO[A]] =
    arbitraryEffIO[E, A]

  implicit val arbTestEffIOUnit: Arbitrary[TestEffIO[Unit]] =
    arbitraryEffIO[E, Unit]

  // A => B requires Cogen[A] to generate functions, and Arbitrary[B] for the result
  implicit def arbTestEffIOFunc[A: Cogen, B: Arbitrary]: Arbitrary[TestEffIO[A => B]] =
    Arbitrary(
      Arbitrary.arbFunction1[A, B].arbitrary.map(f => EffIO.succeed(f))
    )

  implicit def cogenTestEffIO[A: Cogen]: Cogen[TestEffIO[A]] =
    cogenEffIO[E, A]

  implicit def testEffIOBoolToProp(eff: TestEffIO[Boolean]): Prop =
    effIOBooleanToProp(eff)

  implicit def prettyTestEffIO[A]: TestEffIO[A] => Pretty =
    prettyEffIO[E, A]

  // GenSpawn tests include MonadCancel and Monad laws
  checkAll(
    "EffIO[LawError, *].GenSpawn[Throwable]",
    GenSpawnTests[TestEffIO, Throwable].spawn[Int, Int, Int]
  )

  checkAll(
    "EffIO[LawError, *].Defer",
    DeferTests[TestEffIO].defer[Int]
  )

  checkAll(
    "EffIO[LawError, *].Clock",
    ClockTests[TestEffIO].clock
  )

  checkAll(
    "EffIO[LawError, *].Unique",
    UniqueTests[TestEffIO].unique
  )

  implicit def eqEitherEU: Eq[TestEffIO[Either[E, Unit]]] = eqEffIO[E, Either[E, Unit]]
  implicit def eqEitherEA: Eq[TestEffIO[Either[E, Int]]] = eqEffIO[E, Either[E, Int]]
  implicit def eqEitherTEffIO: Eq[EitherT[TestEffIO, E, Int]] =
    EitherT.catsDataEqForEitherT[TestEffIO, E, Int]

  checkAll(
    "EffIO[LawError, *].MonadError[LawError]",
    MonadErrorTests[TestEffIO, E].monadError[Int, Int, Int]
  )

  checkAll(
    "EffIO[LawError, *].SemigroupK",
    SemigroupKTests[TestEffIO].semigroupK[Int]
  )

  checkAll(
    "EffIO[LawError, Int].Eq",
    EqTests[EffIO[E, Int]].eqv
  )
end EffIOLawsSuite
