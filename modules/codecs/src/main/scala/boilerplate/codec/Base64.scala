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

/** Base64 encoding and decoding per RFC 4648.
  *
  * Supports both the standard alphabet (section 4: `+`, `/`, `=` padding) and the URL-safe alphabet
  * (section 5: `-`, `_`, no padding).
  */
object Base64:

  /** Encodes binary data to a standard Base64 string (RFC 4648 section 4) with padding. */
  inline def encode(data: Array[Byte]): String = encode(data, false)

  /** Encodes binary data to Base64. When `urlSafe` is `true`, uses the URL-safe alphabet (RFC 4648
    * section 5): `+` becomes `-`, `/` becomes `_`, and padding is stripped.
    */
  def encode(data: Array[Byte], urlSafe: Boolean): String =
    val standard = PlatformBase64.encode(data)
    if urlSafe then toUrlSafe(standard) else standard

  /** Decodes a standard Base64 string (RFC 4648 section 4) to binary data. */
  inline def decode(input: String): Either[Error, Array[Byte]] = decode(input, false)

  /** Decodes a Base64 string to binary data. When `urlSafe` is `true`, expects the URL-safe
    * alphabet (RFC 4648 section 5): `-` and `_` are translated back, and padding is restored before
    * decoding.
    */
  def decode(input: String, urlSafe: Boolean): Either[Error, Array[Byte]] =
    val normalised = if urlSafe then fromUrlSafe(input) else input
    PlatformBase64.decode(normalised)

  // On the base64 encode path; a single preallocated pass rather than `String.map`/`filter`.
  private def toUrlSafe(s: String): String =
    val len = s.length
    val end =
      if len >= 2 && s.charAt(len - 1) == '=' then if s.charAt(len - 2) == '=' then len - 2 else len - 1
      else if len >= 1 && s.charAt(len - 1) == '=' then len - 1
      else len
    val chars = new Array[Char](end)
    var i = 0 // scalafix:ok DisableSyntax.var
    while i < end do // scalafix:ok DisableSyntax.while
      s.charAt(i) match
        case '+' => chars(i) = '-'
        case '/' => chars(i) = '_'
        case c   => chars(i) = c
      i += 1
    new String(chars)
  end toUrlSafe

  // On the base64 decode path; a single preallocated pass rather than `String.map`.
  private def fromUrlSafe(s: String): String =
    val pad = (4 - s.length % 4) % 4
    val chars = new Array[Char](s.length + pad)
    var i = 0 // scalafix:ok DisableSyntax.var
    while i < s.length do // scalafix:ok DisableSyntax.while
      s.charAt(i) match
        case '-' => chars(i) = '+'
        case '_' => chars(i) = '/'
        case c   => chars(i) = c
      i += 1
    var j = s.length // scalafix:ok DisableSyntax.var
    while j < chars.length do // scalafix:ok DisableSyntax.while
      chars(j) = '='
      j += 1
    new String(chars)
  end fromUrlSafe

  /** Error produced when decoding invalid Base64 input. */
  final class Error(message: String) extends IllegalArgumentException(message)

  object Error:
    given CanEqual[Error, Error] = CanEqual.derived

end Base64
