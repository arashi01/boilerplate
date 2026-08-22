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

opaque type NonEmptyString = String

object NonEmptyString extends OpaqueType[NonEmptyString, String], OpaqueType.Eq[NonEmptyString]:
  type Error = IllegalArgumentException

  protected inline def wrap(s: String): NonEmptyString = s
  inline def unwrap(s: NonEmptyString): String = s
  // The runtime-delegating shape the trait permits; the compile-time shape is CheckedPositive's.
  inline def apply(inline value: String): NonEmptyString = ofUnsafe(value)

  protected inline def validate(s: String): Either[Error, String] =
    if s.nonEmpty then Right(s)
    else Left(new IllegalArgumentException("String must be non-empty"))

opaque type PositiveInt = Int

object PositiveInt extends OpaqueType[PositiveInt, Int], OpaqueType.Eq[PositiveInt]:
  type Error = IllegalArgumentException

  protected inline def wrap(n: Int): PositiveInt = n
  inline def unwrap(n: PositiveInt): Int = n
  inline def apply(inline value: Int): PositiveInt = ofUnsafe(value)

  protected inline def validate(n: Int): Either[Error, Int] =
    if n > 0 then Right(n)
    else Left(new IllegalArgumentException(s"$n must be positive"))

final class EmailError(message: String) extends RuntimeException(message)

object EmailError:
  given CanEqual[EmailError, EmailError] = CanEqual.derived

opaque type Email = String

object Email extends OpaqueType[Email, String], OpaqueType.Eq[Email]:
  type Error = EmailError

  protected inline def wrap(s: String): Email = s
  inline def unwrap(e: Email): String = e
  inline def apply(inline value: String): Email = ofUnsafe(value)

  protected inline def validate(s: String): Either[Error, String] =
    if s.contains("@") then Right(s)
    else Left(new EmailError(s"Invalid email format: $s"))

// Omits OpaqueType.Eq deliberately, so == on SecretToken is a compile error.
opaque type SecretToken = String

object SecretToken extends OpaqueType[SecretToken, String]:
  type Error = IllegalArgumentException

  protected inline def wrap(s: String): SecretToken = s
  inline def unwrap(s: SecretToken): String = s
  inline def apply(inline value: String): SecretToken = ofUnsafe(value)

  protected inline def validate(s: String): Either[Error, String] =
    if s.nonEmpty then Right(s)
    else Left(new IllegalArgumentException("Token must be non-empty"))

  // The deliberate, author-scoped trusted seam over the protected `wrap`.
  private[boilerplate] inline def trusted(s: String): SecretToken = wrap(s)
end SecretToken

sealed trait Metres
sealed trait Feet

opaque type Distance[U] = Double

object Distance:
  object Metres extends OpaqueType[Distance[boilerplate.Metres], Double], OpaqueType.Eq[Distance[boilerplate.Metres]]:
    type Error = IllegalArgumentException

    protected inline def wrap(d: Double): Distance[boilerplate.Metres] = d
    inline def unwrap(d: Distance[boilerplate.Metres]): Double = d
    inline def apply(inline value: Double): Distance[boilerplate.Metres] = ofUnsafe(value)

    protected inline def validate(d: Double): Either[Error, Double] =
      if d >= 0.0 then Right(d)
      else Left(new IllegalArgumentException(s"Distance cannot be negative: $d"))

  object Feet extends OpaqueType[Distance[boilerplate.Feet], Double], OpaqueType.Eq[Distance[boilerplate.Feet]]:
    type Error = IllegalArgumentException

    protected inline def wrap(d: Double): Distance[boilerplate.Feet] = d
    inline def unwrap(d: Distance[boilerplate.Feet]): Double = d
    inline def apply(inline value: Double): Distance[boilerplate.Feet] = ofUnsafe(value)

    protected inline def validate(d: Double): Either[Error, Double] =
      if d >= 0.0 then Right(d)
      else Left(new IllegalArgumentException(s"Distance cannot be negative: $d"))
end Distance

// A wire-form type: its representation IS its wire text, so its doors are parse/render.
final case class HashError(detail: String) extends TypedError(detail, None)

opaque type PasswordHash = String

object PasswordHash extends OpaqueType.Wire[PasswordHash], OpaqueType.Eq[PasswordHash]:
  type Error = HashError

  protected inline def wrap(text: String): PasswordHash = text
  def render(value: PasswordHash): String = value

  protected inline def validate(text: String): Either[HashError, String] =
    if text.startsWith("$argon2id$") then Right(text) else Left(HashError("not a PHC string"))

  inline def apply(inline text: String): PasswordHash =
    inline if text == "" then compiletime.error("PasswordHash cannot be empty") else wrap(text)
end PasswordHash

opaque type CheckedPositive = Int

object CheckedPositive extends OpaqueType[CheckedPositive, Int], OpaqueType.Eq[CheckedPositive]:
  type Error = IllegalArgumentException

  protected inline def wrap(n: Int): CheckedPositive = n
  inline def unwrap(n: CheckedPositive): Int = n

  protected inline def validate(n: Int): Either[Error, Int] =
    if n > 0 then Right(n)
    else Left(new IllegalArgumentException(s"$n must be positive"))

  inline def apply(inline value: Int): CheckedPositive =
    inline if value <= 0 then compiletime.error("value must be positive")
    else wrap(value)
end CheckedPositive

class OpaqueTypeSuite extends FunSuite:

  test("of returns Right for valid input"):
    assertEquals(NonEmptyString.of("hello"), Right(NonEmptyString.ofUnsafe("hello")))

  test("of returns Right for boundary valid input"):
    assertEquals(NonEmptyString.of("x"), Right(NonEmptyString.ofUnsafe("x")))
    assertEquals(PositiveInt.of(1), Right(PositiveInt.ofUnsafe(1)))

  test("of returns Left for invalid input"):
    assert(NonEmptyString.of("").isLeft)
    assert(PositiveInt.of(0).isLeft)
    assert(PositiveInt.of(-5).isLeft)

  test("of Left contains correct error type"):
    NonEmptyString.of("") match
      case Left(e: IllegalArgumentException) => assert(e.getMessage.contains("non-empty"))
      case other                             => fail(s"Expected Left(IllegalArgumentException), got: $other")

  test("of preserves custom error type"):
    Email.of("invalid") match
      case Left(e: EmailError) => assert(e.getMessage.contains("Invalid email format"))
      case other               => fail(s"Expected Left(EmailError), got: $other")

  test("of Right value round-trips through unwrap"):
    val result = NonEmptyString.of("test")
    assertEquals(result.map(NonEmptyString.unwrap), Right("test"))

  test("ofUnsafe returns value for valid input"):
    assertEquals(NonEmptyString.unwrap(NonEmptyString.ofUnsafe("hello")), "hello")
    assertEquals(PositiveInt.unwrap(PositiveInt.ofUnsafe(42)), 42)

  test("ofUnsafe throws for invalid input"):
    intercept[IllegalArgumentException]:
      NonEmptyString.ofUnsafe("")

  test("ofUnsafe throws correct error type"):
    val ex = intercept[IllegalArgumentException]:
      PositiveInt.ofUnsafe(-1)
    assert(ex.getMessage.contains("-1"))

  test("ofUnsafe throws custom error type"):
    val ex = intercept[EmailError]:
      Email.ofUnsafe("not-an-email")
    assert(ex.getMessage.contains("Invalid email format"))

  test("wrap is protected - unvalidated construction is a compile error outside the companion"):
    val errors = scala.compiletime.testing.typeCheckErrors:
      """
      boilerplate.NonEmptyString.wrap("")
      """
    assert(errors.nonEmpty, "wrap was accessible outside its companion")

  test("an author-scoped trusted seam over wrap constructs without validation"):
    assertEquals(SecretToken.unwrap(SecretToken.trusted("")), "")

  test("OpaqueType.Eq allows same-type comparison when mixed in"):
    val a = NonEmptyString.ofUnsafe("hello")
    val b = NonEmptyString.ofUnsafe("hello")
    assertEquals(a, b)

  test("OpaqueType.Eq detects inequality when mixed in"):
    val a = NonEmptyString.ofUnsafe("hello")
    val b = NonEmptyString.ofUnsafe("world")
    assertNotEquals(a, b)

  test("CanEqual is absent when Eq is omitted - compile error on =="):
    val errors = scala.compiletime.testing.typeCheckErrors:
      """
      val a: boilerplate.SecretToken = boilerplate.SecretToken.ofUnsafe("abc")
      val b: boilerplate.SecretToken = boilerplate.SecretToken.ofUnsafe("abc")
      a == b
      """
    assert(errors.exists(_.message.contains("cannot be compared with == or !=")), errors.map(_.message).mkString)

  test("SecretToken of succeeds for valid input"):
    assert(SecretToken.of("my-secret").isRight)

  test("SecretToken of fails for empty input"):
    assert(SecretToken.of("").isLeft)

  test("SecretToken unwrap works"):
    val token = SecretToken.ofUnsafe("my-secret")
    assertEquals(SecretToken.unwrap(token), "my-secret")

  test("Error type member is accessible"):
    // Compile-time only: each companion's refined `Error` member must be ascribable from its
    // concrete exception type.
    val _: NonEmptyString.Error = new IllegalArgumentException("test")
    val _: Email.Error = new EmailError("test")

  test("Error type flows through of"):
    val result: Either[IllegalArgumentException, NonEmptyString] = NonEmptyString.of("test")
    assert(result.isRight)

  test("phantom type of succeeds for valid input"):
    assertEquals(Distance.Metres.of(100.0), Right(Distance.Metres.ofUnsafe(100.0)))
    assertEquals(Distance.Feet.of(328.0), Right(Distance.Feet.ofUnsafe(328.0)))

  test("phantom type of fails for invalid input"):
    assert(Distance.Metres.of(-1.0).isLeft)
    assert(Distance.Feet.of(-1.0).isLeft)

  test("phantom type ofUnsafe works"):
    val m = Distance.Metres.ofUnsafe(50.0)
    assertEquals(Distance.Metres.unwrap(m), 50.0)

  test("phantom type ofUnsafe throws for invalid"):
    intercept[IllegalArgumentException]:
      Distance.Metres.ofUnsafe(-1.0)

  test("phantom types are distinct at compile time"):
    val metres: Distance[Metres] = Distance.Metres.ofUnsafe(100.0)
    val feet: Distance[Feet] = Distance.Feet.ofUnsafe(328.0)
    assertEquals(Distance.Metres.unwrap(metres), 100.0)
    assertEquals(Distance.Feet.unwrap(feet), 328.0)

  test("phantom type CanEqual only allows same-unit comparison"):
    val m1 = Distance.Metres.ofUnsafe(100.0)
    val m2 = Distance.Metres.ofUnsafe(100.0)
    assertEquals(m1, m2)
    // Comparing Distance[Metres] to Distance[Feet] is a compile error

  test("error message contains relevant information"):
    PositiveInt.of(-42) match
      case Left(e)  => assert(e.getMessage.contains("-42"))
      case Right(_) => fail("Expected Left")

  test("email error message includes input"):
    Email.of("notvalid") match
      case Left(e)  => assert(e.getMessage.contains("notvalid"))
      case Right(_) => fail("Expected Left")

  test("whitespace-only string is valid for NonEmptyString"):
    // nonEmpty, not non-blank: whitespace-only strings pass.
    assert(NonEmptyString.of("   ").isRight)

  test("zero is invalid for PositiveInt"):
    assert(PositiveInt.of(0).isLeft)

  test("negative zero for Distance is valid"):
    // IEEE 754: -0.0 == 0.0, and 0.0 >= 0.0 is true
    assert(Distance.Metres.of(-0.0).isRight)

  test("positive infinity is valid for Distance"):
    assert(Distance.Metres.of(Double.PositiveInfinity).isRight)

  test("negative infinity is invalid for Distance"):
    assert(Distance.Metres.of(Double.NegativeInfinity).isLeft)

  test("NaN comparison for Distance"):
    // NaN >= 0.0 is false, so NaN should be invalid
    assert(Distance.Metres.of(Double.NaN).isLeft)

  test("for-comprehension chains multiple validated types"):
    val result = for
      name <- NonEmptyString.of("Alice")
      age <- PositiveInt.of(30)
      email <- Email.of("alice@example.com")
    yield (name, age, email)

    assert(result.isRight)
    result.foreach { case (n, a, e) =>
      assertEquals(NonEmptyString.unwrap(n), "Alice")
      assertEquals(PositiveInt.unwrap(a), 30)
      assertEquals(Email.unwrap(e), "alice@example.com")
    }

  test("for-comprehension short-circuits on first error"):
    var evaluatedEmail = false // scalafix:ok DisableSyntax.var

    val result = for
      name <- NonEmptyString.of("")
      email <-
        evaluatedEmail = true; Email.of("test@test.com")
    yield (name, email)

    assert(result.isLeft)
    assert(!evaluatedEmail, "Email validation should not have been evaluated")

  test("apply succeeds for valid input"):
    assertEquals(NonEmptyString.unwrap(NonEmptyString("hello")), "hello")
    assertEquals(PositiveInt.unwrap(PositiveInt(42)), 42)

  test("apply delegating to ofUnsafe throws for invalid input"):
    intercept[IllegalArgumentException]:
      NonEmptyString("")

  test("apply delegating to ofUnsafe throws custom error type"):
    intercept[EmailError]:
      Email("not-an-email")

  test("apply with compile-time validated override succeeds for valid literal"):
    val p = CheckedPositive(42)
    assertEquals(CheckedPositive.unwrap(p), 42)

  test("apply compile-time error for invalid literal"):
    val errors = scala.compiletime.testing.typeCheckErrors("boilerplate.CheckedPositive(0)")
    assert(errors.exists(_.message.contains("value must be positive")), errors.map(_.message).mkString)

  test("apply compile-time error for negative literal"):
    val errors = scala.compiletime.testing.typeCheckErrors("boilerplate.CheckedPositive(-1)")
    assert(errors.exists(_.message.contains("value must be positive")), errors.map(_.message).mkString)

  test("Wire parse and render round-trip through the derived codec"):
    val codec = summon[ValueCodec[PasswordHash]]
    assertEquals(codec.decode("$argon2id$x").map(codec.encode), Right("$argon2id$x"))
    assertEquals(PasswordHash.parse("$argon2id$x").map(PasswordHash.render), Right("$argon2id$x"))

  test("Wire refuses text with the companion's own error, and parseUnsafe throws it"):
    assertEquals(PasswordHash.parse("plain"), Left(HashError("not a PHC string")))
    val thrown = intercept[HashError](PasswordHash.parseUnsafe("plain"))
    assertEquals(thrown.detail, "not a PHC string")

  test("Wire's codec carries the companion's error member exactly"):
    val _ = summon[ValueCodec.Aux[PasswordHash, HashError]]
    assert(!scala.compiletime.testing.typeChecks("summon[boilerplate.ValueCodec.Aux[boilerplate.PasswordHash, Nothing]]"))

  test("Wire has one door: there is no of for a type whose representation is its wire text"):
    assert(!scala.compiletime.testing.typeChecks("""boilerplate.PasswordHash.of("x")"""))
    assert(!scala.compiletime.testing.typeChecks("""boilerplate.PasswordHash.ofUnsafe("x")"""))

  test("Wire's literal door validates at compile time"):
    assert(!scala.compiletime.testing.typeChecks("""boilerplate.PasswordHash("")"""))
    assertEquals(PasswordHash.render(PasswordHash("$argon2id$x")), "$argon2id$x")

  test("Codec's self-type rejects a companion whose representation is not the one it names"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "object Bad extends boilerplate.OpaqueType[boilerplate.WirePort, Int], boilerplate.OpaqueType.Codec[boilerplate.WirePort, String]"
    )
    assert(errors.nonEmpty, "a Codec mixin naming the wrong representation was accepted")

end OpaqueTypeSuite
