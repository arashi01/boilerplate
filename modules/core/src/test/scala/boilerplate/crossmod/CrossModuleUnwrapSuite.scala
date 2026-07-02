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

/** Tests extension methods across module boundaries.
  *
  * The opaque types (`NonEmptyString`, `PositiveInt`, `Email`, `Distance`) are defined in `package
  * boilerplate`. This suite lives in a child package where opaque type equality does NOT hold -
  * simulating the cross-module scenario where the underlying type would be abstract if not properly
  * propagated via transparent inline.
  */
class CrossModuleUnwrapSuite extends FunSuite:

  test("unwrap extension resolves concrete type for String-based opaque"):
    import NonEmptyString.given
    val wrapped = NonEmptyString.fromUnsafe("hello")
    val result: String = wrapped.unwrap
    assertEquals(result, "hello")

  test("unwrap extension resolves concrete type for Int-based opaque"):
    import PositiveInt.given
    val wrapped = PositiveInt.fromUnsafe(42)
    val result: Int = wrapped.unwrap
    assertEquals(result, 42)

  test("unwrap extension resolves concrete type for custom-error opaque"):
    import Email.given
    val wrapped = Email.fromUnsafe("test@example.com")
    val result: String = wrapped.unwrap
    assertEquals(result, "test@example.com")

  test("unwrap extension resolves concrete type for phantom-typed opaque"):
    import Distance.Metres.given
    val wrapped = Distance.Metres.fromUnsafe(100.0)
    val result: Double = wrapped.unwrap
    assertEquals(result, 100.0)

  test("as extension works across module boundary"):
    import NonEmptyString.given
    val result: Either[IllegalArgumentException, NonEmptyString] = "hello".as[NonEmptyString]
    assert(result.isRight)
    assertEquals(result.map(_.unwrap), Right("hello"))

  test("as extension returns Left across module boundary"):
    import NonEmptyString.given
    assert("".as[NonEmptyString].isLeft)

  test("as extension works with Int across module boundary"):
    import PositiveInt.given
    val result: Either[IllegalArgumentException, PositiveInt] = 42.as[PositiveInt]
    assert(result.isRight)

  test("asUnsafe extension works across module boundary"):
    import NonEmptyString.given
    val result: NonEmptyString = "hello".asUnsafe[NonEmptyString]
    val underlying: String = result.unwrap
    assertEquals(underlying, "hello")

  test("asUnsafe extension throws across module boundary"):
    import NonEmptyString.given
    intercept[IllegalArgumentException]:
      "".asUnsafe[NonEmptyString]

  test("from + unwrap round-trips across module boundary"):
    import Email.given
    val original = "user@domain.com"
    val constructed = Email.from(original)
    assertEquals(constructed.map(_.unwrap), Right(original))

  test("asUnsafe + unwrap round-trips across module boundary"):
    import PositiveInt.given
    val original = 99
    val constructed: PositiveInt = original.asUnsafe[PositiveInt]
    val extracted: Int = constructed.unwrap
    assertEquals(extracted, original)

  test("SecretToken extensions work across module boundary"):
    import SecretToken.given
    val token = "my-secret".asUnsafe[SecretToken]
    val underlying: String = token.unwrap
    assertEquals(underlying, "my-secret")

end CrossModuleUnwrapSuite
