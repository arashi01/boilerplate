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
import cats.effect.kernel.Outcome
import cats.syntax.all.*
import munit.CatsEffectSuite

class EffRSuite extends CatsEffectSuite:
  private def runEffR[R, E, A](er: EffR[IO, R, E, A], env: R): IO[Either[E, A]] = er.run(env).either

  // --- Type alias tests ---

  test("UEffR type alias represents infallible reader effect"):
    val prog: UEffR[IO, String, Int] = EffR.succeed[IO, String, Nothing, Int](42)
    runEffR(prog, "env").map(result => assertEquals(result, Right(42)))

  test("TEffR type alias represents Throwable-errored reader effect"):
    val ex = new RuntimeException("boom")
    val prog: TEffR[IO, Unit, Int] = EffR.fail[IO, Unit, Throwable, Int](ex)
    runEffR(prog, ()).map(result => assertEquals(result, Left(ex)))

  // --- Partially-applied constructor tests ---

  test("EffR[F, R].succeed creates successful computation"):
    val prog = EffR[IO, String].succeed[Nothing, Int](42)
    runEffR(prog, "env").map(result => assertEquals(result, Right(42)))

  test("EffR[F, R].fail creates failed computation"):
    val prog = EffR[IO, String].fail[String, Int]("boom")
    runEffR(prog, "env").map(result => assertEquals(result, Left("boom")))

  test("EffR[F, R].from lifts pure Either"):
    val right = EffR[IO, Unit].from[String, Int](Right(42))
    val left = EffR[IO, Unit].from[String, Int](Left("boom"))
    for
      ok <- runEffR(right, ())
      ko <- runEffR(left, ())
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left("boom"))

  test("EffR[F, R].lift discards environment"):
    val eff: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](42)
    val prog = EffR[IO, String].lift[String, Int](eff)
    runEffR(prog, "env").map(result => assertEquals(result, Right(42)))

  test("EffR[F, R].service retrieves environment"):
    val prog = EffR[IO, String].service[Nothing]
    runEffR(prog, "hello").map(result => assertEquals(result, Right("hello")))

  test("EffR[F, R].unit produces Right(())"):
    val prog = EffR[IO, Unit].unit[Nothing]
    runEffR(prog, ()).map(result => assertEquals(result, Right(())))

  // --- Original tests ---

  test("service fetches environment"):
    val prog = EffR.service[IO, String, String]
    runEffR(prog, "env").map(result => assertEquals(result, Right("env")))

  test("contramap transforms upstream environment"):
    val base = EffR.service[IO, Int, String]
    val transformed = base.contramap[String](_.length)
    runEffR(transformed, "abcd").map(result => assertEquals(result, Right(4)))

  test("provide with layer composes environment effects"):
    val base: EffR[IO, String, String, Int] = EffR.service[IO, String, String].map(_.length)
    val layer: EffR[IO, Unit, String, String] = EffR.succeed[IO, Unit, String, String]("layered")
    val provided = base.provide(layer)(using summon[Monad[IO]])
    runEffR(provided, ()).map(result => assertEquals(result, Right(7)))

  test("fromOption reflects Optional state"):
    val some = EffR.from[IO, Unit, String, Int](Some(1), "none")
    val none = EffR.from[IO, Unit, String, Int](None, "none")
    for
      ok <- runEffR(some, ())
      ko <- runEffR(none, ())
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left("none"))

  test("fromTry translates throwables"):
    val boom = new RuntimeException("boom")
    val success = EffR.from[IO, Unit, String, Int](Try(42), _.getMessage)
    val failure = EffR.from[IO, Unit, String, Int](Try(throw boom), _.getMessage) // scalafix:ok
    for
      ok <- runEffR(success, ())
      ko <- runEffR(failure, ())
    yield
      assertEquals(ok, Right(42))
      assertEquals(ko, Left("boom"))

  test("attempt captures raised errors in F"):
    val io = IO.raiseError[Int](new RuntimeException("boom"))
    val eff = EffR.attempt[IO, Unit, String, Int](io, _.getMessage)
    runEffR(eff, ()).map(result => assertEquals(result, Left("boom")))

  test("widenEnv allows contravariant environment reuse"):
    val base: EffR[IO, AnyRef, String, AnyRef] = EffR.service[IO, AnyRef, String]
    val narrowed = base.widenEnv[String]
    runEffR(narrowed, "env").map(result => assertEquals(result, Right("env")))

  // --- MonadCancel tests for EffR ---

  test("MonadCancel instance for EffR is available"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, String, String]]]
    val program = MC.pure(42)
    runEffR(program, "env").map(result => assertEquals(result, Right(42)))

  test("EffR.uncancelable masks cancellation"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Unit, String]]]

    var wasPolled = false // scalafix:ok
    val program = MC.uncancelable { poll =>
      wasPolled = true
      poll(EffR.succeed[IO, Unit, String, Int](42))
    }

    runEffR(program, ()).map { result =>
      assert(wasPolled)
      assertEquals(result, Right(42))
    }

  test("EffR.onCancel registers finalizer"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Unit, String]]]

    var finalizerRan = false // scalafix:ok
    val fin = EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { finalizerRan = true }))
    val program = MC.onCancel(MC.canceled, fin)

    program.run(()).either.start.flatMap(_.join).map { outcome =>
      assert(outcome.isCanceled)
      assert(finalizerRan)
    }

  test("EffR.forceR discards left result"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Unit, String]]]

    val left = EffR.succeed[IO, Unit, String, Int](1)
    val right = EffR.succeed[IO, Unit, String, Int](2)
    val combined = MC.forceR(left)(right)

    runEffR(combined, ()).map(result => assertEquals(result, Right(2)))

  test("EffR preserves environment across flatMap"):
    val program = for
      env <- EffR.service[IO, String, String]
      len <- EffR.succeed[IO, String, String, Int](env.length)
    yield len

    runEffR(program, "hello").map(result => assertEquals(result, Right(5)))

  test("MonadError for EffR raises and handles typed errors"):
    import cats.MonadError as ME
    given MonadCancelThrow[IO] = IO.asyncForIO
    val me = summon[ME[EffR.Of[IO, Unit, String], String]]

    val raised = me.raiseError[Int]("boom")
    val handled = me.handleErrorWith(raised)(_ => me.pure(42))

    runEffR(handled, ()).map(result => assertEquals(result, Right(42)))

  test("EffR.guaranteeCase runs finalizer on success"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Unit, String]]]

    var outcome: Option[String] = None // scalafix:ok
    val program = MC.guaranteeCase(EffR.succeed[IO, Unit, String, Int](42)) {
      case Outcome.Succeeded(_) =>
        EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { outcome = Some("succeeded") }))
      case Outcome.Errored(_) =>
        EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { outcome = Some("errored") }))
      case Outcome.Canceled() =>
        EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { outcome = Some("canceled") }))
    }

    runEffR(program, ()).map { result =>
      assertEquals(result, Right(42))
      assertEquals(outcome, Some("succeeded"))
    }

  // --- New combinator tests for Batch 4 ---

  test("EffR.semiflatMap applies effectful function"):
    val prog = EffR.succeed[IO, Unit, String, Int](21).semiflatMap(n => IO.pure(n * 2))
    runEffR(prog, ()).map(result => assertEquals(result, Right(42)))

  test("EffR.semiflatMap short-circuits on failure"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").semiflatMap(_ => IO.pure(42))
    runEffR(prog, ()).map(result => assertEquals(result, Left("boom")))

  test("EffR.catchAll transforms error channel to new error type"):
    val prog = EffR.fail[IO, Unit, String, Int]("err").catchAll(e => EffR.fail[IO, Unit, Int, Int](e.length))
    runEffR(prog, ()).map(result => assertEquals(result, Left(3)))

  test("EffR.catchAll can recover to success"):
    val prog = EffR.fail[IO, Unit, String, Int]("42").catchAll(e => EffR.succeed[IO, Unit, Int, Int](e.toInt))
    runEffR(prog, ()).map(result => assertEquals(result, Right(42)))

  test("EffR.subflatMap chains Either-returning function"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).subflatMap(n => Right(n.toString))
    runEffR(prog, ()).map(result => assertEquals(result, Right("42")))

  test("EffR.subflatMap propagates failure from function"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).subflatMap(_ => Left("boom"))
    runEffR(prog, ()).map(result => assertEquals(result, Left("boom")))

  test("EffR.transform applies function to Either"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).transform(_.map(_ * 2))
    runEffR(prog, ()).map(result => assertEquals(result, Right(84)))

  test("EffR.ensure fails when predicate is false"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).ensure("too small")(_ > 100)
    runEffR(prog, ()).map(result => assertEquals(result, Left("too small")))

  test("EffR.ensure passes when predicate is true"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).ensure("too small")(_ > 0)
    runEffR(prog, ()).map(result => assertEquals(result, Right(42)))

  test("EffR.ensureOr fails with computed error"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).ensureOr(n => s"$n is too small")(_ > 100)
    runEffR(prog, ()).map(result => assertEquals(result, Left("42 is too small")))

  test("EffR.ensureOr passes when predicate is true"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).ensureOr(n => s"$n is too small")(_ > 0)
    runEffR(prog, ()).map(result => assertEquals(result, Right(42)))

  // --- Composition Operator Tests ---

  test("EffR.*> sequences computations, discarding left result"):
    val left = EffR.succeed[IO, String, String, Int](1)
    val right = EffR.succeed[IO, String, String, Int](2)
    runEffR(left *> right, "env").map(r => assertEquals(r, Right(2)))

  test("EffR.*> short-circuits on left failure"):
    val left = EffR.fail[IO, String, String, Int]("boom")
    val right = EffR.succeed[IO, String, String, Int](2)
    runEffR(left *> right, "env").map(r => assertEquals(r, Left("boom")))

  test("EffR.<* sequences computations, discarding right result"):
    val left = EffR.succeed[IO, String, String, Int](1)
    val right = EffR.succeed[IO, String, String, Int](2)
    runEffR(left <* right, "env").map(r => assertEquals(r, Right(1)))

  test("EffR.void discards the success value"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).void
    runEffR(prog, ()).map(r => assertEquals(r, Right(())))

  test("EffR.as replaces the success value"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).as("hello")
    runEffR(prog, ()).map(r => assertEquals(r, Right("hello")))

  test("EffR.flatTap applies effectful function and keeps original value"):
    var sideEffect = 0 // scalafix:ok
    val prog = EffR.succeed[IO, Unit, String, Int](42).flatTap { a =>
      sideEffect = a
      EffR.succeed[IO, Unit, String, String]("discarded")
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assertEquals(sideEffect, 42)
    }

  test("EffR.product combines two computations into a tuple"):
    val left = EffR.succeed[IO, Unit, String, Int](1)
    val right = EffR.succeed[IO, Unit, String, String]("two")
    runEffR(left.product(right), ()).map(r => assertEquals(r, Right((1, "two"))))

  // --- Error Recovery Tests ---

  test("EffR.recover with total function maps errors to success values"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").recover(_.length)
    runEffR(prog, ()).map(r => assertEquals(r, Right(4)))

  test("EffR.recover with total function leaves success unchanged"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).recover(_ => 0)
    runEffR(prog, ()).map(r => assertEquals(r, Right(42)))

  test("EffR.catchAll switches to alternative computation with new error type"):
    val prog: EffR[IO, Unit, Int, Int] = EffR
      .fail[IO, Unit, String, Int]("boom")
      .catchAll(e => EffR.succeed[IO, Unit, Int, Int](e.length))
    runEffR(prog, ()).map(r => assertEquals(r, Right(4)))

  test("EffR.recover with partial function handles matching errors"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    runEffR(prog.recover { case "boom" => 42 }, ()).map(r => assertEquals(r, Right(42)))

  test("EffR.recover with partial function passes through non-matching errors"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("other")
    runEffR(prog.recover { case "boom" => 42 }, ()).map(r => assertEquals(r, Left("other")))

  test("EffR.recoverWith handles matching errors"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    runEffR(prog.recoverWith { case "boom" => EffR.succeed[IO, Unit, String, Int](42) }, ())
      .map(r => assertEquals(r, Right(42)))

  test("EffR.recoverWith passes through non-matching errors"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("other")
    runEffR(prog.recoverWith { case "boom" => EffR.succeed[IO, Unit, String, Int](42) }, ())
      .map(r => assertEquals(r, Left("other")))

  test("EffR.onError executes side effect on failure"):
    var errorObserved: Option[String] = None // scalafix:ok
    val prog = EffR.fail[IO, Unit, String, Int]("boom").onError { case e =>
      errorObserved = Some(e)
      EffR.unit[IO, Unit, String]
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(errorObserved, Some("boom"))
    }

  test("EffR.onError does not execute on success"):
    var executed = false // scalafix:ok
    val prog = EffR.succeed[IO, Unit, String, Int](42).onError { case _ =>
      executed = true
      EffR.unit[IO, Unit, String]
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assert(!executed)
    }

  test("EffR.adaptError transforms matching errors"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    runEffR(prog.adaptError { case "boom" => "BOOM" }, ()).map(r => assertEquals(r, Left("BOOM")))

  test("EffR.alt falls back to alternative on failure"):
    val left: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    val right: EffR[IO, Unit, Int, Int] = EffR.succeed(42)
    val combined: EffR[IO, Unit, Int, Int] = left.alt(right)
    runEffR(combined, ()).map(r => assertEquals(r, Right(42)))

  test("EffR.alt returns original on success"):
    val left = EffR.succeed[IO, Unit, String, Int](1)
    val right = EffR.succeed[IO, Unit, String, Int](2)
    runEffR(left.alt(right), ()).map(r => assertEquals(r, Right(1)))

  // --- Utility Tests ---

  test("EffR.bimap transforms both channels"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").bimap(_.toUpperCase, _ * 2)
    runEffR(prog, ()).map(r => assertEquals(r, Left("BOOM")))

  test("EffR.mapError transforms error channel"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").mapError(_.length)
    runEffR(prog, ()).map(r => assertEquals(r, Left(4)))

  test("EffR.redeem handles both success and failure"):
    val fail: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    val success: EffR[IO, Unit, String, Int] = EffR.succeed(42)

    runEffR(fail.redeem(_ => -1, identity), ()).map(r => assertEquals(r, Right(-1))) *>
      runEffR(success.redeem(_ => -1, _ * 2), ()).map(r => assertEquals(r, Right(84)))

  test("EffR.tapError observes failures"):
    var observed: Option[String] = None // scalafix:ok
    val prog = EffR.fail[IO, Unit, String, Int]("boom").tapError { e =>
      observed = Some(e)
      IO.unit
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("EffR.fold returns to base effect"):
    val fail: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    val success: EffR[IO, Unit, String, Int] = EffR.succeed(42)
    fail.fold(_.length, identity)(()).map(r => assertEquals(r, 4)) *>
      success.fold(_ => 0, identity)(()).map(r => assertEquals(r, 42))

  test("EffR.foldF returns to base effect with effectful handlers"):
    val fail: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    fail.foldF(e => IO.pure(e.length), a => IO.pure(a))(()).map(r => assertEquals(r, 4))

  // --- Constructor Edge Cases ---

  test("EffR.lift(F[Either]) wraps effectful Either"):
    val fea = IO.pure[Either[String, Int]](Right(42))
    val prog = EffR.lift[IO, Unit, String, Int](fea)
    runEffR(prog, ()).map(r => assertEquals(r, Right(42)))

  test("EffR.lift(F[Option]) converts missing values"):
    val fopt = IO.pure(Option.empty[Int])
    val prog = EffR.lift[IO, Unit, String, Int](fopt, "missing")
    runEffR(prog, ()).map(r => assertEquals(r, Left("missing")))

  test("EffR.from(EitherT) extracts underlying computation"):
    import cats.data.EitherT
    val et = EitherT.leftT[IO, Int]("boom")
    val prog = EffR.from[IO, Unit, String, Int](et)
    runEffR(prog, ()).map(r => assertEquals(r, Left("boom")))

  test("EffR.ask is alias for service"):
    val prog = EffR.ask[IO, String, Nothing]
    runEffR(prog, "environment").map(r => assertEquals(r, Right("environment")))

  test("EffR.wrap creates from environment function"):
    val prog = EffR.wrap[IO, Int, String, String] { n =>
      Eff.succeed[IO, String, String]("x" * n)
    }
    runEffR(prog, 5).map(r => assertEquals(r, Right("xxxxx")))

  test("EffR.fromContext creates from context function"):
    val prog = EffR.fromContext[IO, Int, String, Int] {
      Eff.succeed[IO, String, Int](summon[Int] * 2)
    }
    runEffR(prog, 21).map(r => assertEquals(r, Right(42)))

  test("EffR.defer delays evaluation until demanded"):
    var evaluated = false // scalafix:ok
    val deferred = EffR.defer[IO, Unit, String, Int] {
      evaluated = true
      EffR.succeed(42)
    }
    assert(!evaluated, "defer should not evaluate immediately")
    runEffR(deferred, ()).map { r =>
      assert(evaluated, "defer should evaluate when run")
      assertEquals(r, Right(42))
    }

  // --- Variance Edge Cases ---

  test("EffR.assumeEnv allows contravariant environment assumption"):
    val base: EffR[IO, String, Nothing, String] = EffR.service[IO, String, Nothing]
    val assumed: EffR[IO, Any, Nothing, String] = base.assumeEnv[Any]
    runEffR(assumed.asInstanceOf[EffR[IO, String, Nothing, String]], "test").map(r => assertEquals(r, Right("test"))) // scalafix:ok

  test("EffR.widenEnv allows contravariant environment widening"):
    val base: EffR[IO, Any, String, Any] = EffR.service[IO, Any, String]
    val widened: EffR[IO, String, String, Any] = base.widenEnv[String]
    runEffR(widened, "env").map(r => assertEquals(r, Right("env")))

  // --- andThen Edge Cases ---

  test("andThen composes reader chains"):
    val first: EffR[IO, Int, String, String] = EffR.service[IO, Int, String].map(n => "x" * n)
    val second: EffR[IO, String, String, Int] = EffR.service[IO, String, String].map(_.length)
    val composed = first.andThen(second)
    runEffR(composed, 5).map(result => assertEquals(result, Right(5)))

  test("andThen propagates errors from first"):
    val first: EffR[IO, Int, String, String] = EffR.fail("boom")
    val second: EffR[IO, String, String, Int] = EffR.service[IO, String, String].map(_.length)
    val composed = first.andThen(second)
    runEffR(composed, 5).map(result => assertEquals(result, Left("boom")))

  test("andThen propagates errors from second"):
    val first: EffR[IO, Int, String, String] = EffR.succeed("hello")
    val second: EffR[IO, String, String, Int] = EffR.fail("second failed")
    val composed = first.andThen(second)
    runEffR(composed, 5).map(result => assertEquals(result, Left("second failed")))

  // --- redeemAll Edge Cases ---

  test("EffR.redeemAll handles both channels with effectful recovery and error type change"):
    val success: EffR[IO, Unit, String, Int] = EffR.succeed(21)
    val failure: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    // Explicit type parameters allow changing error type from String to Int
    val successRedeemed = success.redeemAll[Int, String](
      _ => EffR.succeed("failed"),
      a => EffR.succeed(s"got $a")
    )
    val failureRedeemed = failure.redeemAll[Int, String](
      e => EffR.succeed(s"recovered from $e"),
      _ => EffR.succeed("ok")
    )
    runEffR(successRedeemed, ()).map(r => assertEquals(r, Right("got 21"))) *>
      runEffR(failureRedeemed, ()).map(r => assertEquals(r, Right("recovered from boom")))

  // --- Additional Missing Edge Cases ---

  test("provide with failing layer propagates error"):
    val base: EffR[IO, String, String, Int] = EffR.service[IO, String, String].map(_.length)
    val failingLayer: EffR[IO, Unit, String, String] = EffR.fail("layer failed")
    val provided = base.provide(failingLayer)(using summon[Monad[IO]])
    runEffR(provided, ()).map(result => assertEquals(result, Left("layer failed")))

  test("rethrow throws error into F"):
    val prog: EffR[IO, Unit, RuntimeException, Int] = EffR.fail(new RuntimeException("boom"))
    prog.run(()).rethrow.attempt.map { r =>
      assert(r.isLeft)
      assertEquals(r.left.toOption.map(_.getMessage), Some("boom"))
    }

  test("rethrow returns value on success"):
    val prog: EffR[IO, Unit, RuntimeException, Int] = EffR.succeed(42)
    prog.run(()).rethrow.map(r => assertEquals(r, 42))

  test("absolve throws error into F"):
    val prog: EffR[IO, Unit, Throwable, Int] = EffR.fail(new RuntimeException("boom"))
    prog.run(()).absolve.attempt.map { r =>
      assert(r.isLeft)
      assertEquals(r.left.toOption.map(_.getMessage), Some("boom"))
    }

  test("absolve returns value on success"):
    val prog: EffR[IO, Unit, Throwable, Int] = EffR.succeed(42)
    prog.run(()).absolve.map(r => assertEquals(r, 42))

  test("eitherT converts to EitherT"):
    import cats.data.EitherT
    val prog: EffR[IO, Unit, String, Int] = EffR.succeed(42)
    val et: EitherT[IO, String, Int] = prog.run(()).eitherT
    et.value.map(r => assertEquals(r, Right(42)))

  test("kleisli converts to Kleisli"):
    import cats.data.Kleisli
    val prog: EffR[IO, String, Nothing, Int] = EffR.service[IO, String, Nothing].map(_.length)
    val k: Kleisli[Eff.Of[IO, Nothing], String, Int] = prog.kleisli
    k.run("hello").either.map(r => assertEquals(r, Right(5)))

  test("EffR.redeemAll can return failure from handlers"):
    val prog: EffR[IO, Unit, String, Int] = EffR.succeed(42)
    val redeemed = prog.redeemAll[Int, String](
      _ => EffR.fail(0),
      _ => EffR.fail(1)
    )
    runEffR(redeemed, ()).map(r => assertEquals(r, Left(1)))

  test("onError with non-matching partial function does not execute"):
    var executed = false // scalafix:ok
    val prog: EffR[IO, Unit, String, Int] = EffR.fail[IO, Unit, String, Int]("other").onError { case "boom" =>
      executed = true
      EffR.unit[IO, Unit, String]
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("other"))
      assert(!executed)
    }

  test("ensure short-circuits on failure input"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("already failed")
    val result = prog.ensure("predicate failed")(_ > 0)
    runEffR(result, ()).map(r => assertEquals(r, Left("already failed")))

  test("tapError observes failures without altering result"):
    var observed: Option[String] = None // scalafix:ok
    val prog: EffR[IO, Unit, String, Int] = EffR.fail[IO, Unit, String, Int]("boom").tapError { e =>
      observed = Some(e)
      IO.unit
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("tapError does not execute on success"):
    var executed = false // scalafix:ok
    val prog: EffR[IO, Unit, String, Int] = EffR.succeed[IO, Unit, String, Int](42).tapError(_ => IO { executed = true })
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assert(!executed)
    }
end EffRSuite
