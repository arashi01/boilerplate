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
package boilerplate.effect

import cats.effect.IO
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*

// `asyncAttempt` folds a registration-time failure into a typed error, delivers a callback typed
// error unchanged, and - the B2 fix - needs no `TypeTest`, so it compiles for an abstract `E`.
class AsyncAttemptSuite extends CatsEffectSuite:

  // The original B2 consumer shape: `E` is a type parameter, so a synthesised `TypeTest` would be
  // unchecked and fail under `-Werror`. This must compile.
  @annotation.nowarn("msg=unused")
  private def generic[E <: Throwable, A](ifDefect: Throwable => E)(k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]): Eff[E, A] =
    Eff.asyncAttempt(ifDefect)(k)

  test("a callback-delivered typed error passes through unchanged"):
    Eff
      .asyncAttempt[AppError, Int](_ => NotFound("defect"))(cb => IO(cb(Left(Timeout))).as(None))
      .either
      .map(r => assertEquals(r, Left(Timeout)))

  test("a registration-time failure is folded through ifDefect"):
    val boom = new RuntimeException("boom")
    Eff
      .asyncAttempt[AppError, Int](_ => Timeout)(_ => IO.raiseError(boom))
      .either
      .map(r => assertEquals(r, Left(Timeout)))

  test("a callback-delivered success passes through"):
    Eff
      .asyncAttempt[AppError, Int](_ => Timeout)(cb => IO(cb(Right(42))).as(None))
      .either
      .map(r => assertEquals(r, Right(42)))
end AsyncAttemptSuite
