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

import scala.reflect.TypeTest
import scala.util.Failure
import scala.util.Try

import cats.effect.IO
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IoError.*

// The fallible conversions need `MonadThrow[F]`, which `Id` lacks, so `F = IO` throughout; the
// infallible no-argument lifts carry no constraint and are observed with `absolve`. Data-structure
// lifting extensions (`Resource`/`Ref`/`Queue`/...) are covered by `EffInteropSuite`.
class SyntaxSuite extends CatsEffectSuite:

  private def runEff[E <: Throwable, A](eff: Eff[IO, E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] =
    eff.either

  private def run[E <: Throwable, A](eff: EffIO[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] =
    eff.either

  // Eff conversions (F = IO)

  test("Either.eff lifts a Right to success and a Left to a typed error"):
    for
      ok <- runEff((Right(42): Either[AppError, Int]).eff[IO])
      ko <- runEff((Left(NotFound("u1")): Either[AppError, Int]).eff[IO])
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(NotFound("u1")))

  test("F[Either].eff absorbs a Left into the typed channel"):
    for
      ok <- runEff(IO.pure(Right(7): Either[IoError, Int]).eff)
      ko <- runEff(IO.pure(Left(Closed): Either[IoError, Int]).eff)
    yield
      assertEquals(ok, Right(7))
      assertEquals(ko, Left(Closed))

  test("Option.eff lifts a Some and supplies the error for None"):
    for
      ok <- runEff((Some(42): Option[Int]).eff[IO, AppError](NotFound("u1")))
      ko <- runEff((None: Option[Int]).eff[IO, AppError](NotFound("u1")))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(NotFound("u1")))

  test("F[Option].eff lifts a Some and supplies the error for None"):
    for
      ok <- runEff(IO.pure(Some(42): Option[Int]).eff[IoError](Closed))
      ko <- runEff(IO.pure(None: Option[Int]).eff[IoError](Closed))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Closed))

  test("Try.eff converts a Success and translates a Failure"):
    for
      ok <- runEff(Try(42).eff[IO, AppError](t => Invalid(t.getMessage)))
      ko <- runEff(Failure[Int](RuntimeException("boom")).eff[IO, AppError](t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Invalid("boom")))

  test("F[A].eff captures a raised throwable as a typed error"):
    for
      ok <- runEff(IO.pure(1).eff[AppError](t => Invalid(t.getMessage)))
      ko <- runEff(IO.raiseError[Int](RuntimeException("boom")).eff[AppError](t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Invalid("boom")))

  test("F[A].eff lifts an infallible effect as a success; absolve is O(0) identity"):
    val lifted: UEff[IO, Int] = IO.pure(42).eff
    lifted.absolve.map(value => assertEquals(value, 42))

  // EffIO conversions

  test("IO.effIO captures a raised throwable as a typed error"):
    for
      ok <- run(IO.pure(1).effIO[AppError](t => Invalid(t.getMessage)))
      ko <- run(IO.raiseError[Int](RuntimeException("boom")).effIO[AppError](t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Invalid("boom")))

  test("IO.effIO lifts an infallible IO as a success; absolve is O(0) identity"):
    IO.pure(42).effIO.absolve.map(value => assertEquals(value, 42))

  test("IO[Either].effIO absorbs a Left into the typed channel"):
    for
      ok <- run(IO.pure(Right(7): Either[IoError, Int]).effIO)
      ko <- run(IO.pure(Left(Closed): Either[IoError, Int]).effIO)
    yield
      assertEquals(ok, Right(7))
      assertEquals(ko, Left(Closed))

  test("Either.effIO lifts a Right to success and a Left to a typed error"):
    for
      ok <- run((Right(42): Either[AppError, Int]).effIO)
      ko <- run((Left(NotFound("u1")): Either[AppError, Int]).effIO)
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(NotFound("u1")))

  test("Option.effIO lifts a Some and supplies the error for None"):
    for
      ok <- run((Some(42): Option[Int]).effIO[IoError](Closed))
      ko <- run((None: Option[Int]).effIO[IoError](Closed))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Closed))

  test("IO[Option].effIO lifts a Some and supplies the error for None"):
    for
      ok <- run(IO.pure(Some(42): Option[Int]).effIO[IoError](Closed))
      ko <- run(IO.pure(None: Option[Int]).effIO[IoError](Closed))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Closed))

  test("Try.effIO converts a Success and translates a Failure"):
    for
      ok <- run(Try(42).effIO[AppError](t => Invalid(t.getMessage)))
      ko <- run(Failure[Int](RuntimeException("boom")).effIO[AppError](t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Invalid("boom")))

  // Fibre joins under the Errored flip

  test("Eff Fiber.joinNever returns a success and re-raises a typed failure as Errored"):
    def joined(eff: Eff[IO, AppError, Int]): Eff[IO, AppError, Int] =
      for
        fiber <- eff.start
        value <- fiber.joinNever
      yield value
    for
      ok <- runEff(joined(Eff.succeed[IO, AppError, Int](42)))
      ko <- runEff(joined(Eff.fail[IO, AppError, Int](Timeout)))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Timeout))

  test("Eff Fiber.joinOrFail returns a success and re-raises a typed failure as Errored"):
    def joined(eff: Eff[IO, AppError, Int]): Eff[IO, AppError, Int] =
      for
        fiber <- eff.start
        value <- fiber.joinOrFail(Timeout)
      yield value
    for
      ok <- runEff(joined(Eff.succeed[IO, AppError, Int](42)))
      ko <- runEff(joined(Eff.fail[IO, AppError, Int](Invalid("boom"))))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Invalid("boom")))

  test("Eff Fiber.joinOrFail fails with onCanceled when the fibre is cancelled"):
    val program: Eff[IO, AppError, Int] =
      for
        fiber <- Eff.never[IO, AppError, Int].start
        _ <- fiber.cancel
        value <- fiber.joinOrFail(Timeout)
      yield value
    runEff(program).map(result => assertEquals(result, Left(Timeout)))

  test("EffIO Fiber.joinNever returns a success and re-raises a typed failure as Errored"):
    def joined(eff: EffIO[IoError, Int]): EffIO[IoError, Int] =
      for
        fiber <- eff.start
        value <- fiber.joinNever
      yield value
    for
      ok <- run(joined(EffIO.succeed(42)))
      ko <- run(joined(EffIO.fail(Closed)))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Closed))

  test("EffIO Fiber.joinOrFail returns a success and re-raises a typed failure as Errored"):
    def joined(eff: EffIO[IoError, Int]): EffIO[IoError, Int] =
      for
        fiber <- eff.start
        value <- fiber.joinOrFail(Closed)
      yield value
    for
      ok <- run(joined(EffIO.succeed(42)))
      ko <- run(joined(EffIO.fail(Failed(500))))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Failed(500)))

  test("EffIO Fiber.joinOrFail fails with onCanceled when the fibre is cancelled"):
    val program: EffIO[IoError, Int] =
      for
        fiber <- (EffIO.never: EffIO[IoError, Int]).start
        _ <- fiber.cancel
        value <- fiber.joinOrFail(Closed)
      yield value
    run(program).map(result => assertEquals(result, Left(Closed)))
end SyntaxSuite
