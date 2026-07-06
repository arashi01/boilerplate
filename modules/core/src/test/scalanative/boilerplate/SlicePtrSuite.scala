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

import scala.scalanative.unsafe.*

/** Native-only: the pointer-backed `Slice.of(ptr, len)` path (emile's libuv `(Ptr, len)` shape).
  * Pointer memory is populated via `copyInto` from an array slice, which also exercises the
  * array-to-pointer and pointer-to-array copy paths.
  */
class SlicePtrSuite extends munit.FunSuite:
  test("of(ptr, len) views pointer-backed memory; re-slice and copy out"):
    val buf = stackalloc[Byte](4)
    assertEquals(Slice.of(Array[Byte](1, 2, 3, 4)).copyInto(Slice.of(buf, 4)), 4)
    val s = Slice.of(buf, 4)
    assertEquals(s.length, 4)
    assertEquals(s.toArray.toList, List[Byte](1, 2, 3, 4))
    assertEquals(s.drop(1).take(2).toArray.toList, List[Byte](2, 3))

  test("copyInto between pointer-backed slices"):
    val src = stackalloc[Byte](3)
    assertEquals(Slice.of(Array[Byte](5, 6, 7)).copyInto(Slice.of(src, 3)), 3)
    val dst = stackalloc[Byte](3)
    assertEquals(Slice.of(src, 3).copyInto(Slice.of(dst, 3)), 3)
    assertEquals(Slice.of(dst, 3).toArray.toList, List[Byte](5, 6, 7))
end SlicePtrSuite
