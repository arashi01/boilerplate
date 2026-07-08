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

import scala.util.control.NoStackTrace

import cats.Eq
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen

/** The typed-error root for the discipline law suites. Under the beta model `E <: Throwable`, so
  * the law tests exercise a genuine `Throwable` error (following the ecosystem `EmileError` DNA)
  * rather than an arbitrary type. Carries the scalacheck/cats instances the discipline machinery
  * needs.
  */
final case class LawError(code: Int) extends Exception(s"law error $code") with NoStackTrace derives CanEqual

object LawError:
  given Arbitrary[LawError] = Arbitrary(Arbitrary.arbitrary[Int].map(LawError(_)))
  given Cogen[LawError] = Cogen[Int].contramap(_.code)
  given Eq[LawError] = Eq.by(_.code)
