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

import scala.util.control.NoStackTrace

// A consumer module's root, declared exactly as one would downstream: sealed over the non-sealed
// base, with the payload-free arm in the class+object shape and an idempotent `Unexpected`.
sealed abstract class StoreError(message: String, cause: Option[Throwable]) extends TypedError(message, cause)
object StoreError:
  sealed trait Read extends StoreError

  sealed abstract class Missing private[StoreError] () extends StoreError("missing", None) with Read
  case object Missing extends Missing

  final class Unexpected private (val reason: Throwable) extends StoreError("unexpected read failure", Some(reason)) with Read
  object Unexpected:
    def apply(cause: Throwable): Read = TypedError.idempotent[Read, Unexpected](cause)(new Unexpected(_))

class TypedErrorSuite extends munit.FunSuite:
  test("a root over TypedError carries the message and the cause through to Exception"):
    val cause = RuntimeException("underlying")
    val wrapped = StoreError.Unexpected(cause)
    assertEquals(wrapped.getMessage, "unexpected read failure")
    assertEquals(wrapped.getCause, cause)
    assertEquals(StoreError.Missing.getMessage, "missing")
    assertEquals(StoreError.Missing.getCause, null) // scalafix:ok DisableSyntax.null

  test("an arm carries no stack trace, so raising one costs nothing to fill in"):
    assertEquals(StoreError.Missing.getStackTrace.length, 0)
    assert(StoreError.Missing.isInstanceOf[NoStackTrace]) // scalafix:ok DisableSyntax.isInstanceOf

  test("idempotent returns a cause that already is a Root unchanged, never nesting it"):
    val existing: StoreError.Read = StoreError.Missing
    val result = StoreError.Unexpected(existing)
    assert(result eq existing)

  test("idempotent constructs the arm for a cause outside the Root"):
    val cause = IllegalStateException("foreign")
    val result = StoreError.Unexpected(cause)
    assert(!(result eq cause))
    assertEquals(result.getCause, cause)

  test("the root is exhaustively matchable, so a consumer root stays sealed over the open base"):
    def describe(e: StoreError.Read): String = e match
      case StoreError.Missing       => "missing"
      case _: StoreError.Unexpected => "unexpected"
    assertEquals(describe(StoreError.Missing), "missing")
    assertEquals(describe(StoreError.Unexpected(RuntimeException("x"))), "unexpected")

  test("derived multiversal equality compares arms of the same root"):
    val a: StoreError = StoreError.Missing
    val b: StoreError = StoreError.Missing
    assert(a == b)
end TypedErrorSuite
