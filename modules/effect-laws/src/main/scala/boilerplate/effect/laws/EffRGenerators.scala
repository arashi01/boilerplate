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

import cats.effect.*
import org.scalacheck.Arbitrary
import org.scalacheck.Gen

import boilerplate.effect.Eff
import boilerplate.effect.EffR

/** Generators for [[boilerplate.effect.EffR EffR]] types used in law testing. */
trait EffRGenerators:

  /** Generates arbitrary `EffR[IO, R, E, A]` values by generating `Eff[IO, E, A]` and lifting.
    *
    * The environment `R` is ignored since law tests run computations with a fixed environment.
    */
  implicit def arbitraryEffR[R, E, A](using
    arbEff: Arbitrary[Eff[IO, E, A]]
  ): Arbitrary[EffR[IO, R, E, A]] =
    Arbitrary(arbEff.arbitrary.map(eff => EffR.lift[IO, R, E, A](eff)))

  /** Generates success-only `EffR` values for testing operations that expect success. */
  def genSuccessEffR[R, E, A: Arbitrary]: Gen[EffR[IO, R, E, A]] =
    Arbitrary.arbitrary[A].map(a => EffR.succeed[IO, R, E, A](a))

  /** Generates failure-only `EffR` values for testing error handling. */
  def genFailEffR[R, E: Arbitrary, A]: Gen[EffR[IO, R, E, A]] =
    Arbitrary.arbitrary[E].map(e => EffR.fail[IO, R, E, A](e))

  /** Generates `EffR[IO, R, E, A]` from `Either[E, A]`. */
  def genFromEitherEffR[R, E: Arbitrary, A: Arbitrary]: Gen[EffR[IO, R, E, A]] =
    Arbitrary.arbitrary[Either[E, A]].map(ea => EffR.from[IO, R, E, A](ea))
end EffRGenerators

object EffRGenerators extends EffRGenerators
