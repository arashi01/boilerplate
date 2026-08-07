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
// overload is exercised here, together with the one-directional `IO[A] <: Eff[E, A]` bound that
// feeds those channels.
class NothingChannelSuite extends CatsEffectSuite:

  private def defect: UEff[Int] = IO.raiseError[Int](new RuntimeException("DEFECT"))
  // A handler over the uninhabited typed channel; supplied where a combinator needs one, never run.
  private val absurd: Nothing => Nothing = identity

  private def propagates[A](eff: UEff[A]): IO[Unit] =
    eff.absolve.attempt.map(r => assert(r.isLeft, s"defect not propagated: $r"))

  // Abstract-`E` generic code must still resolve the observers: the `Nothing` overloads must not
  // make them ambiguous when `E` is a type parameter (only `E` statically `Nothing` selects them).
  @annotation.nowarn("msg=unused")
  private def genericObservers[E <: Throwable, A](eff: Eff[E, A])(using scala.reflect.TypeTest[Throwable, E]): Unit =
    val _ = eff.either
    val _ = eff.option
    val _ = eff.catchAll(Eff.fail(_))
    val _ = eff.mapError(identity)
    val _ = eff.fold(_ => 0, _ => 1)
    val _ = eff.orElseSucceed(())
    // Both catchOnly forms: the infallible handler selects the residual-bounded twin (full
    // coverage, so the residual is Nothing), the fallible one the general overload.
    val _ = eff.catchOnly((_: E) => Eff.never)
    val _ = eff.catchOnly((e: E) => Eff.fail(e))
    // `retry`/`retryWithBackoff` are companion functions, not observers, but they take the same
    // `TypeTest`; abstract `E` must resolve the general overloads, never the `Nothing` twins.
    val _ = Eff.retry(eff, 3)
    val _ = Eff.retryWithBackoff(eff, 3, 1.milli, None)
    val _ = Eff.retry(eff, RetryPolicy.constant(1.milli))
    val _ = Eff.retry(eff, RetryPolicy.constant(1.milli), (_: E) => true)
    val _ = Eff.retry(eff, RetryPolicy.constant(1.milli), (_: Int, _: E, _: FiniteDuration) => IO.unit)
    val _ = Eff.retry(eff, RetryPolicy.constant(1.milli), (_: E) => true, (_: Int, _: E, _: FiniteDuration) => IO.unit)
  end genericObservers

  test("either propagates")(propagates(defect.either))
  test("option propagates")(propagates(defect.option.absolve))
  test("eitherT propagates")(propagates(defect.eitherT.value))
  test("fold propagates")(propagates(defect.fold(absurd, _.toString)))
  test("foldF propagates")(propagates(defect.foldF(absurd, a => IO.pure(a.toString))))
  test("transform propagates")(propagates(defect.transform(_ => Right(())).absolve))
  test("redeemAll propagates")(propagates(defect.redeemAll(absurd, Eff.succeed(_)).absolve))
  test("attemptTap propagates")(propagates(defect.attemptTap(_ => Eff.unit).absolve))
  test("catchAll does not recover")(propagates(defect.catchAll(absurd).absolve))
  test("catchSome does not recover")(propagates(defect.catchSome(PartialFunction.empty).absolve))
  test("catchOnly does not recover")(propagates(defect.catchOnly(absurd).absolve))
  test("catchOnly does not recover an H-typed defect"):
    // Sharper than the absurd row: the defect IS an AppError, but on the infallible channel it is
    // a defect, so the identity twin must propagate it rather than hand it to either handler kind.
    // Asserting the ORIGINAL error distinguishes identity propagation from capture-and-refail.
    val typedDefect: UEff[Int] = IO.raiseError[Int](Invalid("DEFECT"))
    def propagatesOriginal(io: IO[Int]): IO[Unit] =
      io.attempt.map {
        case Left(_: Invalid) => ()
        case other            => fail(s"defect not propagated unchanged: $other")
      }
    propagatesOriginal(typedDefect.catchOnly((_: AppError) => Eff.succeed(0)).absolve) *>
      propagatesOriginal(typedDefect.catchOnly((_: AppError) => Eff.fail(IoError.Closed)).absolve)
  test("alt does not fall back")(propagates(defect.alt(Eff.succeed(0)).absolve))
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
      e <- Eff.succeed(1).either
      o <- Eff.succeed(2).option
      f <- Eff.succeed(3).fold(absurd, _ + 10)
      c <- Eff.succeed(4).catchAll(absurd).absolve
    yield
      assertEquals(e, Right(1))
      assertEquals(o, Some(2))
      assertEquals(f, 13)
      assertEquals(c, 4)

  // A `typeCheckErrors` row that asserted only `nonEmpty` would pass on any compile error at all,
  // including one from a typo in the snippet - so each asserts the mismatch it means.
  private def assertRejected(errors: List[scala.compiletime.testing.Error], found: String, required: String): Unit =
    val messages = errors.map(_.message).mkString("\n")
    assert(messages.contains(found), s"expected the rejection to name $found, got: $messages")
    assert(messages.contains(required), s"expected the rejection to name $required, got: $messages")

  // The lift is one-directional: `IO[A]` is a subtype of every `Eff[E, A]`, and nothing goes back
  // without `absolve`. Each negative must fail to compile - a `typeCheckErrors` list that came back
  // empty would mean the bound had leaked in the wrong direction.

  test("a typed Eff is not assignable to IO"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "(e: boilerplate.effect.Eff[boilerplate.effect.AppError, Int]) => (e: cats.effect.IO[Int])"
    )
    assertRejected(errors, "boilerplate.effect.Eff[boilerplate.effect.AppError, Int]", "IO[Int]")

  test("an infallible UEff is not assignable to IO"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "(e: boilerplate.effect.UEff[Int]) => (e: cats.effect.IO[Int])"
    )
    assertRejected(errors, "boilerplate.effect.UEff[Int]", "IO[Int]")

  test("a typed Eff is not assignable to an infallible UEff"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "(e: boilerplate.effect.Eff[boilerplate.effect.AppError, Int]) => (e: boilerplate.effect.UEff[Int])"
    )
    assertRejected(errors, "boilerplate.effect.Eff[boilerplate.effect.AppError, Int]", "boilerplate.effect.UEff[Int]")

  test("a raw IO is assignable to an infallible Eff channel and runs unchanged"):
    val lifted: UEff[Int] = IO.pure(1)
    lifted.either.map(r => assertEquals(r, Right(1)))

  test("a raw IO is assignable to a typed Eff channel and runs unchanged"):
    val lifted: Eff[AppError, Int] = IO.pure(2)
    lifted.either.map(r => assertEquals(r, Right(2)))

  test("a raw IO returned from a flatMap continuation lifts into the widened channel"):
    val chained: Eff[AppError, Int] = (Eff.succeed(10): Eff[AppError, Int]).flatMap(n => IO.pure(n + 32))
    chained.either.map(r => assertEquals(r, Right(42)))

  // A defect on an infallible channel counts as a programmer error, never a typed failure, so
  // `retry` (counted and policy-driven alike) must run the effect exactly once. Without the
  // `Nothing` twin the call-site solver widens `E := Throwable`, making the observer's `TypeTest`
  // the identity and re-running the defect until the bound. A BARE `IO` argument is the
  // sharpest form of the question: nothing at the call site names an error type, so the twin must
  // still be selected rather than `E` re-widening through the supertype bound.
  private def failing(counter: Ref[IO, Int], e: Throwable): IO[Int] =
    counter.update(_ + 1).flatMap(_ => IO.raiseError[Int](e))

  private def executedOnce(counter: Ref[IO, Int], outcome: Either[Throwable, Any]): IO[Unit] =
    counter.get.map { count =>
      assert(outcome.isLeft, s"defect not propagated: $outcome")
      assertEquals(count, 1)
    }

  test("a bare IO pins Eff.retry to the Nothing twin - the defect executes exactly once"):
    for
      counter <- IO.ref(0)
      outcome <- Eff.retry(failing(counter, new RuntimeException("DEFECT")), 3).absolve.attempt
      _ <- executedOnce(counter, outcome)
    yield ()

  test("a bare IO pins Eff.retryWithBackoff to the Nothing twin - the defect executes exactly once"):
    for
      counter <- IO.ref(0)
      outcome <- Eff.retryWithBackoff(failing(counter, new RuntimeException("DEFECT")), 3, 1.milli, None).absolve.attempt
      _ <- executedOnce(counter, outcome)
    yield ()

  test("a bare IO pins every policy retry overload to the Nothing twin - the defect executes exactly once"):
    val policy = RetryPolicy.constant(1.milli).withMaxAttempts(3)
    for
      plain <- IO.ref(0)
      plainOut <- Eff.retry(failing(plain, new RuntimeException("DEFECT")), policy).absolve.attempt
      _ <- executedOnce(plain, plainOut)
      pred <- IO.ref(0)
      predOut <- Eff.retry(failing(pred, new RuntimeException("DEFECT")), policy, (_: Nothing) => true).absolve.attempt
      _ <- executedOnce(pred, predOut)
      hook <- IO.ref(0)
      hookOut <-
        Eff.retry(failing(hook, new RuntimeException("DEFECT")), policy, (_: Int, _: Nothing, _: FiniteDuration) => IO.unit).absolve.attempt
      _ <- executedOnce(hook, hookOut)
      both <- IO.ref(0)
      bothOut <- Eff
                   .retry(
                     failing(both, new RuntimeException("DEFECT")),
                     policy,
                     (_: Nothing) => true,
                     (_: Int, _: Nothing, _: FiniteDuration) => IO.unit
                   )
                   .absolve
                   .attempt
      _ <- executedOnce(both, bothOut)
    yield ()
    end for

  // The dual: a genuine typed error still retries the full count (1 initial + `maxRetries`).
  test("Eff.retry still retries a typed error the full count"):
    for
      counter <- IO.ref(0)
      typed: Eff[AppError, Int] = (counter.update(_ + 1): Eff[AppError, Unit]).flatMap(_ => Eff.fail(Invalid("boom")))
      outcome <- Eff.retry(typed, 3).either.absolve
      count <- counter.get
    yield
      assertEquals(outcome, Left(Invalid("boom")))
      assertEquals(count, 4)

  test("Eff.retryWithBackoff still retries a typed error the full count"):
    for
      counter <- IO.ref(0)
      typed: Eff[AppError, Int] = (counter.update(_ + 1): Eff[AppError, Unit]).flatMap(_ => Eff.fail(Invalid("boom")))
      outcome <- Eff.retryWithBackoff(typed, 3, 1.milli, None).either.absolve
      count <- counter.get
    yield
      assertEquals(outcome, Left(Invalid("boom")))
      assertEquals(count, 4)

  test("Eff.retry with a policy still retries a typed error up to maxAttempts total executions"):
    for
      counter <- IO.ref(0)
      typed: Eff[AppError, Int] = (counter.update(_ + 1): Eff[AppError, Unit]).flatMap(_ => Eff.fail(Invalid("boom")))
      outcome <- Eff.retry(typed, RetryPolicy.constant(1.milli).withMaxAttempts(3)).either.absolve
      count <- counter.get
    yield
      assertEquals(outcome, Left(Invalid("boom")))
      assertEquals(count, 3)

  test("EffResource.retry with a policy does not re-acquire on a defect (acquires exactly once)"):
    for
      counter <- IO.ref(0)
      defective: EffResource[Nothing, Int] =
        EffResource.make(counter.updateAndGet(_ + 1).flatMap(_ => IO.raiseError[Int](new RuntimeException("DEFECT"))))(_ => IO.unit)
      outcome <- EffResource.retry(defective, RetryPolicy.constant(1.milli).withMaxAttempts(3)).use(Eff.succeed).absolve.attempt
      count <- counter.get
    yield
      assert(outcome.isLeft, s"defect not propagated: $outcome")
      assertEquals(count, 1)
end NothingChannelSuite
