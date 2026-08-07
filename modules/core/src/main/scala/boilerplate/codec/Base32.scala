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

/** RFC 4648 section 6 base32, UNPADDED upper case - the alphabet enrolment URIs and human
  * transcription carry binary values in. `decode` admits only the one canonical encoding: lower
  * case, `=` padding, any character outside `A-Z2-7`, a length of 1, 3 or 6 modulo 8, or a final
  * symbol whose unused low bits are set is [[Malformed]]. Table-driven, so NOT constant time - a
  * transcribed value is read once, never compared on a hot path.
  */
object Base32:
  private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
  private val inverse: Array[Int] =
    val t = Array.fill(128)(-1)
    for i <- 0 until alphabet.length do t(alphabet.charAt(i).toInt) = i
    t

  def encode(bytes: Slice): String =
    // Division first: `length * 8` overflows Int past 268 MiB while the output stays
    // representable - the same overflow class the base64 decode sizing guards against.
    val out = new StringBuilder(bytes.length / 5 * 8 + (bytes.length % 5 * 8 + 4) / 5)
    @tailrec def go(i: Int, acc: Int, bits: Int): Unit =
      if bits >= 5 then
        val _ = out.append(alphabet((acc >>> (bits - 5)) & 0x1f))
        go(i, acc & ((1 << (bits - 5)) - 1), bits - 5)
      else if i < bytes.length then go(i + 1, (acc << 8) | (bytes(i) & 0xff), bits + 8)
      else if bits > 0 then
        val _ = out.append(alphabet((acc << (5 - bits)) & 0x1f))
        ()
    go(0, 0, 0)
    out.toString
  end encode

  def encode(bytes: Array[Byte]): String = encode(Slice.of(bytes))

  def decode(text: String): Either[Malformed, Array[Byte]] =
    val residue = text.length % 8
    // 5 bits per symbol: 1, 3 and 6 symbols carry 5, 15 and 30 bits, none of which completes an
    // octet count that 8 fewer symbols could not encode - so no octet string produces them.
    if residue == 1 || residue == 3 || residue == 6 then Left(Malformed("impossible length"))
    else
      val out = new Array[Byte](text.length / 8 * 5 + residue * 5 / 8)
      @tailrec def go(i: Int, acc: Int, bits: Int, o: Int): Either[Malformed, Array[Byte]] =
        if i >= text.length then
          // The final symbol's low bits reach no octet; unless they are zero one value has many
          // spellings, and anything keyed on the encoded string no longer identifies the value.
          if acc != 0 then Left(Malformed("non-canonical trailing bits")) else Right(out)
        else
          val c = text.charAt(i).toInt
          val v = if c < 128 then inverse(c) else -1
          if v < 0 then Left(Malformed("non-alphabet character"))
          else
            val a = (acc << 5) | v
            val b = bits + 5
            if b >= 8 then
              out(o) = ((a >>> (b - 8)) & 0xff).toByte
              go(i + 1, a & ((1 << (b - 8)) - 1), b - 8, o + 1)
            else go(i + 1, a, b, o)
      go(0, 0, 0, 0)
    end if
  end decode
end Base32
