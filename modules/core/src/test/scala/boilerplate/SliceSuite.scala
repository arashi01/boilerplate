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

  test("update writes a byte via s(i) = b, honouring the offset; an out-of-range index raises"):
    val a = new Array[Byte](4)
    val s = Slice.of(a)
    s(1) = 42.toByte
    assertEquals(s(1), 42.toByte)
    s.drop(2)(1) = 7.toByte // through a sub-view: backing index 3
    assertEquals(a.toList, List[Byte](0, 42, 0, 7))
    val _ = intercept[IllegalArgumentException](s(4) = 1.toByte)
    val _ = intercept[IllegalArgumentException](s(-1) = 1.toByte)

  test("contentEquals compares bytes; reference == does not"):
    val s = Slice.of(Array[Byte](10, 20, 30, 40)).take(3)
    assert(s.contentEquals(Slice.of(Array[Byte](10, 20, 30))))
    assert(!s.contentEquals(Slice.of(Array[Byte](10, 20, 99))))
    assert(!s.contentEquals(Slice.of(Array[Byte](10, 20))))
    assert(!(s == Slice.of(Array[Byte](10, 20, 30))))

  test("constantTimeEquals is true on equal content and false for a change at any position"):
    val a = Slice.of(Array[Byte](1, 2, 3, 4, 5))
    assert(a.constantTimeEquals(Slice.of(Array[Byte](1, 2, 3, 4, 5))))
    assert(!a.constantTimeEquals(Slice.of(Array[Byte](9, 2, 3, 4, 5))))
    assert(!a.constantTimeEquals(Slice.of(Array[Byte](1, 2, 9, 4, 5))))
    assert(!a.constantTimeEquals(Slice.of(Array[Byte](1, 2, 3, 4, 9))))

  test("constantTimeEquals treats differing lengths as unequal, and empty views as equal"):
    assert(!Slice.of(Array[Byte](1, 2, 3)).constantTimeEquals(Slice.of(Array[Byte](1, 2))))
    assert(!Slice.of(Array[Byte](1, 2)).constantTimeEquals(Slice.of(Array[Byte](1, 2, 3))))
    assert(Slice.empty.constantTimeEquals(Slice.empty))
    assert(!Slice.empty.constantTimeEquals(Slice.of(Array[Byte](1))))

  test("constantTimeEquals compares the viewed sub-range and agrees with contentEquals"):
    val view = Slice.of(Array[Byte](9, 1, 2, 3, 9)).slice(1, 4)
    val same = Slice.of(Array[Byte](1, 2, 3))
    val diff = Slice.of(Array[Byte](1, 2, 9))
    assert(view.constantTimeEquals(same))
    assert(!view.constantTimeEquals(diff))
    assertEquals(view.constantTimeEquals(same), view.contentEquals(same))
    assertEquals(view.constantTimeEquals(diff), view.contentEquals(diff))

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

  test("writeBE / writeLE encode Short, Int, and Long, round-tripping through readBE / readLE"):
    val s = Slice.of(new Array[Byte](8))
    s.writeBE[Short](0, 0x0a0b.toShort)
    assertEquals(s.readBE[Short](0), 0x0a0b.toShort)
    s.writeLE[Short](0, 0x0a0b.toShort)
    assertEquals(s.readLE[Short](0), 0x0a0b.toShort)
    s.writeBE[Int](0, 0x0a0b0c0d)
    assertEquals(s.readBE[Int](0), 0x0a0b0c0d)
    s.writeLE[Int](0, 0x0a0b0c0d)
    assertEquals(s.readLE[Int](0), 0x0a0b0c0d)
    s.writeBE[Long](0, 0x0a0b0c0d0e0f1011L)
    assertEquals(s.readBE[Long](0), 0x0a0b0c0d0e0f1011L)
    s.writeLE[Long](0, 0x0a0b0c0d0e0f1011L)
    assertEquals(s.readLE[Long](0), 0x0a0b0c0d0e0f1011L)

  test("writeBE lays bytes big-endian, writeLE little-endian, honouring the offset"):
    val be = new Array[Byte](6)
    Slice.of(be).writeBE[Int](1, 0x01020304)
    assertEquals(be.toList, List[Byte](0, 1, 2, 3, 4, 0))
    val le = new Array[Byte](6)
    Slice.of(le).writeLE[Int](1, 0x01020304)
    assertEquals(le.toList, List[Byte](0, 4, 3, 2, 1, 0))

  test("writeBE writes through a sub-view into the shared backing"):
    val a = new Array[Byte](6)
    Slice.of(a).drop(2).writeBE[Short](0, 0x0102.toShort)
    assertEquals(a.toList, List[Byte](0, 0, 1, 2, 0, 0))

  test("a scalar write past the end raises"):
    val s = Slice.of(new Array[Byte](3))
    val _ = intercept[IllegalArgumentException](s.writeBE[Int](0, 1))
    val _ = intercept[IllegalArgumentException](s.writeBE[Short](2, 1.toShort))
    val _ = intercept[IllegalArgumentException](s.writeLE[Long](0, 1L))

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

  test("wipe zeros the viewed bytes"):
    val backing = Array[Byte](1, 2, 3, 4, 5)
    Slice.of(backing).wipe()
    assertEquals(backing.toList, List[Byte](0, 0, 0, 0, 0))

  test("wipe erases exactly the viewed sub-range, leaving neighbours intact"):
    val backing = Array[Byte](1, 2, 3, 4, 5)
    Slice.of(backing).slice(1, 4).wipe()
    assertEquals(backing.toList, List[Byte](1, 0, 0, 0, 5))

  test("wipe of an empty view is a no-op"):
    Slice.empty.wipe()
    val backing = Array[Byte](7, 8, 9)
    Slice.of(backing).take(0).wipe()
    Slice.of(backing).drop(3).wipe()
    assertEquals(backing.toList, List[Byte](7, 8, 9))

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
