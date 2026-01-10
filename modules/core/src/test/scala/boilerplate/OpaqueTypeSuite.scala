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

// ============================================================================
// Test Fixtures - Opaque Types at Package Level for Proper Given Resolution
// ============================================================================

/** Simple string-based opaque type: must be non-empty. */
opaque type NonEmptyString = String

object NonEmptyString extends OpaqueType[NonEmptyString]:
  type Type = String
  type Error = IllegalArgumentException

  inline def wrap(s: String): NonEmptyString = s
  inline def unwrap(s: NonEmptyString): String = s

  def validate(s: String): Error | Unit =
    if s.nonEmpty then ()
    else new IllegalArgumentException("String must be non-empty")

/** Numeric opaque type: must be positive. */
opaque type PositiveInt = Int

object PositiveInt extends OpaqueType[PositiveInt]:
  type Type = Int
  type Error = IllegalArgumentException

  inline def wrap(n: Int): PositiveInt = n
  inline def unwrap(n: PositiveInt): Int = n

  def validate(n: Int): Error | Unit =
    if n > 0 then ()
    else new IllegalArgumentException(s"Value must be positive, got: $n")

/** Domain-specific error type for Email validation. */
final class EmailError(message: String) extends RuntimeException(message)

object EmailError:
  given CanEqual[EmailError, EmailError] = CanEqual.derived

/** Email opaque type with custom error type. */
opaque type Email = String

object Email extends OpaqueType[Email]:
  type Type = String
  type Error = EmailError

  inline def wrap(s: String): Email = s
  inline def unwrap(e: Email): String = e

  def validate(s: String): Error | Unit =
    if s.contains("@") then ()
    else new EmailError(s"Invalid email format: $s")

// ============================================================================
// Phantom Type Fixtures - Test OpaqueType with Phantom Type Parameters
// ============================================================================

/** Marker traits for type-level unit tagging. */
sealed trait Metres
sealed trait Feet

/** Distance opaque type parameterised by unit phantom type. */
opaque type Distance[U] = Double

object Distance:
  /** Companion for Metres-tagged Distance. */
  object Metres extends OpaqueType[Distance[boilerplate.Metres]]:
    type Type = Double
    type Error = IllegalArgumentException

    inline def wrap(d: Double): Distance[boilerplate.Metres] = d
    inline def unwrap(d: Distance[boilerplate.Metres]): Double = d

    def validate(d: Double): Error | Unit =
      if d >= 0.0 then ()
      else new IllegalArgumentException(s"Distance cannot be negative: $d")

  /** Companion for Feet-tagged Distance. */
  object Feet extends OpaqueType[Distance[boilerplate.Feet]]:
    type Type = Double
    type Error = IllegalArgumentException

    inline def wrap(d: Double): Distance[boilerplate.Feet] = d
    inline def unwrap(d: Distance[boilerplate.Feet]): Double = d

    def validate(d: Double): Error | Unit =
      if d >= 0.0 then ()
      else new IllegalArgumentException(s"Distance cannot be negative: $d")
end Distance

class OpaqueTypeSuite extends FunSuite:

  // -------------------------------------------------------------------------
  // from: Safe Construction
  // -------------------------------------------------------------------------

  test("from returns Right for valid input"):
    assertEquals(NonEmptyString.from("hello"), Right(NonEmptyString.wrap("hello")))

  test("from returns Right for boundary valid input"):
    assertEquals(NonEmptyString.from("x"), Right(NonEmptyString.wrap("x")))
    assertEquals(PositiveInt.from(1), Right(PositiveInt.wrap(1)))

  test("from returns Left for invalid input"):
    assert(NonEmptyString.from("").isLeft)
    assert(PositiveInt.from(0).isLeft)
    assert(PositiveInt.from(-5).isLeft)

  test("from Left contains correct error type"):
    NonEmptyString.from("") match
      case Left(e: IllegalArgumentException) => assert(e.getMessage.contains("non-empty"))
      case other                             => fail(s"Expected Left(IllegalArgumentException), got: $other")

  test("from preserves custom error type"):
    Email.from("invalid") match
      case Left(e: EmailError) => assert(e.getMessage.contains("Invalid email format"))
      case other               => fail(s"Expected Left(EmailError), got: $other")

  test("from Right value equals wrapped value"):
    val result = NonEmptyString.from("test")
    assertEquals(result.map(NonEmptyString.unwrap), Right("test"))

  // -------------------------------------------------------------------------
  // fromUnsafe: Throwing Construction
  // -------------------------------------------------------------------------

  test("fromUnsafe returns value for valid input"):
    assertEquals(NonEmptyString.unwrap(NonEmptyString.fromUnsafe("hello")), "hello")
    assertEquals(PositiveInt.unwrap(PositiveInt.fromUnsafe(42)), 42)

  test("fromUnsafe throws for invalid input"):
    intercept[IllegalArgumentException]:
      NonEmptyString.fromUnsafe("")

  test("fromUnsafe throws correct error type"):
    val ex = intercept[IllegalArgumentException]:
      PositiveInt.fromUnsafe(-1)
    assert(ex.getMessage.contains("-1"))

  test("fromUnsafe throws custom error type"):
    val ex = intercept[EmailError]:
      Email.fromUnsafe("not-an-email")
    assert(ex.getMessage.contains("Invalid email format"))

  // -------------------------------------------------------------------------
  // as: Extension Method Safe Construction
  // -------------------------------------------------------------------------

  test("as extension returns Right for valid input"):
    import NonEmptyString.given
    assertEquals("hello".as[NonEmptyString], Right(NonEmptyString.wrap("hello")))

  test("as extension returns Left for invalid input"):
    import NonEmptyString.given
    assert("".as[NonEmptyString].isLeft)

  test("as extension works with Int underlying type"):
    import PositiveInt.given
    assertEquals(42.as[PositiveInt], Right(PositiveInt.wrap(42)))
    assert(0.as[PositiveInt].isLeft)

  test("as extension preserves custom error type"):
    import Email.given
    "bad".as[Email] match
      case Left(_: EmailError) => () // expected
      case other               => fail(s"Expected Left(EmailError), got: $other")

  // -------------------------------------------------------------------------
  // asUnsafe: Extension Method Throwing Construction
  // -------------------------------------------------------------------------

  test("asUnsafe extension returns value for valid input"):
    import NonEmptyString.given
    assertEquals(NonEmptyString.unwrap("hello".asUnsafe[NonEmptyString]), "hello")

  test("asUnsafe extension throws for invalid input"):
    import NonEmptyString.given
    intercept[IllegalArgumentException]:
      "".asUnsafe[NonEmptyString]

  test("asUnsafe extension throws custom error type"):
    import Email.given
    intercept[EmailError]:
      "invalid".asUnsafe[Email]

  // -------------------------------------------------------------------------
  // unwrap: Extension Method Extraction
  // -------------------------------------------------------------------------

  test("unwrap extension extracts underlying value"):
    import NonEmptyString.given
    val wrapped = NonEmptyString.wrap("hello")
    assertEquals(wrapped.unwrap, "hello")

  test("unwrap extension works with Int underlying type"):
    import PositiveInt.given
    val wrapped = PositiveInt.wrap(42)
    assertEquals(wrapped.unwrap, 42)

  test("unwrap extension round-trips with construction"):
    import Email.given
    val original = "test@example.com"
    val constructed = original.asUnsafe[Email]
    assertEquals(constructed.unwrap, original)

  test("unwrap extension works with phantom type parameter"):
    import Distance.Metres.given
    val metres = Distance.Metres.wrap(100.0)
    assertEquals(metres.unwrap, 100.0)

  // -------------------------------------------------------------------------
  // validate: Union Type Semantics
  // -------------------------------------------------------------------------

  test("validate returns Unit for valid input"):
    val result = NonEmptyString.validate("valid")
    assertEquals(result, ())

  test("validate returns error instance for invalid input"):
    val result = NonEmptyString.validate("")
    result match
      case e: IllegalArgumentException => assert(e.getMessage.contains("non-empty"))
      case _                           => fail("Expected error, got ()")

  test("validate error is subtype of Throwable"):
    val result = Email.validate("bad")
    result match
      case _: EmailError => assert(true)
      case _             => fail("Expected EmailError")

  // -------------------------------------------------------------------------
  // wrap/unwrap: Low-Level Operations
  // -------------------------------------------------------------------------

  test("wrap creates opaque type without validation"):
    // wrap bypasses validation - this is intentional for trusted contexts
    val wrapped = NonEmptyString.wrap("")
    assertEquals(NonEmptyString.unwrap(wrapped), "")

  test("unwrap extracts underlying value"):
    val wrapped = NonEmptyString.wrap("test")
    assertEquals(NonEmptyString.unwrap(wrapped), "test")

  test("wrap and unwrap are inverses"):
    val original = "hello"
    assertEquals(NonEmptyString.unwrap(NonEmptyString.wrap(original)), original)

  // -------------------------------------------------------------------------
  // OpaqueType.apply: Summoning
  // -------------------------------------------------------------------------

  test("OpaqueType.apply summons instance"):
    import NonEmptyString.given
    val instance = OpaqueType[NonEmptyString]
    // Verify summoned instance is the same singleton
    assert(instance eq NonEmptyString)

  test("OpaqueType.apply returns same instance"):
    import NonEmptyString.given
    val a = OpaqueType[NonEmptyString]
    val b = OpaqueType[NonEmptyString]
    assert(a eq b) // Same singleton instance

  // -------------------------------------------------------------------------
  // CanEqual: Multiversal Equality
  // -------------------------------------------------------------------------

  test("CanEqual allows same-type comparison"):
    val a = NonEmptyString.wrap("hello")
    val b = NonEmptyString.wrap("hello")
    assertEquals(a, b)

  test("CanEqual detects inequality"):
    val a = NonEmptyString.wrap("hello")
    val b = NonEmptyString.wrap("world")
    assertNotEquals(a, b)

  // Note: Cross-type comparisons (NonEmptyString vs Email) are compile errors
  // due to CanEqual - we don't test compile errors in runtime tests

  // -------------------------------------------------------------------------
  // Type Member: Error <: Throwable Constraint
  // -------------------------------------------------------------------------

  test("Error type member is accessible"):
    // This is a compile-time check - if Error wasn't properly constrained,
    // we couldn't use it with intercept or catch
    val _: NonEmptyString.Error = new IllegalArgumentException("test")
    val _: Email.Error = new EmailError("test")

  test("Error type flows through from"):
    // Type inference check: result type includes refined Error
    val result: Either[IllegalArgumentException, NonEmptyString] = NonEmptyString.from("test")
    assert(result.isRight)

  test("Error type flows through extension"):
    import Email.given
    val result: Either[EmailError, Email] = "test@example.com".as[Email]
    assert(result.isRight)

  // -------------------------------------------------------------------------
  // Phantom Type: Distance[U] with Unit Tags
  // -------------------------------------------------------------------------

  test("phantom type from succeeds for valid input"):
    assertEquals(Distance.Metres.from(100.0), Right(Distance.Metres.wrap(100.0)))
    assertEquals(Distance.Feet.from(328.0), Right(Distance.Feet.wrap(328.0)))

  test("phantom type from fails for invalid input"):
    assert(Distance.Metres.from(-1.0).isLeft)
    assert(Distance.Feet.from(-1.0).isLeft)

  test("phantom type fromUnsafe works"):
    val m = Distance.Metres.fromUnsafe(50.0)
    assertEquals(Distance.Metres.unwrap(m), 50.0)

  test("phantom type fromUnsafe throws for invalid"):
    intercept[IllegalArgumentException]:
      Distance.Metres.fromUnsafe(-1.0)

  test("phantom type extension as works"):
    import Distance.Metres.given
    assertEquals(100.0.as[Distance[Metres]], Right(Distance.Metres.wrap(100.0)))

  test("phantom type extension asUnsafe works"):
    import Distance.Feet.given
    val d = 50.0.asUnsafe[Distance[Feet]]
    assertEquals(Distance.Feet.unwrap(d), 50.0)

  test("phantom types are distinct at compile time"):
    // Metres and Feet are type-incompatible
    val metres: Distance[Metres] = Distance.Metres.wrap(100.0)
    val feet: Distance[Feet] = Distance.Feet.wrap(328.0)
    // The following would be a compile error:
    // assertEquals(metres, feet)
    // We just verify they exist with correct types
    assertEquals(Distance.Metres.unwrap(metres), 100.0)
    assertEquals(Distance.Feet.unwrap(feet), 328.0)

  test("phantom type CanEqual only allows same-unit comparison"):
    val m1 = Distance.Metres.wrap(100.0)
    val m2 = Distance.Metres.wrap(100.0)
    assertEquals(m1, m2)
    // Comparing Distance[Metres] to Distance[Feet] is a compile error

  // -------------------------------------------------------------------------
  // Error Messages: Content Verification
  // -------------------------------------------------------------------------

  test("error message contains relevant information"):
    PositiveInt.from(-42) match
      case Left(e)  => assert(e.getMessage.contains("-42"))
      case Right(_) => fail("Expected Left")

  test("email error message includes input"):
    Email.from("notvalid") match
      case Left(e)  => assert(e.getMessage.contains("notvalid"))
      case Right(_) => fail("Expected Left")

  // -------------------------------------------------------------------------
  // Edge Cases
  // -------------------------------------------------------------------------

  test("whitespace-only string is valid for NonEmptyString"):
    // Design decision: NonEmptyString checks nonEmpty, not non-blank
    assert(NonEmptyString.from("   ").isRight)

  test("zero is invalid for PositiveInt"):
    assert(PositiveInt.from(0).isLeft)

  test("negative zero for Distance is valid"):
    // IEEE 754: -0.0 == 0.0, and 0.0 >= 0.0 is true
    assert(Distance.Metres.from(-0.0).isRight)

  test("positive infinity is valid for Distance"):
    assert(Distance.Metres.from(Double.PositiveInfinity).isRight)

  test("negative infinity is invalid for Distance"):
    assert(Distance.Metres.from(Double.NegativeInfinity).isLeft)

  test("NaN comparison for Distance"):
    // NaN >= 0.0 is false, so NaN should be invalid
    assert(Distance.Metres.from(Double.NaN).isLeft)

  // -------------------------------------------------------------------------
  // Chained Validation Patterns
  // -------------------------------------------------------------------------

  test("for-comprehension chains multiple validated types"):
    val result = for
      name <- NonEmptyString.from("Alice")
      age <- PositiveInt.from(30)
      email <- Email.from("alice@example.com")
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
      name <- NonEmptyString.from("")
      email <-
        evaluatedEmail = true; Email.from("test@test.com")
    yield (name, email)

    assert(result.isLeft)
    assert(!evaluatedEmail, "Email validation should not have been evaluated")

end OpaqueTypeSuite
