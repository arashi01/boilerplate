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
package boilerplate.crossmod

import munit.FunSuite

import boilerplate.*

// Sits in a child package rather than `boilerplate` so the opaque types' underlying representation
// is abstract here, exercising that the trait's final `inline` bodies - which call the PROTECTED
// `wrap` and `validate` - expand soundly at a call site outside the defining scope.
class CrossModuleUnwrapSuite extends FunSuite:

  test("of round-trips through unwrap for a String-based opaque"):
    val constructed = NonEmptyString.of("hello")
    assertEquals(constructed.map(NonEmptyString.unwrap), Right("hello"))

  test("of round-trips through unwrap for an Int-based opaque"):
    val constructed = PositiveInt.of(42)
    assertEquals(constructed.map(PositiveInt.unwrap), Right(42))

  test("of returns Left for invalid input across the boundary"):
    assert(NonEmptyString.of("").isLeft)

  test("of round-trips for a custom-error opaque"):
    val original = "user@domain.com"
    val constructed = Email.of(original)
    assertEquals(constructed.map(Email.unwrap), Right(original))

  test("ofUnsafe constructs and unwrap extracts across the boundary"):
    val value: NonEmptyString = NonEmptyString.ofUnsafe("hello")
    val underlying: String = NonEmptyString.unwrap(value)
    assertEquals(underlying, "hello")

  test("ofUnsafe throws across the boundary"):
    intercept[IllegalArgumentException]:
      NonEmptyString.ofUnsafe("")

  test("ofUnsafe round-trips for a phantom-typed opaque"):
    val wrapped = Distance.Metres.ofUnsafe(100.0)
    val result: Double = Distance.Metres.unwrap(wrapped)
    assertEquals(result, 100.0)

  test("the compile-time apply expands at a call site outside the defining scope"):
    assertEquals(CheckedPositive.unwrap(CheckedPositive(7)), 7)

  test("wrap stays inaccessible from another package"):
    val errors = scala.compiletime.testing.typeCheckErrors:
      """
      boilerplate.PositiveInt.wrap(0)
      """
    assert(errors.nonEmpty, "wrap was accessible outside the companion's scope")

  test("SecretToken constructs and extracts across the boundary"):
    val token = SecretToken.ofUnsafe("my-secret")
    val underlying: String = SecretToken.unwrap(token)
    assertEquals(underlying, "my-secret")

end CrossModuleUnwrapSuite
