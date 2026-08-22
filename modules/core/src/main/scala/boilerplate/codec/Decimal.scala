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

/** Canonical plain-decimal wire text for `BigDecimal` - the money-class rendering seam.
  *
  * `render` emits the ONE plain form of a value: trailing zeros stripped, never scientific
  * notation, negative zero normalised to `0`. `BigDecimal.toString` alone is not wire-safe -
  * `stripTrailingZeros` leaves negative scales that render scientifically (`250` becomes `2.5E+2`),
  * which a plain-decimal parser then refuses.
  *
  * `parse` admits plain forms alone: an optional leading `-`, then ASCII digits with an optional
  * `.` carrying ASCII digits on both sides. An exponent, a `+` sign, a lone or edge `.`, grouping
  * separators, or any non-ASCII digit is [[Malformed]] - `BigDecimal(String)` itself accepts
  * exponents and Unicode digits, so the scan here is the wire contract. `parse` is deliberately
  * wider than `render`'s image (leading and trailing zeros are accepted and normalise away), so a
  * decode built on it is idempotent through re-encoding.
  */
object Decimal:

  def render(value: BigDecimal): String = value.underlying.stripTrailingZeros.toPlainString

  def parse(text: String): Either[Malformed, BigDecimal] =
    // Walks the text once to establish the plain-decimal grammar before handing it to `BigDecimal`,
    // whose own parser accepts the exponents and signs this form refuses; a cursor is what the
    // grammar's three sections need, and money text is read on every inbound amount.
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    val start = if text.startsWith("-") then 1 else 0
    var i = start
    var integerDigits = 0
    while i < text.length && ASCII.isDigit(text.charAt(i)) do
      integerDigits += 1
      i += 1
    if integerDigits == 0 then Left(Malformed("not a plain decimal"))
    else if i == text.length then Right(BigDecimal(new java.math.BigDecimal(text)))
    else if text.charAt(i) != '.' then Left(Malformed("not a plain decimal"))
    else
      var fractionDigits = 0
      i += 1
      while i < text.length && ASCII.isDigit(text.charAt(i)) do
        fractionDigits += 1
        i += 1
      if fractionDigits == 0 || i != text.length then Left(Malformed("not a plain decimal"))
      else Right(BigDecimal(new java.math.BigDecimal(text)))
    // scalafix:on DisableSyntax.var, DisableSyntax.while
  end parse
end Decimal
