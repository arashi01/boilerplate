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

/** Cross-platform behaviour of the common `Slice` surface, exercised over array-backed views (which
  * on Native drive the interior-pointer `memcpy`/`memmove` paths).
  */
class SliceSuite extends munit.FunSuite:
  test("of views the whole array"):
    val s = Slice.of(Array[Byte](1, 2, 3))
    assertEquals(s.length, 3)
    assert(!s.isEmpty)
    assertEquals(s.toArray.toList, List[Byte](1, 2, 3))

  test("of with offset and length views a sub-range"):
    val s = Slice.of(Array[Byte](1, 2, 3, 4, 5), 1, 3)
    assertEquals(s.toArray.toList, List[Byte](2, 3, 4))

  test("take / drop / slice are bounds-checked sub-views"):
    val s = Slice.of(Array[Byte](10, 20, 30, 40, 50))
    assertEquals(s.take(2).toArray.toList, List[Byte](10, 20))
    assertEquals(s.drop(3).toArray.toList, List[Byte](40, 50))
    assertEquals(s.slice(1, 4).toArray.toList, List[Byte](20, 30, 40))
    assertEquals(s.take(3).drop(1).toArray.toList, List[Byte](20, 30))

  test("empty is the zero-length view"):
    assert(Slice.empty.isEmpty)
    assertEquals(Slice.empty.length, 0)
    assertEquals(Slice.empty.toArray.length, 0)

  test("copyInto copies min(length, dst.length) bytes and returns the count"):
    val dst = new Array[Byte](2)
    assertEquals(Slice.of(Array[Byte](1, 2, 3, 4)).copyInto(Slice.of(dst)), 2)
    assertEquals(dst.toList, List[Byte](1, 2))

  test("copyInto into a larger destination copies only the source length"):
    val dst = new Array[Byte](5)
    assertEquals(Slice.of(Array[Byte](7, 8)).copyInto(Slice.of(dst)), 2)
    assertEquals(dst.toList, List[Byte](7, 8, 0, 0, 0))

  test("toArray produces a copy independent of the backing memory"):
    val backing = Array[Byte](1, 2, 3)
    val copy = Slice.of(backing).toArray
    backing(0) = 99
    assertEquals(copy.toList, List[Byte](1, 2, 3))

  test("out-of-bounds construction and re-slicing are rejected"):
    def rejected(body: => Any): Unit =
      assert(intercept[IllegalArgumentException](body).getMessage.nn.endsWith("bounds"))
    rejected(Slice.of(Array[Byte](1, 2), 1, 5))
    rejected(Slice.of(Array[Byte](1, 2), -1, 1))
    val s = Slice.of(Array[Byte](1, 2, 3))
    rejected(s.take(4))
    rejected(s.drop(4))
    rejected(s.slice(2, 1))
    rejected(s.slice(0, 4))
end SliceSuite
