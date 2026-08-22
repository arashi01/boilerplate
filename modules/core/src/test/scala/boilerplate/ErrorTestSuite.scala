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

import scala.compiletime.testing.typeCheckErrors
import scala.compiletime.testing.typeChecks
import scala.util.control.NoStackTrace

import munit.FunSuite

// A module object outside any error root: the arm shape whose synthesised `TypeTest` mis-casts.
object Alpha extends Exception("alpha") with NoStackTrace

sealed abstract class TestError(message: String) extends TypedError(message, None)
object TestError:
  final case class Io(detail: String) extends TestError(detail)
  final case class Db(code: Int) extends TestError(s"db $code")
  case object Gone extends TestError("gone")

sealed abstract class OtherRoot(message: String) extends TypedError(message, None)
object OtherRoot:
  final case class Net(host: String) extends OtherRoot(host)

// A parameterised arm (not runtime-testable) and its wildcard application (testable).
final case class Boxed[A](value: A) extends Exception("boxed") with NoStackTrace

// An enum root: a simple case is a `val` singleton, a parameterised case a class.
enum Halt(message: String) extends TypedError(message, None):
  case Cancelled extends Halt("cancelled")
  case Deadline(at: Long) extends Halt(s"deadline $at")

// The extractor at a concrete union with singleton arms, with the binder USED - the form a
// synthesised or hand-written `TypeTest` throws `ClassCastException` on.
private def viaExtractor(t: Throwable): String =
  val et = summon[ErrorTest[TestError.Io | TestError.Gone.type | Alpha.type]]
  t match
    case et(e) => s"typed:${e.getMessage}"
    case other => s"defect:${other.getMessage}"

// The seam generic code writes: evidence for `E` alone must satisfy the observer's `Concrete | E`.
private def seam[E <: Throwable](t: Throwable)(using ErrorTest[E]): String =
  if summon[ErrorTest[TestError.Io | E]].test(t) then "typed" else "defect"

class ErrorTestSuite extends FunSuite:

  test("the extractor binds a singleton arm without a class cast"):
    assertEquals(viaExtractor(TestError.Gone), "typed:gone")
    assertEquals(viaExtractor(Alpha), "typed:alpha")

  test("the extractor binds a class arm and lets an unrelated throwable through"):
    assertEquals(viaExtractor(TestError.Io("io")), "typed:io")
    assertEquals(viaExtractor(RuntimeException("x")), "defect:x")

  test("the extractor's binder is usable at the union type it binds"):
    // The refutation this shape exists for: a `TypeTest` extractor's bound result is cast to one
    // arm, so any use of the binder - even `hashCode` - threw at an `object`-arm union.
    val et = summon[ErrorTest[TestError.Io | TestError.Gone.type]]
    val described = (TestError.Gone: Throwable) match
      case et(e) => s"${e.getMessage}:${e.hashCode != 0}"
      case _     => "defect"
    assertEquals(described, "gone:true")

  test("generic code holding evidence for E derives it for Concrete | E"):
    assertEquals(seam[OtherRoot.Net](OtherRoot.Net("h")), "typed")
    assertEquals(seam[OtherRoot.Net](TestError.Io("i")), "typed")
    assertEquals(seam[OtherRoot.Net](TestError.Gone), "defect")

  test("an infallible channel instantiates the seam through the nothing instance"):
    assertEquals(seam[Nothing](TestError.Io("i")), "typed")
    assertEquals(seam[Nothing](OtherRoot.Net("h")), "defect")

  test("a singleton channel is tested by identity, not by class"):
    assertEquals(seam[TestError.Gone.type](TestError.Gone), "typed")
    assert(summon[ErrorTest[TestError.Gone.type]].test(TestError.Gone))
    assert(!summon[ErrorTest[TestError.Gone.type]].test(TestError.Io("i")))

  test("a union of a class arm and an enum's simple and parameterised cases tests each correctly"):
    val et = summon[ErrorTest[TestError.Io | Halt.Cancelled.type | Halt.Deadline]]
    assert(et.test(TestError.Io("i")))
    assert(et.test(Halt.Cancelled))
    assert(et.test(Halt.Deadline(1L)))
    assert(!et.test(TestError.Gone))

  test("the nothing instance admits nothing and wins over derivation without ambiguity"):
    assert(!summon[ErrorTest[Nothing]].test(TestError.Gone))
    assert(typeChecks("summon[boilerplate.ErrorTest[Nothing]]"))

  test("an intersection - the join inferred for branches of unrelated roots - is refused"):
    val errors = typeCheckErrors("summon[boilerplate.ErrorTest[boilerplate.TestError & boilerplate.OtherRoot]]")
    assert(errors.exists(_.message.contains("name the precise union")), errors.map(_.message).mkString("\n"))

  test("an abstract channel with no evidence in scope is refused, naming the remedy"):
    val errors = typeCheckErrors("def f[E <: Throwable](t: Throwable) = summon[boilerplate.ErrorTest[E]].test(t)")
    assert(errors.exists(_.message.contains("using ErrorTest[E]")), errors.map(_.message).mkString("\n"))

  test("a local class arm is refused - the derived test would not be checkable"):
    val errors = typeCheckErrors(
      "def f(t: Throwable) = { final class Local extends Exception; summon[boilerplate.ErrorTest[Local]].test(t) }"
    )
    assert(errors.exists(_.message.contains("local type")), errors.map(_.message).mkString("\n"))

  test("a parameterised channel is refused; its wildcard application is admitted"):
    val errors = typeCheckErrors("summon[boilerplate.ErrorTest[boilerplate.Boxed[Int]]]")
    assert(errors.exists(_.message.contains("not runtime-testable")), errors.map(_.message).mkString("\n"))
    assert(summon[ErrorTest[Boxed[?]]].test(Boxed(1)))

  test("an ErrorTest is not a TypeTest, so a legacy signature does not silently accept it"):
    assert(
      !typeChecks(
        "summon[scala.reflect.TypeTest[Throwable, boilerplate.TestError.Io]](using summon[boilerplate.ErrorTest[boilerplate.TestError.Io]])"
      )
    )
end ErrorTestSuite
