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
package boilerplate.effect

import scala.scalanative.unsafe.*

import boilerplate.Slice

// Escape rejection is asserted by the build's `checkCaptureEscapes` - see SecretBorrowSuite for why
// `typeCheckErrors` cannot express a capture-checking negative.
class SliceBorrowEffectSuite extends munit.FunSuite:
  test("an effect that reads the view and keeps only the result compiles and runs"):
    val ptr = stackalloc[Byte](2)
    ptr(0) = 3.toByte
    ptr(1) = 4.toByte
    val total: Eff[Nothing, Int] = Slice.borrowing(ptr, 2)(s => Eff.succeed(s(0) + s(1)))
    assertEquals(total.absolve.syncStep(Int.MaxValue).unsafeRunSync().toOption, Some(7))

  test("an effect over a re-sliced view keeps only the result"):
    val ptr = stackalloc[Byte](4)
    ptr(0) = 1.toByte
    ptr(1) = 2.toByte
    ptr(2) = 3.toByte
    ptr(3) = 4.toByte
    val tail: Eff[Nothing, List[Byte]] = Slice.borrowing(ptr, 4)(s => Eff.succeed(s.drop(2).take(2).toArray.toList))
    assertEquals(tail.absolve.syncStep(Int.MaxValue).unsafeRunSync().toOption, Some(List[Byte](3, 4)))
end SliceBorrowEffectSuite
