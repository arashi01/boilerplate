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

import munit.FunSuite

import boilerplate.codec.Hex

class UUIDSuite extends FunSuite:
  private def bytes(hex: String): Slice = Slice.of(Hex.decode(hex).toOption.get)

  // RFC 9562 Appendix A.3: 16 random octets, of which v4 overwrites the version nibble and the
  // variant bits alone.
  private val v4 = UUID.v4(bytes("919108f752d133205bacf847db4148a8"))
  // Appendix A.6: rand_a = 0xCC3 in the low 12 bits of octets 6-7, rand_b in the low 62 of 8-15.
  private val v7 = UUID.v7(1645557742000L, bytes("000000000000fcc3d8c4dc0c0c07398f"))

  test("the RFC 9562 A.3 v4 vector reproduces exactly"):
    assertEquals(UUID.render(v4), "919108f7-52d1-4320-9bac-f847db4148a8")
    assertEquals(v4.version, 4)

  test("the RFC 9562 A.6 v7 vector reproduces exactly"):
    assertEquals(UUID.render(v7), "017f22e2-79b0-7cc3-98c4-dc0c0c07398f")
    assertEquals(v7.version, 7)

  test("v7 overwrites the caller's first six bytes with the timestamp and keeps the rest"):
    assertEquals(UUID.v7(1645557742000L, bytes("ffffffffffffFCC3D8C4DC0C0C07398F")), v7)

  test("v4 keeps every bit the version and variant fields do not claim"):
    // Only octet 6's high nibble and octet 8's top two bits differ from the input.
    val input = Hex.decode("919108f752d133205bacf847db4148a8").toOption.get
    val output = v4.toArray
    assertEquals(output.indices.filter(i => input(i) != output(i)).toList, List(6, 8))

  test("parse reads either letter case and renders lower case"):
    val forms = List(
      "017f22e2-79b0-7cc3-98c4-dc0c0c07398f",
      "017F22E2-79B0-7CC3-98C4-DC0C0C07398F",
      "017f22E2-79b0-7CC3-98c4-dc0c0c07398F"
    )
    assert(forms.forall(UUID.parse(_).contains(v7)))
    assert(forms.forall(UUID.parse(_).map(UUID.render).contains("017f22e2-79b0-7cc3-98c4-dc0c0c07398f")))

  test("parse admits the ABNF alone, refusing the forms the JDK accepts"):
    val refused = List(
      "1-1-1-1-1",
      "+1-1-1-1-1",
      "{017f22e2-79b0-7cc3-98c4-dc0c0c07398f}",
      "urn:uuid:017f22e2-79b0-7cc3-98c4-dc0c0c07398f",
      "017f22e2-79b0-7cc3-98c4-dc0c0c07398f0",
      "017f22e279b07cc398c4dc0c0c07398f",
      "017f22e2-79b0-7cc3-98c4-dc0c0c07398٣",
      ""
    )
    assert(refused.forall(UUID.parse(_).isLeft), refused.filter(UUID.parse(_).isRight).mkString(", "))

  test("nil and max render as the RFC's sentinels"):
    assertEquals(UUID.render(UUID.nil), "00000000-0000-0000-0000-000000000000")
    assertEquals(UUID.render(UUID.max), "ffffffff-ffff-ffff-ffff-ffffffffffff")

  test("ordering is unsigned, so max sorts last rather than first"):
    // Signed 64-bit comparison of the halves - what java.util.UUID.compareTo does - inverts this.
    val order = summon[Ordering[UUID]]
    assert(order.lt(UUID.nil, v7))
    assert(order.lt(v7, v4))
    assert(order.lt(v4, UUID.max))
    assert(order.lt(UUID.nil, UUID.max))

  test("byte order and canonical text order agree"):
    val values = List(v4, v7, UUID.max, UUID.nil)
    assertEquals(values.sorted.map(UUID.render), values.map(UUID.render).sorted)

  test("of reads 16 octets verbatim and refuses any other length"):
    assertEquals(UUID.of(Slice.of(v7.toArray)), Right(v7))
    assert(UUID.of(Slice.of(new Array[Byte](15))).isLeft)
    assert(UUID.of(Slice.of(new Array[Byte](17))).isLeft)

  test("the ValueCodec round-trips and encodes canonically"):
    val codec = summon[ValueCodec[UUID]]
    assertEquals(codec.decode(codec.encode(v4)), Right(v4))
    assertEquals(codec.decode("919108F7-52D1-4320-9BAC-F847DB4148A8").map(codec.encode), Right("919108f7-52d1-4320-9bac-f847db4148a8"))

  test("equality and hashCode follow the 128 bits, not identity"):
    val parsed = UUID.parse("919108f7-52d1-4320-9bac-f847db4148a8").toOption.get
    assertEquals(parsed, v4)
    assertEquals(parsed.hashCode, v4.hashCode)
    assertNotEquals(v4, v7)

  test("copyInto writes the octets into a wire buffer in place"):
    val destination = new Array[Byte](20)
    v4.copyInto(Slice.of(destination))
    assertEquals(Hex.encode(Slice.of(destination, 0, 16)), "919108f752d143209bacf847db4148a8")
    intercept[IllegalArgumentException](v4.copyInto(Slice.of(new Array[Byte](15))))

  test("toString is the canonical text, so a UUID cannot log as an opaque object"):
    assertEquals(v4.toString, "919108f7-52d1-4320-9bac-f847db4148a8")

  test("parse refuses a 36-character form whose dashes sit in the wrong places"):
    val misplaced = List("017f22e-279b0-7cc3-98c4-dc0c0c07398f", "017f22e2-79b0-7cc3-98c4d-c0c0c07398f")
    assert(misplaced.forall(t => t.length == 36 && UUID.parse(t).isLeft), misplaced.filter(UUID.parse(_).isRight).mkString(", "))

  test("v4 and v7 reject a source that is not 16 bytes"):
    val short = intercept[IllegalArgumentException](UUID.v4(Slice.of(new Array[Byte](15))))
    val long = intercept[IllegalArgumentException](UUID.v7(0L, Slice.of(new Array[Byte](17))))
    assert(short.getMessage.contains("16 random bytes"), short.getMessage)
    assert(long.getMessage.contains("16 random bytes"), long.getMessage)

  test("v7 rejects a timestamp outside the RFC's 48 unsigned bits"):
    val random = bytes("000000000000fcc3d8c4dc0c0c07398f")
    val negative = intercept[IllegalArgumentException](UUID.v7(-1L, random))
    val overlong = intercept[IllegalArgumentException](UUID.v7(1L << 48, random))
    assert(negative.getMessage.contains("48 unsigned bits"), negative.getMessage)
    assert(overlong.getMessage.contains("48 unsigned bits"), overlong.getMessage)
    assertEquals(UUID.v7((1L << 48) - 1L, random).version, 7)

  test("ordering falls through to the low word unsigned, where signed comparison would invert"):
    val order = summon[Ordering[UUID]]
    val lowOne = UUID.of(Slice.of(Hex.decode("00000000000000000000000000000001").toOption.get)).toOption.get
    val lowMax = UUID.of(Slice.of(Hex.decode("0000000000000000ffffffffffffffff").toOption.get)).toOption.get
    assert(order.lt(lowOne, lowMax))
    assertEquals(order.compare(v4, v4), 0)

  test("neither sentinel reports an RFC 9562 version"):
    assertEquals(UUID.nil.version, 0)
    assertEquals(UUID.max.version, 15)
end UUIDSuite
