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

// Array-backed views on Native lower to the interior-pointer paths, so these shared cases also
// exercise the Native memcpy/memmove/memcmp code.
class SliceSuite extends munit.FunSuite:
  test("of views the whole array; of(offset, length) views a sub-range"):
    assertEquals(Slice.of(Array[Byte](1, 2, 3)).toArray.toList, List[Byte](1, 2, 3))
    assertEquals(Slice.of(Array[Byte](1, 2, 3, 4, 5), 1, 3).toArray.toList, List[Byte](2, 3, 4))

  test("take / drop / slice are bounds-checked sub-views over the same memory"):
    val s = Slice.of(Array[Byte](10, 20, 30, 40, 50))
    assertEquals(s.take(2).toArray.toList, List[Byte](10, 20))
    assertEquals(s.drop(3).toArray.toList, List[Byte](40, 50))
    assertEquals(s.slice(1, 4).toArray.toList, List[Byte](20, 30, 40))
    assertEquals(s.take(3).drop(1).toArray.toList, List[Byte](20, 30))

  test("empty is the zero-length view"):
    assert(Slice.empty.isEmpty)
    assertEquals(Slice.empty.length, 0)
    assertEquals(Slice.empty.toArray.length, 0)

  test("apply reads a byte by index; an out-of-range index raises"):
    val s = Slice.of(Array[Byte](10, 20, 30))
    assertEquals(s(0), 10.toByte)
    assertEquals(s.drop(1)(1), 30.toByte)
    val _ = intercept[IllegalArgumentException](s(3))
    val _ = intercept[IllegalArgumentException](s(-1))

  test("contentEquals compares bytes; reference == does not"):
    val s = Slice.of(Array[Byte](10, 20, 30, 40)).take(3)
    assert(s.contentEquals(Slice.of(Array[Byte](10, 20, 30))))
    assert(!s.contentEquals(Slice.of(Array[Byte](10, 20, 99))))
    assert(!s.contentEquals(Slice.of(Array[Byte](10, 20))))
    assert(!(s == Slice.of(Array[Byte](10, 20, 30))))

  test("readBE / readLE decode Short, Int, and Long without sub-slicing"):
    val s = Slice.of(Array[Byte](0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11))
    assertEquals(s.readBE[Short](0), 0x0a0b.toShort)
    assertEquals(s.readLE[Short](0), 0x0b0a.toShort)
    assertEquals(s.readBE[Int](0), 0x0a0b0c0d)
    assertEquals(s.readLE[Int](0), 0x0d0c0b0a)
    assertEquals(s.readBE[Long](0), 0x0a0b0c0d0e0f1011L)
    assertEquals(s.readLE[Long](0), 0x11100f0e0d0c0b0aL)

  test("readBE keeps high bytes unsigned (no sign extension) and honours the offset"):
    val s = Slice.of(Array[Byte](0, 0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte))
    assertEquals(s.readBE[Int](1), -1)
    assertEquals(s.readBE[Int](1) & 0xffffffffL, 0xffffffffL)

  test("a reader past the end raises"):
    val s = Slice.of(Array[Byte](1, 2, 3))
    val _ = intercept[IllegalArgumentException](s.readBE[Int](0))
    val _ = intercept[IllegalArgumentException](s.readBE[Short](2))

  test("sliceOrError returns a view for valid bounds and a typed error for invalid ones"):
    val s = Slice.of(Array[Byte](1, 2, 3, 4, 5))
    assertEquals(s.sliceOrError(1, 3).map(_.toArray.toList), Right(List[Byte](2, 3)))
    assertEquals(s.sliceOrError(0, 5).map(_.length), Right(5))
    assertEquals(s.sliceOrError(1, 99), Left(SliceError.OutOfBounds(1, 99, 5)))
    assertEquals(s.sliceOrError(3, 1), Left(SliceError.OutOfBounds(3, 1, 5)))
    assertEquals(s.sliceOrError(-1, 2), Left(SliceError.OutOfBounds(-1, 2, 5)))

  test("copyInto copies min(length, dst.length) bytes and returns the count"):
    val small = new Array[Byte](2)
    assertEquals(Slice.of(Array[Byte](1, 2, 3, 4)).copyInto(Slice.of(small)), 2)
    assertEquals(small.toList, List[Byte](1, 2))
    val large = new Array[Byte](5)
    assertEquals(Slice.of(Array[Byte](7, 8)).copyInto(Slice.of(large)), 2)
    assertEquals(large.toList, List[Byte](7, 8, 0, 0, 0))

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
