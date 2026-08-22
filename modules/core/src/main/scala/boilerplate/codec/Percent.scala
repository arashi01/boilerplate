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

/** RFC 3986 percent-encoding over UTF-8 text. `encode` takes the keep-set as a predicate - each
  * wire component brings its own (a URI segment, a query value, a form field keep different
  * characters), with [[keepUnreserved]] as the universal baseline. Two decodes serve the two
  * disciplines the wire genuinely needs: [[decode]] is strict (a truncated or non-hex escape is
  * [[Malformed]] - URI components), [[decodeLenient]] is total (an invalid escape passes through
  * literally - the form-parsing discipline). Raw non-ASCII input passes through both, which
  * real-world request targets contain.
  */
object Percent:

  private inline def hexDigit(n: Int): Char = if n < 10 then ('0' + n).toChar else ('A' + n - 10).toChar

  private inline def hexValue(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1

  /** RFC 3986 `unreserved`: `A-Z a-z 0-9 - . _ ~` - the characters every component may carry
    * unencoded.
    */
  def keepUnreserved(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '.' || c == '_' || c == '~'

  /** Encodes `value` as UTF-8, percent-escaping every byte outside `keep`. Non-ASCII bytes are
    * always escaped; `keep` decides only over the ASCII range.
    */
  def encode(value: String, keep: Char => Boolean): String =
    // Runs over every URI component of every request; the builder is filled in one pass so an
    // escaped byte costs three appends rather than a fresh string.
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    val out = new java.lang.StringBuilder(bytes.length)
    var i = 0
    while i < bytes.length do
      val b = bytes(i)
      val c = (b & 0xff).toChar
      if b >= 0 && keep(c) then out.append(c)
      else
        out.append('%')
        out.append(hexDigit((b >> 4) & 0xf))
        out.append(hexDigit(b & 0xf))
      i += 1
    out.toString
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end encode

  /** Decodes percent-escapes into UTF-8 text, rejecting a truncated or non-hex escape - the URI
    * component discipline.
    */
  def decode(value: String): Either[Malformed, String] =
    run(value, lenient = false)

  /** Decodes percent-escapes into UTF-8 text totally: an invalid escape passes through literally -
    * the form-parsing discipline. Deliberately lenient; reach for [[decode]] wherever the input
    * claims to be a well-formed URI component.
    */
  def decodeLenient(value: String): String =
    run(value, lenient = true) match
      case Right(text) => text
      case Left(_)     => value // unreachable: the lenient pass never fails

  private def run(value: String, lenient: Boolean): Either[Malformed, String] =
    // The decode counterpart of `encode`, on the same seam: an escape consumes three characters, so
    // the cursor advances by a variable step that no fold over the characters expresses.
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    val out = new java.io.ByteArrayOutputStream(value.length)
    var i = 0
    var error: Option[Malformed] = None
    while i < value.length && error.isEmpty do
      val c = value.charAt(i)
      if c == '%' then
        val hi = if i + 1 < value.length then hexValue(value.charAt(i + 1)) else -1
        val lo = if i + 2 < value.length then hexValue(value.charAt(i + 2)) else -1
        if hi >= 0 && lo >= 0 then
          out.write((hi << 4) | lo)
          i += 3
        else if lenient then
          out.write('%'.toInt)
          i += 1
        else if i + 2 >= value.length then error = Some(Malformed("truncated percent-escape"))
        else error = Some(Malformed(s"malformed percent-escape at index $i"))
      else if c < 0x80 then
        out.write(c.toInt)
        i += 1
      else
        val end =
          if Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value.charAt(i + 1))
          then i + 2
          else i + 1
        val bytes = value.substring(i, end).getBytes(StandardCharsets.UTF_8)
        out.write(bytes, 0, bytes.length)
        i = end
      end if
    end while
    error match
      case Some(e) => Left(e)
      case None    => Right(new String(out.toByteArray, StandardCharsets.UTF_8))
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end run
end Percent
