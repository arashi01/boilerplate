/*
 * Copyright (c) 2025 Boilerplate contributors.
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

import scala.util.Try

import cats.Monad
import cats.effect.IO
import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import munit.CatsEffectSuite

class EffSuite extends CatsEffectSuite:
  private def runEff[E, A](eff: Eff[IO, E, A]): IO[Either[E, A]] = eff.either
  private def runUEff[A](eff: UEff[IO, A]): IO[Either[Nothing, A]] = eff.either

  // --- Type alias tests ---

  test("UEff type alias represents infallible effect"):
    val eff: UEff[IO, Int] = Eff[IO].succeed(42)
    runUEff(eff).map(result => assertEquals(result, Right(42)))

  test("TEff type alias represents Throwable-errored effect"):
    val eff: TEff[IO, Int] = Eff.fail[IO, Throwable, Int](new RuntimeException("boom"))
    runEff(eff).map(result => assert(result.isLeft))

  // --- Partially-applied constructor tests ---

  test("Eff[F].succeed creates successful computation"):
    val eff = Eff[IO].succeed(42)
    runEff(eff).map(result => assertEquals(result, Right(42)))

  test("Eff[F].fail creates failed computation"):
    val eff = Eff[IO].fail("boom")
    runEff(eff).map(result => assertEquals(result, Left("boom")))

  test("Eff[F].from lifts pure Either"):
    val right = Eff[IO].from(Right(42): Either[String, Int])
    val left = Eff[IO].from(Left("boom"): Either[String, Int])
    for
      r <- runEff(right)
      l <- runEff(left)
    yield
      assertEquals(r, Right(42))
      assertEquals(l, Left("boom"))

  test("Eff[F].liftF embeds F[A] as success"):
    val eff = Eff[IO].liftF(IO.pure(42))
    runEff(eff).map(result => assertEquals(result, Right(42)))

  test("Eff[F].unit produces Right(())"):
    val eff = Eff[IO].unit
    runEff(eff).map(result => assertEquals(result, Right(())))

  // --- Original constructor tests ---

  test("Eff.succeed and Eff.fail with explicit types"):
    val success = Eff.succeed[IO, String, Int](42)
    val failure = Eff.fail[IO, String, Int]("boom")
    runEff(success).map(r => assertEquals(r, Right(42))) *>
      runEff(failure).map(r => assertEquals(r, Left("boom")))

  test("map transforms successes"):
    val eff = Eff.succeed[IO, String, Int](21).map(_ * 2)
    runEff(eff).map(result => assertEquals(result, Right(42)))

  test("flatMap short circuits on failure"):
    val eff = Eff.fail[IO, String, Int]("boom").flatMap(a => Eff.succeed[IO, String, Int](a + 1))
    runEff(eff).map(result => assertEquals(result, Left("boom")))

  test("catchAll recovers typed errors"):
    val eff = Eff.fail[IO, String, Int]("boom").catchAll(_ => Eff.succeed[IO, String, Int](42))
    runEff(eff).map(result => assertEquals(result, Right(42)))

  test("Monad instance integrates with for-comprehension"):
    val M = summon[Monad[Eff.Of[IO, String]]]
    val program = M.flatMap(M.pure(40))(a => M.pure(a + 2))
    runEff(program).map(result => assertEquals(result, Right(42)))

  test("MonadError instance raises typed errors"):
    val ME = summon[cats.MonadError[Eff.Of[IO, String], String]]
    val eff = ME.raiseError[Int]("boom")
    runEff(eff).map(result => assertEquals(result, Left("boom")))

  test("MonadCancel instance exposes cancellation semantics"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]
    val canceled = MC.onCancel(Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Unit](())).either
    canceled.map(result => assertEquals(result, Right(1)))

  test("fromTry converts failures via mapper"):
    val boom = new RuntimeException("boom")
    val eff = Eff.from[IO, String, Int](Try(throw boom), _.getMessage) // scalafix:ok DisableSyntax.throw
    runEff(eff).map(result => assertEquals(result, Left("boom")))

  test("attempt captures raised errors in F"):
    val io = IO.raiseError[Int](new RuntimeException("boom"))
    val eff = Eff.attempt[IO, String, Int](io, _.getMessage)
    runEff(eff).map(result => assertEquals(result, Left("boom")))

  test("widenError performs zero-cost widening"):
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom")
    val widened = eff.widenError[Any]
    runEff(widened).map(result => assertEquals(result, Left("boom")))

  // --- MonadError disambiguation tests ---

  test("MonadError[Eff.Of[IO, Throwable], Throwable] resolves for typed errors"):
    // When E = Throwable, the typed error instance should be preferred
    val ME = summon[cats.MonadError[Eff.Of[IO, Throwable], Throwable]]
    val raised = ME.raiseError[Int](new RuntimeException("typed"))
    // Typed errors go into Left channel
    runEff(raised).map(result => assert(result.isLeft))

  test("typed errors and defects are distinguishable"):
    // Typed error: goes into Left channel
    val typedError: Eff[IO, Throwable, Int] = Eff.fail(new RuntimeException("typed"))
    // Defect: raised in F itself, not in Either
    val defect: Eff[IO, String, Int] = Eff.liftF(IO.raiseError(new RuntimeException("defect")))

    for
      typedResult <- runEff(typedError).attempt
      defectResult <- runEff(defect).attempt
    yield
      // Typed error: IO succeeds with Left
      assert(typedResult.isRight)
      assertEquals(typedResult.toOption.get.left.toOption.map(_.getMessage), Some("typed"))
      // Defect: IO fails
      assert(defectResult.isLeft)
      assertEquals(defectResult.left.toOption.map(_.getMessage), Some("defect"))

  test("MonadCancel.canceled propagates through Eff"):
    import cats.effect.kernel.Outcome
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]

    var finalizerRan = false // scalafix:ok DisableSyntax.var
    val program = MC.guaranteeCase(MC.canceled) {
      case Outcome.Canceled() =>
        Eff.liftF[IO, String, Unit](IO { finalizerRan = true })
      case _ =>
        Eff.unit[IO, String]
    }

    program.either.start.flatMap(_.join).map { outcome =>
      assert(outcome.isCanceled)
      assert(finalizerRan)
    }

  test("uncancelable masks cancellation correctly"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]

    var wasPolled = false // scalafix:ok DisableSyntax.var
    val program = MC.uncancelable { poll =>
      // Inside uncancelable, poll allows observing cancellation
      wasPolled = true
      poll(Eff.succeed[IO, String, Int](42))
    }

    runEff(program).map { result =>
      assert(wasPolled)
      assertEquals(result, Right(42))
    }

  test("forceR discards left result but propagates defects"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]

    val left = Eff.succeed[IO, String, Int](1)
    val right = Eff.succeed[IO, String, Int](2)
    val combined = MC.forceR(left)(right)

    runEff(combined).map(result => assertEquals(result, Right(2)))

  test("forceR discards typed errors from left"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]

    val left = Eff.fail[IO, String, Int]("ignored")
    val right = Eff.succeed[IO, String, Int](42)
    val combined = MC.forceR(left)(right)

    runEff(combined).map(result => assertEquals(result, Right(42)))

  test("onCancel registers finalizer that runs on cancellation"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]

    var finalizerRan = false // scalafix:ok DisableSyntax.var
    val fin = Eff.liftF[IO, String, Unit](IO { finalizerRan = true })
    val program = MC.onCancel(MC.canceled, fin)

    program.either.start.flatMap(_.join).map { outcome =>
      assert(outcome.isCanceled)
      assert(finalizerRan)
    }

  // --- New combinator tests ---

  test("semiflatMap applies effectful function to success"):
    val eff = Eff[IO].succeed(21).semiflatMap(a => IO.pure(a * 2))
    runEff(eff).map(result => assertEquals(result, Right(42)))

  test("semiflatMap short-circuits on failure"):
    var called = false // scalafix:ok DisableSyntax.var
    val eff = Eff[IO].fail[String]("boom").semiflatMap { _ =>
      called = true
      IO.pure(42)
    }
    runEff(eff).map { result =>
      assertEquals(result, Left("boom"))
      assert(!called)
    }

  test("catchAll transforms error channel to new error type"):
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom")
    val recovered = eff.catchAll(e => Eff.fail[IO, Int, Int](e.length))
    runEff(recovered).map(result => assertEquals(result, Left(4)))

  test("catchAll can recover to success"):
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom")
    val recovered = eff.catchAll(_ => Eff.succeed[IO, String, Int](42))
    runEff(recovered).map(result => assertEquals(result, Right(42)))

  test("subflatMap chains pure Either-returning function"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](10)
    val result = eff.subflatMap { a =>
      if a > 5 then Right(a * 2) else Left("too small")
    }
    runEff(result).map(r => assertEquals(r, Right(20)))

  test("subflatMap propagates failure from function"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](3)
    val result = eff.subflatMap { a =>
      if a > 5 then Right(a * 2) else Left("too small")
    }
    runEff(result).map(r => assertEquals(r, Left("too small")))

  test("transform applies function to Either"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](21)
    val result = eff.transform {
      case Right(a) => Right(a * 2)
      case Left(e)  => Left(e)
    }
    runEff(result).map(r => assertEquals(r, Right(42)))

  test("ensure fails when predicate is false"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](5)
    val result = eff.ensure("too small")(_ > 10)
    runEff(result).map(r => assertEquals(r, Left("too small")))

  test("ensure passes when predicate is true"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](15)
    val result = eff.ensure("too small")(_ > 10)
    runEff(result).map(r => assertEquals(r, Right(15)))

  test("ensureOr fails with computed error when predicate is false"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](5)
    val result = eff.ensureOr(a => s"$a is too small")(_ > 10)
    runEff(result).map(r => assertEquals(r, Left("5 is too small")))

  test("ensureOr passes when predicate is true"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](15)
    val result = eff.ensureOr(a => s"$a is too small")(_ > 10)
    runEff(result).map(r => assertEquals(r, Right(15)))

  // --- Composition Operator Tests ---

  test("*> sequences computations, discarding left result"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left *> right).map(r => assertEquals(r, Right(2)))

  test("*> short-circuits on left failure"):
    val left: Eff[IO, String, Int] = Eff.fail("boom")
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left *> right).map(r => assertEquals(r, Left("boom")))

  test("<* sequences computations, discarding right result"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left <* right).map(r => assertEquals(r, Right(1)))

  test("<* short-circuits on left failure"):
    val left: Eff[IO, String, Int] = Eff.fail("boom")
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left <* right).map(r => assertEquals(r, Left("boom")))

  test("<* short-circuits on right failure"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(left <* right).map(r => assertEquals(r, Left("boom")))

  test("productR is alias for *>"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left.productR(right)).map(r => assertEquals(r, Right(2)))

  test("productL is alias for <*"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left.productL(right)).map(r => assertEquals(r, Right(1)))

  test("void discards the success value"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    runEff(eff.void).map(r => assertEquals(r, Right(())))

  test("as replaces the success value"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    runEff(eff.as("hello")).map(r => assertEquals(r, Right("hello")))

  test("as preserves failure"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(eff.as(42)).map(r => assertEquals(r, Left("boom")))

  test("flatTap applies effectful function and keeps original value"):
    var sideEffect = 0 // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](42).flatTap { a =>
      sideEffect = a
      Eff.succeed[IO, String, String]("discarded")
    }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(sideEffect, 42)
    }

  test("flatTap short-circuits on effectful function failure"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](42).flatTap(_ => Eff.fail[IO, String, Unit]("tap failed"))
    runEff(eff).map(r => assertEquals(r, Left("tap failed")))

  test("product combines two computations into a tuple"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, String] = Eff.succeed("two")
    runEff(left.product(right)).map(r => assertEquals(r, Right((1, "two"))))

  // --- Error Recovery Tests ---

  test("recover with total function maps errors to success values"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(eff.recover(_.length)).map(r => assertEquals(r, Right(4)))

  test("recover with total function leaves success unchanged"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    runEff(eff.recover(_ => 0)).map(r => assertEquals(r, Right(42)))

  test("catchAll switches to alternative computation with new error type"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    val handled: Eff[IO, Int, Int] = eff.catchAll(e => Eff.succeed[IO, Int, Int](e.length))
    runEff(handled).map(r => assertEquals(r, Right(4)))

  test("recover with partial function with partial function handles matching errors"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(eff.recover { case "boom" => 42 }).map(r => assertEquals(r, Right(42)))

  test("recover with partial function with partial function passes through non-matching errors"):
    val eff: Eff[IO, String, Int] = Eff.fail("other")
    runEff(eff.recover { case "boom" => 42 }).map(r => assertEquals(r, Left("other")))

  test("recoverWith handles matching errors with effectful recovery"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(eff.recoverWith { case "boom" => Eff.succeed[IO, String, Int](42) }).map(r => assertEquals(r, Right(42)))

  test("recoverWith passes through non-matching errors"):
    val eff: Eff[IO, String, Int] = Eff.fail("other")
    runEff(eff.recoverWith { case "boom" => Eff.succeed[IO, String, Int](42) }).map(r => assertEquals(r, Left("other")))

  test("onError executes side effect on failure"):
    var errorObserved: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom").onError { case e =>
      errorObserved = Some(e)
      Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(errorObserved, Some("boom"))
    }

  test("onError does not execute on success"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](42).onError { case _ =>
      executed = true
      Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(!executed)
    }

  test("adaptError transforms matching errors"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(eff.adaptError { case "boom" => "BOOM" }).map(r => assertEquals(r, Left("BOOM")))

  test("adaptError passes through non-matching errors"):
    val eff: Eff[IO, String, Int] = Eff.fail("other")
    runEff(eff.adaptError { case "boom" => "BOOM" }).map(r => assertEquals(r, Left("other")))

  // --- Conversion Utility Tests ---

  test("either unwraps to the underlying F[Either]"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    eff.either.map(r => assertEquals(r, Right(42)))

  test("rethrow throws error into F"):
    val eff: Eff[IO, RuntimeException, Int] = Eff.fail(new RuntimeException("boom"))
    eff.rethrow.attempt.map { r =>
      assert(r.isLeft)
      assertEquals(r.left.toOption.map(_.getMessage), Some("boom"))
    }

  test("rethrow returns value on success"):
    val eff: Eff[IO, RuntimeException, Int] = Eff.succeed(42)
    eff.rethrow.map(r => assertEquals(r, 42))

  test("absolve with Throwable error type"):
    val eff: Eff[IO, Throwable, Int] = Eff.fail(new RuntimeException("boom"))
    eff.absolve.attempt.map { r =>
      assert(r.isLeft)
      assertEquals(r.left.toOption.map(_.getMessage), Some("boom"))
    }

  // --- Constructor Edge Cases ---

  test("Eff.lift wraps F[Either] without recomputation"):
    var evaluations = 0 // scalafix:ok DisableSyntax.var
    val fea = IO { evaluations += 1; Right(42) }
    val eff = Eff.lift(fea)
    runEff(eff).flatMap { r1 =>
      assertEquals(r1, Right(42))
      assertEquals(evaluations, 1)
      runEff(eff).map { r2 =>
        assertEquals(r2, Right(42))
        assertEquals(evaluations, 2) // Re-evaluates because IO is not memoised
      }
    }

  test("Eff.lift with F[Option] converts missing values"):
    val fopt = IO.pure(Option.empty[Int])
    val eff = Eff.lift[IO, String, Int](fopt, "missing")
    runEff(eff).map(r => assertEquals(r, Left("missing")))

  test("Eff.lift with F[Option] converts present values"):
    val fopt = IO.pure(Some(42))
    val eff = Eff.lift[IO, String, Int](fopt, "missing")
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("Eff.from(EitherT) extracts underlying computation"):
    import cats.data.EitherT
    val et = EitherT.rightT[IO, String](42)
    val eff = Eff.from(et)
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("Eff.defer delays evaluation until demanded"):
    var evaluated = false // scalafix:ok DisableSyntax.var
    val deferred = Eff.defer[IO, String, Int] {
      evaluated = true
      Eff.succeed(42)
    }
    assert(!evaluated, "defer should not evaluate immediately")
    runEff(deferred).map { r =>
      assert(evaluated, "defer should evaluate when run")
      assertEquals(r, Right(42))
    }

  test("eitherT converts to EitherT"):
    import cats.data.EitherT
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    val et: EitherT[IO, String, Int] = eff.eitherT
    et.value.map(r => assertEquals(r, Right(42)))

  // --- Fold and Redeem Edge Cases ---

  test("fold returns pure value from both channels"):
    val success: Eff[IO, String, Int] = Eff.succeed(42)
    val failure: Eff[IO, String, Int] = Eff.fail("boom")
    success.fold(_ => "error", _.toString).map(r => assertEquals(r, "42")) *>
      failure.fold(_.toUpperCase, _ => "ok").map(r => assertEquals(r, "BOOM"))

  test("foldF allows effectful handlers"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    eff.foldF(e => IO.pure(e.length), a => IO.pure(a)).map(r => assertEquals(r, 4))

  test("redeemAll handles both channels with effectful recovery and error type change"):
    val success: Eff[IO, String, Int] = Eff.succeed(21)
    val failure: Eff[IO, String, Int] = Eff.fail("boom")
    // Explicit type parameters allow changing error type from String to Int
    val successRedeemed = success.redeemAll[Int, String](
      _ => Eff.succeed("failed"),
      a => Eff.succeed(s"got $a")
    )
    val failureRedeemed = failure.redeemAll[Int, String](
      e => Eff.succeed(s"recovered from $e"),
      _ => Eff.succeed("ok")
    )
    runEff(successRedeemed).map(r => assertEquals(r, Right("got 21"))) *>
      runEff(failureRedeemed).map(r => assertEquals(r, Right("recovered from boom")))

  // --- Variance Edge Cases ---

  test("assume performs unchecked cast on success channel"):
    val eff: Eff[IO, String, Any] = Eff.succeed[IO, String, Any](42)
    val narrowed = eff.assume[Int]
    runEff(narrowed).map(r => assertEquals(r, Right(42)))

  test("widen performs safe upcast on success channel"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    val widened: Eff[IO, String, Any] = eff.widen[Any]
    runEff(widened).map(r => assertEquals(r, Right(42)))

  // --- Missing Edge Cases ---

  test("mapError transforms error channel"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    runEff(eff.mapError(_.length)).map(r => assertEquals(r, Left(4)))

  test("mapError preserves success"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    runEff(eff.mapError(_.length)).map(r => assertEquals(r, Right(42)))

  test("bimap transforms both channels"):
    val fail: Eff[IO, String, Int] = Eff.fail("boom")
    val success: Eff[IO, String, Int] = Eff.succeed(21)
    runEff(fail.bimap(_.length, _ * 2)).map(r => assertEquals(r, Left(4))) *>
      runEff(success.bimap(_.length, _ * 2)).map(r => assertEquals(r, Right(42)))

  test("tapError observes failures without altering result"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom").tapError { e =>
      IO { observed = Some(e) }
    }
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("tapError does not execute on success"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](42).tapError(_ => IO { executed = true })
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(!executed)
    }

  test("alt falls back to alternative on failure"):
    val left: Eff[IO, String, Int] = Eff.fail("boom")
    val right: Eff[IO, Int, Int] = Eff.succeed(42)
    runEff(left.alt(right)).map(r => assertEquals(r, Right(42)))

  test("alt returns original on success"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, Int] = Eff.succeed(2)
    runEff(left.alt(right)).map(r => assertEquals(r, Right(1)))

  test("alt allows error type change"):
    val left: Eff[IO, String, Int] = Eff.fail("boom")
    val right: Eff[IO, Int, Int] = Eff.fail(42)
    runEff(left.alt(right)).map(r => assertEquals(r, Left(42)))

  test("onError with non-matching partial function does not execute"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("other").onError { case "boom" =>
      executed = true
      Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Left("other"))
      assert(!executed)
    }

  test("redeemAll can return failure from handlers"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    val redeemed = eff.redeemAll[Int, String](
      _ => Eff.fail(0),
      _ => Eff.fail(1)
    )
    runEff(redeemed).map(r => assertEquals(r, Left(1)))

  test("ensure short-circuits on failure input"):
    val eff: Eff[IO, String, Int] = Eff.fail("already failed")
    val result = eff.ensure("predicate failed")(_ > 0)
    runEff(result).map(r => assertEquals(r, Left("already failed")))

  test("ensureOr short-circuits on failure input"):
    val eff: Eff[IO, String, Int] = Eff.fail("already failed")
    val result = eff.ensureOr(n => s"$n too small")(_ > 0)
    runEff(result).map(r => assertEquals(r, Left("already failed")))

  test("absolve returns value on success"):
    val eff: Eff[IO, Throwable, Int] = Eff.succeed(42)
    eff.absolve.map(r => assertEquals(r, 42))

  test("assumeError performs unchecked cast on error channel"):
    val eff: Eff[IO, Any, Int] = Eff.fail[IO, Any, Int]("boom")
    val narrowed = eff.assumeError[String]
    runEff(narrowed).map(r => assertEquals(r, Left("boom")))
end EffSuite
