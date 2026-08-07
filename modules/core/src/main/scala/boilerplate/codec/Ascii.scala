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

/** ASCII-domain character operations for wire parsers. Locale-free by construction, so the Turkish
  * dotless-i behaviour of `String.toLowerCase` cannot reach a protocol token.
  */
object Ascii:

  private val tokenExtra = "!#$%&'*+-.^_`|~"

  /** Whether `c` is an RFC 9110 `tchar` - the character class of protocol tokens (header names,
    * method names, parameter keys).
    */
  inline def isTokenChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      tokenExtra.indexOf(c.toInt) >= 0

  /** Whether the whole string is an RFC 9110 `token`: one or more `tchar`. */
  def isToken(value: String): Boolean = value.nonEmpty && value.forall(isTokenChar)

  /** ASCII-lowercases `value`, folding `A-Z` alone. Scans once and returns the input unchanged when
    * already lower case, which is the common case for wire-canonical names.
    */
  def lower(value: String): String =
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    var upper = -1
    var i = 0
    while upper < 0 && i < value.length do
      val c = value.charAt(i)
      if c >= 'A' && c <= 'Z' then upper = i
      i += 1
    if upper < 0 then value
    else
      val out = new Array[Char](value.length)
      var j = 0
      while j < value.length do
        val c = value.charAt(j)
        out(j) = if c >= 'A' && c <= 'Z' then (c + 32).toChar else c
        j += 1
      new String(out)
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end lower
end Ascii
