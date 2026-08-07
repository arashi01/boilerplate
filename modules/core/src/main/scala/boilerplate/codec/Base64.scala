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

/** RFC 4648 section 4 base64, PADDED - the alphabet of PEM, MIME, and HTTP Basic credentials.
  * `decode` admits only the one canonical encoding of an octet string: a length that is not a
  * multiple of four, more than two padding characters, any non-alphabet character, or a short final
  * group whose unused low bits are set is [[Malformed]].
  */
object Base64:
  def encode(bytes: Slice): String = Base64Core.encode(bytes, Base64Core.stdAlphabet, pad = true)
  def encode(bytes: Array[Byte]): String = encode(Slice.of(bytes))
  def decode(text: String): Either[Malformed, Array[Byte]] = Base64Core.decode(text, Base64Core.stdInverse, padded = true)

/** RFC 4648 section 5 base64url, UNPADDED - the JOSE and web-token alphabet. `decode` admits only
  * the one canonical encoding of an octet string: padding, `+`, `/`, any non-alphabet character, an
  * impossible length (4k+1), or a short final group whose unused low bits are set is [[Malformed]].
  */
object Base64Url:
  def encode(bytes: Slice): String = Base64Core.encode(bytes, Base64Core.urlAlphabet, pad = false)
  def encode(bytes: Array[Byte]): String = encode(Slice.of(bytes))
  def decode(text: String): Either[Malformed, Array[Byte]] = Base64Core.decode(text, Base64Core.urlInverse, padded = false)

private object Base64Core:
  val urlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
  val stdAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  private def inverse(alphabet: String): Array[Int] =
    val t = Array.fill(128)(-1)
    for i <- 0 until alphabet.length do t(alphabet.charAt(i).toInt) = i
    t
  val urlInverse: Array[Int] = inverse(urlAlphabet)
  val stdInverse: Array[Int] = inverse(stdAlphabet)

  def encode(bytes: Slice, alphabet: String, pad: Boolean): String =
    val out = new StringBuilder((bytes.length + 2) / 3 * 4)
    @tailrec def full(i: Int): Int =
      if i + 3 <= bytes.length then
        val n = ((bytes(i) & 0xff) << 16) | ((bytes(i + 1) & 0xff) << 8) | (bytes(i + 2) & 0xff)
        val _ = out
          .append(alphabet((n >> 18) & 0x3f))
          .append(alphabet((n >> 12) & 0x3f))
          .append(alphabet((n >> 6) & 0x3f))
          .append(alphabet(n & 0x3f))
        full(i + 3)
      else i
    val i = full(0)
    bytes.length - i match
      case 1 =>
        val n = (bytes(i) & 0xff) << 16
        val _ = out.append(alphabet((n >> 18) & 0x3f)).append(alphabet((n >> 12) & 0x3f))
        if pad then out.append("==")
      case 2 =>
        val n = ((bytes(i) & 0xff) << 16) | ((bytes(i + 1) & 0xff) << 8)
        val _ = out.append(alphabet((n >> 18) & 0x3f)).append(alphabet((n >> 12) & 0x3f)).append(alphabet((n >> 6) & 0x3f))
        if pad then out.append('=')
      case _ => ()
    out.toString
  end encode

  def decode(text: String, table: Array[Int], padded: Boolean): Either[Malformed, Array[Byte]] =
    def stripped: Either[Malformed, String] =
      if !padded then Right(text)
      else if text.length % 4 != 0 then Left(Malformed("length is not a multiple of four"))
      else
        val padding = text.length - text.lastIndexWhere(_ != '=') - 1
        if padding > 2 then Left(Malformed("more than two padding characters")) else Right(text.dropRight(padding))
    stripped.flatMap { body =>
      if body.length % 4 == 1 then Left(Malformed("impossible length"))
      else
        // length/4*3, not length*3/4: the latter overflows Int past 715,827,882 characters and
        // throws a negative array size out of a total Either.
        val out = new Array[Byte](body.length / 4 * 3 + math.max(body.length % 4 - 1, 0))
        @tailrec def go(i: Int, o: Int): Either[Malformed, Array[Byte]] =
          if i >= body.length then Right(out)
          else
            val chunk = math.min(4, body.length - i)
            if chunk < 2 then Left(Malformed("impossible length"))
            else
              val values = (0 until chunk).map { j =>
                val c = body.charAt(i + j).toInt
                if c < 128 then table(c) else -1
              }
              val acc = values.foldLeft(0)((a, v) => (a << 6) | (v & 0x3f)) << (6 * (4 - chunk))
              // A short final group carries bits below its last octet that no byte receives; unless
              // they are zero one octet string has many encodings, defeating any defence keyed on
              // the encoded string - a replay cache, a denylist, a unique-token column.
              if values.exists(_ < 0) then Left(Malformed("non-alphabet character"))
              else if (acc & ((1 << (8 * (4 - chunk))) - 1)) != 0 then Left(Malformed("non-canonical trailing bits"))
              else
                out(o) = ((acc >> 16) & 0xff).toByte
                if chunk >= 3 then out(o + 1) = ((acc >> 8) & 0xff).toByte
                if chunk == 4 then out(o + 2) = (acc & 0xff).toByte
                go(i + chunk, o + chunk - 1)
            end if
        go(0, 0)
    }
  end decode
end Base64Core
