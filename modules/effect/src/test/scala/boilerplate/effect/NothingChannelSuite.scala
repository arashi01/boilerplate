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

// A defect raised on an infallible (`Nothing`) channel must PROPAGATE - it is not a typed error, so
// no channel-observing combinator may capture, recover, or swallow it. Each observer's `Nothing`
// overload is exercised here.
class NothingChannelSuite extends CatsEffectSuite:

  private def defect: UEffIO[Int] = EffIO.liftF(IO.raiseError(new RuntimeException("DEFECT")))
  // A handler over the uninhabited typed channel; supplied where a combinator needs one, never run.
  private val absurd: Nothing => Nothing = identity

  private def propagates[A](io: IO[A]): IO[Unit] =
    io.attempt.map(r => assert(r.isLeft, s"defect not propagated: $r"))

  // Abstract-`E` generic code must still resolve the observers: the `Nothing` overloads must not
  // make them ambiguous when `E` is a type parameter (only `E` statically `Nothing` selects them).
  @annotation.nowarn("msg=unused")
  private def genericObservers[E <: Throwable, A](eff: EffIO[E, A])(using scala.reflect.TypeTest[Throwable, E]): Unit =
    val _ = eff.either
    val _ = eff.option
    val _ = eff.catchAll(EffIO.fail(_))
    val _ = eff.mapError(identity)
    val _ = eff.fold(_ => 0, _ => 1)
    val _ = eff.orElseSucceed(())

  // The same for the generic `Eff` surface (the handler ignores its argument - the shape that broke
  // `retry` internally; a consumer must still be able to write it).
  @annotation.nowarn("msg=unused")
  private def genericEffObservers[F[_], E <: Throwable, A](eff: Eff[F, E, A])(using
    cats.MonadThrow[F],
    scala.reflect.TypeTest[Throwable, E]
  ): Unit =
    val _ = eff.either
    val _ = eff.option
    val _ = eff.catchAll(_ => eff)
    val _ = eff.mapError(identity)

  test("either propagates")(propagates(defect.either))
  test("option propagates")(propagates(defect.option.absolve))
  test("eitherT propagates")(propagates(defect.eitherT.value))
  test("fold propagates")(propagates(defect.fold(absurd, _.toString)))
  test("foldF propagates")(propagates(defect.foldF(absurd, a => IO.pure(a.toString))))
  test("transform propagates")(propagates(defect.transform(_ => Right(())).absolve))
  test("redeemAll propagates")(propagates(defect.redeemAll(absurd, EffIO.succeed(_)).absolve))
  test("attemptTap propagates")(propagates(defect.attemptTap(_ => EffIO.unit).absolve))
  test("catchAll does not recover")(propagates(defect.catchAll(absurd).absolve))
  test("catchSome does not recover")(propagates(defect.catchSome(PartialFunction.empty).absolve))
  test("catchOnly does not recover")(propagates(defect.catchOnly(absurd).absolve))
  test("alt does not fall back")(propagates(defect.alt(EffIO.succeed(0)).absolve))
  test("orElseSucceed does not recover")(propagates(defect.orElseSucceed(0).absolve))
  test("orElseFail does not replace")(propagates(defect.orElseFail(new RuntimeException("other")).absolve))
  test("valueOr does not recover")(propagates(defect.valueOr(absurd).absolve))
  test("mapError is identity")(propagates(defect.mapError(absurd).absolve))
  test("mapErrorPartial is identity")(propagates(defect.mapErrorPartial(PartialFunction.empty).absolve))
  test("tapError does not observe")(propagates(defect.tapError(absurd).absolve))
  test("flatTapError does not observe")(propagates(defect.flatTapError(absurd).absolve))

  test("a success still flows through the observers"):
    // Sanity: the degenerate bodies must not break the happy path.
    for
      e <- EffIO.succeed(1).either
      o <- EffIO.succeed(2).option.absolve
      f <- EffIO.succeed(3).fold(absurd, _ + 10)
      c <- EffIO.succeed(4).catchAll(absurd).absolve
    yield
      assertEquals(e, Right(1))
      assertEquals(o, Some(2))
      assertEquals(f, 13)
      assertEquals(c, 4)
end NothingChannelSuite
