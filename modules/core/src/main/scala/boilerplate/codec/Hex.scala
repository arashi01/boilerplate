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

import scala.annotation.tailrec

import boilerplate.Slice

/** RFC 4648 section 8 base16. `encode` emits lower case - the canonical form for digests and
  * fingerprints. `decode` accepts BOTH cases (transcribed hex arrives in either), so it is
  * deliberately not canonical-strict: one octet string has mixed-case spellings, and anything keyed
  * on hex text must key on the decoded bytes instead. An odd length or a non-hex character is
  * [[Malformed]].
  */
object Hex:
  private val digits = "0123456789abcdef"

  def encode(bytes: Slice): String =
    val out = new StringBuilder(bytes.length * 2)
    @tailrec def go(i: Int): Unit =
      if i < bytes.length then
        val b = bytes(i) & 0xff
        val _ = out.append(digits(b >> 4)).append(digits(b & 0xf))
        go(i + 1)
    go(0)
    out.toString

  def encode(bytes: Array[Byte]): String = encode(Slice.of(bytes))

  def decode(text: String): Either[Malformed, Array[Byte]] =
    inline def value(c: Char): Int =
      if c >= '0' && c <= '9' then c - '0'
      else if c >= 'a' && c <= 'f' then c - 'a' + 10
      else if c >= 'A' && c <= 'F' then c - 'A' + 10
      else -1
    if text.length % 2 != 0 then Left(Malformed("odd length"))
    else
      val out = new Array[Byte](text.length / 2)
      @tailrec def go(i: Int): Either[Malformed, Array[Byte]] =
        if i >= text.length then Right(out)
        else
          val hi = value(text.charAt(i))
          val lo = value(text.charAt(i + 1))
          if hi < 0 || lo < 0 then Left(Malformed("non-hex character"))
          else
            out(i / 2) = ((hi << 4) | lo).toByte
            go(i + 2)
      go(0)
    end if
  end decode
end Hex
