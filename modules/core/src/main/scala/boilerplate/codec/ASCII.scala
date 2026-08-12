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
  * dotless-i behaviour of `String.toLowerCase` cannot reach a protocol token, and the whole Unicode
  * classes `Character.isDigit`/`isLetter` admit cannot smuggle a non-ASCII numeral or letter into a
  * wire field.
  */
object ASCII:

  private val tokenExtra = "!#$%&'*+-.^_`|~"

  /** Whether `c` is an ASCII decimal digit, `0-9` alone. */
  inline def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

  /** Whether `c` is an ASCII letter, `A-Z a-z` alone. */
  inline def isLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  /** Whether `c` is an ASCII letter or digit. */
  inline def isAlphanumeric(c: Char): Boolean = isLetter(c) || isDigit(c)

  /** Whether the whole string is ASCII digits: one or more `0-9`. */
  def isDigits(value: String): Boolean = value.nonEmpty && value.forall(isDigit)

  /** Whether the whole string is ASCII letters: one or more `A-Z a-z`. */
  def isLetters(value: String): Boolean = value.nonEmpty && value.forall(isLetter)

  /** Whether the whole string is ASCII letters or digits, one or more. */
  def isAlphanumeric(value: String): Boolean = value.nonEmpty && value.forall(isAlphanumeric)

  /** Reads the whole string as an unsigned decimal over ASCII digits alone: no sign, no separators,
    * leading zeros accepted (fixed-width wire fields pad with them). `None` on an empty string, any
    * non-digit, or overflow past `Int.MaxValue`.
    */
  def uint(value: String): Option[Int] =
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    var acc = 0
    var i = 0
    var bad = value.isEmpty
    while !bad && i < value.length do
      val c = value.charAt(i)
      if c < '0' || c > '9' then bad = true
      else
        val d = c - '0'
        if acc > (Int.MaxValue - d) / 10 then bad = true
        else
          acc = acc * 10 + d
          i += 1
    if bad then None else Some(acc)
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end uint

  /** As [[uint]], reading into `Long`; `None` past `Long.MaxValue`. */
  def ulong(value: String): Option[Long] =
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    var acc = 0L
    var i = 0
    var bad = value.isEmpty
    while !bad && i < value.length do
      val c = value.charAt(i)
      if c < '0' || c > '9' then bad = true
      else
        val d = (c - '0').toLong
        if acc > (Long.MaxValue - d) / 10L then bad = true
        else
          acc = acc * 10L + d
          i += 1
    if bad then None else Some(acc)
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end ulong

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

  /** ASCII-uppercases `value`, folding `a-z` alone; the mirror of [[lower]], with the same
    * unchanged-input fast path.
    */
  def upper(value: String): String =
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    var lowerAt = -1
    var i = 0
    while lowerAt < 0 && i < value.length do
      val c = value.charAt(i)
      if c >= 'a' && c <= 'z' then lowerAt = i
      i += 1
    if lowerAt < 0 then value
    else
      val out = new Array[Char](value.length)
      var j = 0
      while j < value.length do
        val c = value.charAt(j)
        out(j) = if c >= 'a' && c <= 'z' then (c - 32).toChar else c
        j += 1
      new String(out)
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end upper
end ASCII
