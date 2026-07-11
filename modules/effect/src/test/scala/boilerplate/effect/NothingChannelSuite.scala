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

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*

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
    // `retry`/`retryWithBackoff` are companion functions, not observers, but they take the same
    // `TypeTest`; abstract `E` must resolve the general overload, never the `Nothing` twin.
    val _ = EffIO.retry(eff, 3)
    val _ = EffIO.retryWithBackoff(eff, 3, 1.milli, None)
  end genericObservers

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
    val _ = Eff.retry(eff, 3)

  // As above but with `GenTemporal` in scope, exercising the `retryWithBackoff` general overload
  // under an abstract `E` (its `Nothing` twin must not shadow it).
  @annotation.nowarn("msg=unused")
  private def genericEffBackoff[F[_], E <: Throwable, A](eff: Eff[F, E, A])(using
    cats.effect.kernel.GenTemporal[F, Throwable],
    scala.reflect.TypeTest[Throwable, E]
  ): Unit =
    val _ = Eff.retryWithBackoff(eff, 3, 1.milli, None)

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

  // A defect on an infallible channel counts as a programmer error, never a typed failure, so
  // `retry`/`retryWithBackoff` must run the effect exactly once. Before the `Nothing` twin, the
  // call-site solver widened `E := Throwable`, making the observer's `TypeTest` the identity and
  // re-running the defect `maxRetries` times.
  private def failing(counter: Ref[IO, Int], e: Throwable): IO[Int] =
    counter.update(_ + 1).flatMap(_ => IO.raiseError[Int](e))

  test("EffIO.retry does not re-run a defect (executes exactly once)"):
    for
      counter <- IO.ref(0)
      defect = EffIO.liftF(failing(counter, new RuntimeException("DEFECT")))
      outcome <- EffIO.retry(defect, 3).absolve.attempt
      count <- counter.get
    yield
      assert(outcome.isLeft, s"defect not propagated: $outcome")
      assertEquals(count, 1)

  test("EffIO.retryWithBackoff does not re-run a defect (executes exactly once)"):
    for
      counter <- IO.ref(0)
      defect = EffIO.liftF(failing(counter, new RuntimeException("DEFECT")))
      outcome <- EffIO.retryWithBackoff(defect, 3, 1.milli, None).absolve.attempt
      count <- counter.get
    yield
      assert(outcome.isLeft, s"defect not propagated: $outcome")
      assertEquals(count, 1)

  test("Eff.retry does not re-run a defect (executes exactly once)"):
    for
      counter <- IO.ref(0)
      defect = Eff[IO].liftF(failing(counter, new RuntimeException("DEFECT")))
      outcome <- Eff.retry(defect, 3).absolve.attempt
      count <- counter.get
    yield
      assert(outcome.isLeft, s"defect not propagated: $outcome")
      assertEquals(count, 1)

  // The dual: a genuine typed error still retries the full count (1 initial + `maxRetries`).
  test("EffIO.retry still retries a typed error the full count"):
    for
      counter <- IO.ref(0)
      typed: EffIO[AppError, Int] = EffIO.liftF(counter.update(_ + 1)).flatMap(_ => EffIO.fail(Invalid("boom")))
      outcome <- EffIO.retry(typed, 3).either
      count <- counter.get
    yield
      assertEquals(outcome, Left(Invalid("boom")))
      assertEquals(count, 4)

  test("EffIO.retryWithBackoff still retries a typed error the full count"):
    for
      counter <- IO.ref(0)
      typed: EffIO[AppError, Int] = EffIO.liftF(counter.update(_ + 1)).flatMap(_ => EffIO.fail(Invalid("boom")))
      outcome <- EffIO.retryWithBackoff(typed, 3, 1.milli, None).either
      count <- counter.get
    yield
      assertEquals(outcome, Left(Invalid("boom")))
      assertEquals(count, 4)
end NothingChannelSuite
