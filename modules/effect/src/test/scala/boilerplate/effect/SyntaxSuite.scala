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

import boilerplate.ErrorTest
import boilerplate.effect.AppError.*

// The fibre-join extensions over `Fiber[Eff.Of[E], Throwable, A]`: a typed failure arrives as
// `Outcome.Errored`, so joining has to re-raise it on the typed channel rather than report success.
class SyntaxSuite extends CatsEffectSuite:

  private def run[E <: Throwable, A](eff: Eff[E, A])(using ErrorTest[E]): IO[Either[E, A]] = eff.either.absolve

  test("Fiber.joinNever returns a success and re-raises a typed failure as Errored"):
    def joined(eff: Eff[AppError, Int]): Eff[AppError, Int] =
      for
        fiber <- eff.start
        value <- fiber.joinNever
      yield value
    for
      ok <- run(joined(Eff.succeed(42)))
      ko <- run(joined(Eff.fail(Timeout)))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Timeout))

  test("Fiber.joinOrFail returns a success and re-raises a typed failure as Errored"):
    def joined(eff: Eff[AppError, Int]): Eff[AppError, Int] =
      for
        fiber <- eff.start
        value <- fiber.joinOrFail(Timeout)
      yield value
    for
      ok <- run(joined(Eff.succeed(42)))
      ko <- run(joined(Eff.fail(Invalid("boom"))))
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left(Invalid("boom")))

  test("Fiber.joinOrFail fails with onCanceled when the fibre is cancelled"):
    val program: Eff[AppError, Int] =
      for
        fiber <- (Eff.never: Eff[AppError, Int]).start
        _ <- fiber.cancel
        value <- fiber.joinOrFail(Timeout)
      yield value
    run(program).map(result => assertEquals(result, Left(Timeout)))

  // The leading-generator seam: `IO`'s member `flatMap` wins over any extension, so an `IO`
  // generator followed by an `Eff` generator needs `.eff` on the `IO` - the one lift position
  // the supertype bound cannot reach.
  test("an IO generator marked .eff is followed by Eff generators"):
    def typed(n: Int): Eff[AppError, Int] =
      if n >= 0 then Eff.succeed(n + 1) else Eff.fail(Invalid("negative"))
    val program: Eff[AppError, Int] =
      for
        ref <- IO.ref(41).eff
        n <- ref.get.eff
        out <- typed(n)
      yield out
    run(program).map(assertEquals(_, Right(42)))

  test("an unmarked IO generator before an Eff generator still fails to compile"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      """
      def typed(n: Int): Eff[AppError, Int] = Eff.succeed(n + 1)
      val program: Eff[AppError, Int] =
        for
          n <- IO.pure(41)
          out <- typed(n)
        yield out
      """
    )
    assert(errors.nonEmpty && errors.exists(_.message.contains("Eff")), errors.map(_.message).mkString("\n"))

  test("a Resource generator marked .eff is followed by EffResource generators"):
    import cats.effect.Resource
    def typed(n: Int): EffResource[AppError, Int] = EffResource.eval(Eff.succeed(n + 1))
    val program: EffResource[AppError, Int] =
      for
        n <- Resource.pure[IO, Int](41).eff
        out <- typed(n)
      yield out
    program.use(n => Eff.succeed(n)).absolve.map(assertEquals(_, 42))
end SyntaxSuite
