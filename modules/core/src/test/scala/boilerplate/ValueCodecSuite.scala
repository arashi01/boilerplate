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
package boilerplate

import munit.FunSuite

final case class WireError(detail: String) extends TypedError(detail, None)

opaque type WireId = String
object WireId extends OpaqueType[WireId, String], OpaqueType.Eq[WireId], OpaqueType.Codec[WireId]:
  type Error = WireError
  protected inline def wrap(s: String): WireId = s
  def unwrap(id: WireId): String = id
  protected inline def validate(s: String): Either[WireError, String] =
    if s.nonEmpty then Right(s) else Left(WireError("empty"))
  inline def apply(inline value: String): WireId =
    inline if value == "" then scala.compiletime.error("WireId cannot be empty") else wrap(value)

// Total accept: the derived codec is infallible.
opaque type WireToken = String
object WireToken extends OpaqueType[WireToken, String], OpaqueType.Codec[WireToken]:
  type Error = Nothing
  protected inline def wrap(s: String): WireToken = s
  def unwrap(t: WireToken): String = t
  protected inline def validate(s: String): Either[Nothing, String] = Right(s)
  inline def apply(inline value: String): WireToken = wrap(value)

// Normalising: validate canonicalises to ASCII lower case; wrap stays a pure cast.
opaque type WireHeader = String
object WireHeader extends OpaqueType[WireHeader, String], OpaqueType.Eq[WireHeader], OpaqueType.Codec[WireHeader]:
  type Error = WireError
  protected inline def wrap(s: String): WireHeader = s
  def unwrap(h: WireHeader): String = h
  protected inline def validate(s: String): Either[WireError, String] =
    if s.isEmpty then Left(WireError("empty"))
    else if !codec.Ascii.isToken(s) then Left(WireError("not a token"))
    else Right(codec.Ascii.lower(s))
  inline def apply(inline value: String): WireHeader = ofUnsafe(value)

// Non-String representation: the documented hand-written given through the constructor.
opaque type WirePort = Int
object WirePort extends OpaqueType[WirePort, Int], OpaqueType.Eq[WirePort]:
  type Error = WireError
  protected inline def wrap(i: Int): WirePort = i
  def unwrap(p: WirePort): Int = p
  protected inline def validate(i: Int): Either[WireError, Int] =
    if i >= 0 && i <= 65535 then Right(i) else Left(WireError("out of range"))
  inline def apply(inline value: Int): WirePort =
    inline if value < 0 || value > 65535 then scala.compiletime.error("port out of range") else wrap(value)

  given valueCodec: ValueCodec.Aux[WirePort, WireError] =
    ValueCodec(s => s.toIntOption.toRight(WireError("not an integer")).flatMap(i => of(i)), p => unwrap(p).toString)
end WirePort

class ValueCodecSuite extends FunSuite:

  test("the primitive givens round-trip and reject with constraint-naming messages"):
    assertEquals(summon[ValueCodec[Int]].decode("17"), Right(17))
    assertEquals(summon[ValueCodec[Int]].encode(17), "17")
    assertEquals(summon[ValueCodec[Int]].decode("x"), Left(ValueCodec.Invalid("not an integer")))
    assertEquals(summon[ValueCodec[Long]].decode("9007199254740993"), Right(9007199254740993L))
    assertEquals(summon[ValueCodec[Boolean]].decode("true"), Right(true))
    assertEquals(summon[ValueCodec[Boolean]].decode("TRUE"), Left(ValueCodec.Invalid("not a boolean")))
    assertEquals(summon[ValueCodec[String]].decode("anything"), Right("anything"))

  test("primitive given failure messages never carry the offending input"):
    summon[ValueCodec[Int]].decode("secret-value-123") match
      case Left(e)  => assert(!e.getMessage.contains("secret-value-123"))
      case Right(_) => fail("expected a decode failure")

  test("the given set carries the Error member precisely"):
    val _ = summon[ValueCodec.Aux[String, Nothing]]
    val _ = summon[ValueCodec.Aux[Int, ValueCodec.Invalid]]
    val _ = summon[ValueCodec.Aux[Long, ValueCodec.Invalid]]
    val _ = summon[ValueCodec.Aux[Boolean, ValueCodec.Invalid]]

  test("the Codec mixin derives decode from of and encode from unwrap"):
    val codec = summon[ValueCodec.Aux[WireId, WireError]]
    assertEquals(codec.decode("abc").map(codec.encode), Right("abc"))
    assertEquals(codec.decode(""), Left(WireError("empty")))

  test("the mixin preserves the companion's Error member exactly"):
    val _ = summon[ValueCodec.Aux[WireId, WireError]]
    assert(!scala.compiletime.testing.typeChecks("summon[boilerplate.ValueCodec.Aux[boilerplate.WireId, Nothing]]"))

  test("a total-accept companion yields an infallible codec"):
    val codec = summon[ValueCodec.Aux[WireToken, Nothing]]
    assertEquals(codec.decode("anything").map(codec.encode), Right("anything"))

  test("a normalising companion decodes to the canonical value and encodes the canonical text"):
    val codec = summon[ValueCodec.Aux[WireHeader, WireError]]
    assertEquals(codec.decode("Content-Type").map(codec.encode), Right("content-type"))
    // Idempotent through re-encoding: the canonical form is a fixed point.
    val once = codec.decode("X-Thing").map(codec.encode)
    assertEquals(once.flatMap(codec.decode).map(codec.encode), once)

  test("a non-String representation writes the given through the constructor, refinement intact"):
    val codec = summon[ValueCodec.Aux[WirePort, WireError]]
    assertEquals(codec.decode("8080").map(codec.encode), Right("8080"))
    assertEquals(codec.decode("x"), Left(WireError("not an integer")))
    assertEquals(codec.decode("70000"), Left(WireError("out of range")))

  test("a ValueCodec for Secret is refused at compile time with the rationale"):
    val errors = scala.compiletime.testing.typeCheckErrors("summon[boilerplate.ValueCodec[boilerplate.Secret]]")
    assert(errors.exists(_.message.contains("must not exist")), s"expected the refusal rationale, got: $errors")

  test("a ValueCodec for Slice is refused at compile time with the rationale"):
    val errors = scala.compiletime.testing.typeCheckErrors("summon[boilerplate.ValueCodec[boilerplate.Slice]]")
    assert(errors.exists(_.message.contains("must not exist")), s"expected the refusal rationale, got: $errors")

  test("the mixin does not imply equality - == on a Codec-only companion stays a compile error"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "(a: boilerplate.WireToken, b: boilerplate.WireToken) => a == b"
    )
    assert(errors.nonEmpty, "expected == to be rejected without the Eq mixin")
end ValueCodecSuite
