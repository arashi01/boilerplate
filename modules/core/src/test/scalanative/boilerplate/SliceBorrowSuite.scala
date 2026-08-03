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
package boilerplate

import scala.language.experimental.captureChecking
import scala.scalanative.unsafe.*

// The opted-in caller. Escape rejection is asserted by the build's `checkCaptureEscapes` - see
// SecretBorrowSuite for why `typeCheckErrors` cannot express a capture-checking negative.
class SliceBorrowSuite extends munit.FunSuite:
  test("a borrow that copies out rather than retaining the view compiles under capture checking"):
    val ptr = stackalloc[Byte](2)
    ptr(0) = 9.toByte
    ptr(1) = 8.toByte
    assertEquals(Slice.borrowing(ptr, 2)(s => s.toArray).toList, List[Byte](9, 8))

  test("a chained re-slice is readable inside the borrow and its copy outlives it"):
    val ptr = stackalloc[Byte](4)
    ptr(0) = 1.toByte
    ptr(1) = 2.toByte
    ptr(2) = 3.toByte
    ptr(3) = 4.toByte
    assertEquals(Slice.borrowing(ptr, 4)(s => s.drop(1).take(2).toArray).toList, List[Byte](2, 3))
    assertEquals(Slice.borrowing(ptr, 4)(s => s.drop(2).take(1)(0)), 3.toByte)
end SliceBorrowSuite
