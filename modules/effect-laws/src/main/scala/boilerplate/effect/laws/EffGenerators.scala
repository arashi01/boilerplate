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

import cats.effect.*
import org.scalacheck.Arbitrary
import org.scalacheck.Gen

import boilerplate.effect.Eff

/** Generators for [[boilerplate.effect.Eff Eff]] types used in law testing. */
trait EffGenerators:

  /** Generates arbitrary `Eff[IO, E, A]` values from arbitrary `IO[Either[E, A]]` values.
    *
    * This generator produces effects by lifting `IO[Either[E, A]]` which covers: - Pure success
    * values - Pure failure values - Delayed computations - Async operations
    */
  implicit def arbitraryEff[E, A](using
    arbIO: Arbitrary[IO[Either[E, A]]]
  ): Arbitrary[Eff[IO, E, A]] =
    Arbitrary(arbIO.arbitrary.map(Eff.lift(_)))

  /** Generates success-only `Eff` values for testing operations that expect success. */
  def genSuccessEff[E, A: Arbitrary]: Gen[Eff[IO, E, A]] =
    Arbitrary.arbitrary[A].map(a => Eff.succeed[IO, E, A](a))

  /** Generates failure-only `Eff` values for testing error handling. */
  def genFailEff[E: Arbitrary, A]: Gen[Eff[IO, E, A]] =
    Arbitrary.arbitrary[E].map(e => Eff.fail[IO, E, A](e))

  /** Generates `Eff[IO, E, A]` from `Either[E, A]`. */
  def genFromEither[E: Arbitrary, A: Arbitrary]: Gen[Eff[IO, E, A]] =
    Arbitrary.arbitrary[Either[E, A]].map(ea => Eff.from[IO, E, A](ea))
end EffGenerators

object EffGenerators extends EffGenerators
