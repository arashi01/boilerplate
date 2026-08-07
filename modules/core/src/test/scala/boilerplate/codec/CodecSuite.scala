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

import java.nio.charset.StandardCharsets

import munit.FunSuite

class CodecSuite extends FunSuite:
  private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)
  private def text(b: Array[Byte]): String = new String(b, StandardCharsets.US_ASCII)

  // RFC 4648 section 10 test vectors, verbatim.
  private val rfc4648 = List(
    "" -> ("", "", ""),
    "f" -> ("Zg==", "Zg", "MY"),
    "fo" -> ("Zm8=", "Zm8", "MZXQ"),
    "foo" -> ("Zm9v", "Zm9v", "MZXW6"),
    "foob" -> ("Zm9vYg==", "Zm9vYg", "MZXW6YQ"),
    "fooba" -> ("Zm9vYmE=", "Zm9vYmE", "MZXW6YTB"),
    "foobar" -> ("Zm9vYmFy", "Zm9vYmFy", "MZXW6YTBOI")
  )

  test("Base64 encodes and decodes the RFC 4648 vectors (padded)"):
    rfc4648.foreach { case (plain, (b64, _, _)) =>
      assertEquals(Base64.encode(bytes(plain)), b64)
      assertEquals(Base64.decode(b64).map(text), Right(plain))
    }

  test("Base64Url encodes and decodes the RFC 4648 vectors (unpadded)"):
    rfc4648.foreach { case (plain, (_, b64u, _)) =>
      assertEquals(Base64Url.encode(bytes(plain)), b64u)
      assertEquals(Base64Url.decode(b64u).map(text), Right(plain))
    }

  test("Base32 encodes and decodes the RFC 4648 vectors (unpadded upper case)"):
    rfc4648.foreach { case (plain, (_, _, b32)) =>
      assertEquals(Base32.encode(bytes(plain)), b32)
      assertEquals(Base32.decode(b32).map(text), Right(plain))
    }

  test("Base64Url uses the url-safe alphabet"):
    assertEquals(Base64Url.encode(Array(251.toByte, 255.toByte)), "-_8")
    assertEquals(Base64Url.decode("-_8").map(_.toList), Right(List(251.toByte, 255.toByte)))

  test("Base64 rejects non-canonical input"):
    assert(Base64.decode("Zg").isLeft, "missing padding must be rejected")
    assert(Base64.decode("Zg===").isLeft, "wrong length must be rejected")
    assert(Base64.decode("Zm9!").isLeft, "non-alphabet character must be rejected")
    assertEquals(Base64.decode("Zh=="), Left(Malformed("non-canonical trailing bits")))
    assert(Base64.decode("Zm9vYg-_").isLeft, "url alphabet in std decode must be rejected")

  test("Base64Url rejects non-canonical input"):
    assert(Base64Url.decode("Zg==").isLeft, "padding must be rejected")
    assert(Base64Url.decode("Zm9vY").isLeft, "impossible length (4k+1) must be rejected")
    assertEquals(Base64Url.decode("Zh"), Left(Malformed("non-canonical trailing bits")))
    assert(Base64Url.decode("Zm+8").isLeft, "std alphabet in url decode must be rejected")

  test("Base32 rejects non-canonical input"):
    assert(Base32.decode("my").isLeft, "lower case must be rejected")
    assert(Base32.decode("MY======").isLeft, "padding must be rejected")
    assert(Base32.decode("M").isLeft, "impossible length must be rejected")
    assertEquals(Base32.decode("MZ"), Left(Malformed("non-canonical trailing bits")))
    assert(Base32.decode("M1").isLeft, "non-alphabet character must be rejected")

  test("Hex encodes lower case and decodes either case"):
    assertEquals(Hex.encode(bytes("foobar")), "666f6f626172")
    assertEquals(Hex.decode("666f6f626172").map(text), Right("foobar"))
    assertEquals(Hex.decode("666F6F626172").map(text), Right("foobar"))
    assertEquals(Hex.encode(Array[Byte]()), "")

  test("Hex rejects odd length and non-hex characters"):
    assert(Hex.decode("6").isLeft)
    assert(Hex.decode("6g").isLeft)

  test("Percent encodes with the keep-set and always escapes non-ASCII"):
    assertEquals(Percent.encode("a b/c", Percent.keepUnreserved), "a%20b%2Fc")
    assertEquals(Percent.encode("café", Percent.keepUnreserved), "caf%C3%A9")
    assertEquals(Percent.encode("keep-me_2.0~", Percent.keepUnreserved), "keep-me_2.0~")

  test("Percent strict decode round-trips and rejects malformed escapes"):
    assertEquals(Percent.decode("a%20b%2Fc"), Right("a b/c"))
    assertEquals(Percent.decode("caf%C3%A9"), Right("café"))
    assert(Percent.decode("%2").isLeft, "truncated escape must be rejected")
    assert(Percent.decode("%GG").isLeft, "non-hex escape must be rejected")

  test("Percent strict decode passes raw non-ASCII input through"):
    assertEquals(Percent.decode("café"), Right("café"))

  test("Percent decode after a lone high surrogate still decodes the following escape"):
    // An unpaired surrogate is replaced by the UTF-8 encoder; the escape after it must not be
    // swallowed into the replacement.
    assertEquals(Percent.decode("\uD83D%41end"), Right("?Aend"))
    assertEquals(Percent.decodeLenient("\uD83D%41end"), "?Aend")

  test("Percent lenient decode is total - invalid escapes pass through literally"):
    assertEquals(Percent.decodeLenient("a%41"), "aA")
    assertEquals(Percent.decodeLenient("%2"), "%2")
    assertEquals(Percent.decodeLenient("100%"), "100%")
    assertEquals(Percent.decodeLenient("%GG"), "%GG")
    assertEquals(Percent.decodeLenient("café%20x"), "café x")

  test("Ascii.lower folds A-Z alone and fast-paths already-lower input"):
    assertEquals(Ascii.lower("Content-Type"), "content-type")
    val already = "content-type"
    assert(Ascii.lower(already) eq already, "already-lower input must be returned unchanged")
    assertEquals(Ascii.lower("MIXEDÄcase"), "mixedÄcase")

  test("Ascii token classes are RFC 9110 tchar"):
    assert(Ascii.isToken("GET"))
    assert(Ascii.isToken("x-request-id"))
    assert(Ascii.isTokenChar('~') && Ascii.isTokenChar('!'))
    assert(!Ascii.isToken(""))
    assert(!Ascii.isToken("a b"))
    assert(!Ascii.isTokenChar('(') && !Ascii.isTokenChar('@'))

  test("byte codecs round-trip arbitrary binary content"):
    val data = Array.tabulate(257)(i => (i * 31 % 256).toByte)
    assertEquals(Base64.decode(Base64.encode(data)).map(_.toList), Right(data.toList))
    assertEquals(Base64Url.decode(Base64Url.encode(data)).map(_.toList), Right(data.toList))
    assertEquals(Base32.decode(Base32.encode(data)).map(_.toList), Right(data.toList))
    assertEquals(Hex.decode(Hex.encode(data)).map(_.toList), Right(data.toList))

  test("Malformed names the violated constraint"):
    Base64.decode("Zg") match
      case Left(Malformed(detail)) => assertEquals(detail, "length is not a multiple of four")
      case other                   => fail(s"expected Malformed, got $other")
end CodecSuite
