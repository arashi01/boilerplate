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

// The pointer-backed path (of(ptr, len) / borrowing) that the array constructors cannot reach.
class SlicePtrSuite extends munit.FunSuite:
  test("of(ptr, len) views pointer-backed memory; re-slice and copy out"):
    val buf = stackalloc[Byte](4)
    assertEquals(Slice.of(Array[Byte](1, 2, 3, 4)).copyInto(Slice.of(buf, 4)), 4)
    val s = Slice.of(buf, 4)
    assertEquals(s.toArray.toList, List[Byte](1, 2, 3, 4))
    assertEquals(s.drop(1).take(2).toArray.toList, List[Byte](2, 3))

  test("copyInto between pointer-backed slices"):
    val src = stackalloc[Byte](3)
    assertEquals(Slice.of(Array[Byte](5, 6, 7)).copyInto(Slice.of(src, 3)), 3)
    val dst = stackalloc[Byte](3)
    assertEquals(Slice.of(src, 3).copyInto(Slice.of(dst, 3)), 3)
    assertEquals(Slice.of(dst, 3).toArray.toList, List[Byte](5, 6, 7))

  test("borrowing hands f a pointer-backed slice; the copy-out persists beyond the scope"):
    val ptr = stackalloc[Byte](4)
    ptr(0) = 1.toByte; ptr(1) = 2.toByte; ptr(2) = 3.toByte; ptr(3) = 4.toByte
    val copied = Slice.borrowing(ptr, 4) { s =>
      assertEquals(s(0), 1.toByte)
      assertEquals(s.readBE[Int](0), 0x01020304)
      s.toArray
    }
    assertEquals(copied.toList, List[Byte](1, 2, 3, 4))

  test("wipe zeros pointer-backed memory"):
    val buf = stackalloc[Byte](4)
    val _ = Slice.of(Array[Byte](1, 2, 3, 4)).copyInto(Slice.of(buf, 4))
    Slice.of(buf, 4).wipe()
    assertEquals(Slice.of(buf, 4).toArray.toList, List[Byte](0, 0, 0, 0))

  test("wipe erases exactly the viewed sub-range of pointer-backed memory"):
    val buf = stackalloc[Byte](4)
    val _ = Slice.of(Array[Byte](9, 9, 9, 9)).copyInto(Slice.of(buf, 4))
    Slice.of(buf, 4).slice(1, 3).wipe()
    assertEquals(Slice.of(buf, 4).toArray.toList, List[Byte](9, 0, 0, 9))

  test("constantTimeEquals over pointer-backed memory"):
    val buf = stackalloc[Byte](3)
    val _ = Slice.of(Array[Byte](1, 2, 3)).copyInto(Slice.of(buf, 3))
    assert(Slice.of(buf, 3).constantTimeEquals(Slice.of(Array[Byte](1, 2, 3))))
    assert(!Slice.of(buf, 3).constantTimeEquals(Slice.of(Array[Byte](1, 2, 9))))
end SlicePtrSuite
