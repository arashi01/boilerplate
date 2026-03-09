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
package boilerplate.codec

import munit.FunSuite
import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class Base64Suite extends ScalaCheckSuite:

  // ---------------------------------------------------------------------------
  // RFC 4648 §10 Test Vectors
  // ---------------------------------------------------------------------------

  test("encode empty"):
    assertEquals(Base64.encode(Array.empty[Byte]), "")

  test("encode 'f'"):
    assertEquals(Base64.encode("f".getBytes("UTF-8").nn), "Zg==")

  test("encode 'fo'"):
    assertEquals(Base64.encode("fo".getBytes("UTF-8").nn), "Zm8=")

  test("encode 'foo'"):
    assertEquals(Base64.encode("foo".getBytes("UTF-8").nn), "Zm9v")

  test("encode 'foob'"):
    assertEquals(Base64.encode("foob".getBytes("UTF-8").nn), "Zm9vYg==")

  test("encode 'fooba'"):
    assertEquals(Base64.encode("fooba".getBytes("UTF-8").nn), "Zm9vYmE=")

  test("encode 'foobar'"):
    assertEquals(Base64.encode("foobar".getBytes("UTF-8").nn), "Zm9vYmFy")

  test("decode empty"):
    assertDecoded("", Array.empty[Byte])

  test("decode 'Zg=='"):
    assertDecoded("Zg==", "f".getBytes("UTF-8").nn)

  test("decode 'Zm8='"):
    assertDecoded("Zm8=", "fo".getBytes("UTF-8").nn)

  test("decode 'Zm9v'"):
    assertDecoded("Zm9v", "foo".getBytes("UTF-8").nn)

  test("decode 'Zm9vYg=='"):
    assertDecoded("Zm9vYg==", "foob".getBytes("UTF-8").nn)

  test("decode 'Zm9vYmE='"):
    assertDecoded("Zm9vYmE=", "fooba".getBytes("UTF-8").nn)

  test("decode 'Zm9vYmFy'"):
    assertDecoded("Zm9vYmFy", "foobar".getBytes("UTF-8").nn)

  // ---------------------------------------------------------------------------
  // Edge Cases
  // ---------------------------------------------------------------------------

  test("encode single zero byte"):
    assertEquals(Base64.encode(Array[Byte](0)), "AA==")

  test("encode all byte values round-trips"):
    val allBytes = Array.tabulate(256)(i => i.toByte)
    val encoded = Base64.encode(allBytes)
    assertDecoded(encoded, allBytes)

  test("encode large payload round-trips"):
    val large = Array.tabulate(10000)(i => i.toByte)
    val encoded = Base64.encode(large)
    assertDecoded(encoded, large)

  // ---------------------------------------------------------------------------
  // Decode Errors
  // ---------------------------------------------------------------------------

  test("decode rejects invalid characters"):
    assert(Base64.decode("Zm9v!!!").isLeft)

  test("decode rejects single trailing character"):
    assert(Base64.decode("A").isLeft)

  test("decode returns Base64.Error on failure"):
    Base64.decode("!!!") match
      case Left(e: Base64.Error) => assert(e.getMessage.nn.nonEmpty)
      case other                 => fail(s"Expected Left(Base64.Error), got: $other")

  // ---------------------------------------------------------------------------
  // Property-Based Tests
  // ---------------------------------------------------------------------------

  given Arbitrary[Array[Byte]] = Arbitrary(
    Gen.choose(0, 1024).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray))
  )

  property("round-trip: decode(encode(bytes)) == Right(bytes)"):
    forAll { (bytes: Array[Byte]) =>
      val encoded = Base64.encode(bytes)
      Base64.decode(encoded) match
        case Right(decoded) => assertEquals(decoded.toSeq, bytes.toSeq)
        case Left(e)        => fail(s"Decode failed: ${e.getMessage}")
    }

  property("encode produces only valid Base64 characters"):
    forAll { (bytes: Array[Byte]) =>
      val encoded = Base64.encode(bytes)
      assert(
        encoded.forall(c => c.isLetterOrDigit || c == '+' || c == '/' || c == '='),
        s"Invalid character in encoded output: $encoded"
      )
    }

  property("encoded length is always a multiple of 4"):
    forAll { (bytes: Array[Byte]) =>
      val encoded = Base64.encode(bytes)
      assert(encoded.length % 4 == 0, s"Length ${encoded.length} is not a multiple of 4")
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def assertDecoded(input: String, expected: Array[Byte]): Unit =
    Base64.decode(input) match
      case Right(decoded) => assertEquals(decoded.toSeq, expected.toSeq)
      case Left(e)        => fail(s"Decode failed: ${e.getMessage}")

end Base64Suite
