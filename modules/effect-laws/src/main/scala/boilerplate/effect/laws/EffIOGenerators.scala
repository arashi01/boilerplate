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

import boilerplate.effect.EffIO

/** Generators for [[boilerplate.effect.EffIO EffIO]] types used in law testing. */
trait EffIOGenerators:

  /** Generates arbitrary `EffIO[E, A]` values from arbitrary `IO[Either[E, A]]` values.
    *
    * This generator produces effects by lifting `IO[Either[E, A]]` which covers pure success
    * values, pure failure values, delayed computations, and async operations.
    */
  implicit def arbitraryEffIO[E, A](using
    arbIO: Arbitrary[IO[Either[E, A]]]
  ): Arbitrary[EffIO[E, A]] =
    Arbitrary(arbIO.arbitrary.map(EffIO.lift(_)))

  /** Generates success-only `EffIO` values for testing operations that expect success. */
  def genSuccessEffIO[E, A: Arbitrary]: Gen[EffIO[E, A]] =
    Arbitrary.arbitrary[A].map(a => EffIO.succeed(a))

  /** Generates failure-only `EffIO` values for testing error handling. */
  def genFailEffIO[E: Arbitrary, A]: Gen[EffIO[E, A]] =
    Arbitrary.arbitrary[E].map(e => EffIO.fail(e))

  /** Generates `EffIO[E, A]` from `Either[E, A]`. */
  def genFromEitherEffIO[E: Arbitrary, A: Arbitrary]: Gen[EffIO[E, A]] =
    Arbitrary.arbitrary[Either[E, A]].map(ea => EffIO.from(ea))
end EffIOGenerators

object EffIOGenerators extends EffIOGenerators
