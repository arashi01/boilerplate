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
  private def runEff[E, A](eff: Eff[IO, E, A]): IO[Either[E, A]] = eff.value
  private def runUEff[A](eff: UEff[IO, A]): IO[Either[Nothing, A]] = eff.value

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

  test("Eff[F].fromEither lifts pure Either"):
    val right = Eff[IO].fromEither(Right(42): Either[String, Int])
    val left = Eff[IO].fromEither(Left("boom"): Either[String, Int])
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

  test("succeed produces Right"):
    val eff = Eff.succeed[IO, String, Int](42)
    runEff(eff).map(result => assertEquals(result, Right(42)))

  test("fail produces Left"):
    val eff = Eff.fail[IO, String, Int]("boom")
    runEff(eff).map(result => assertEquals(result, Left("boom")))

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
    val canceled = MC.onCancel(Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Unit](())).value
    canceled.map(result => assertEquals(result, Right(1)))

  test("unit produces Right(())"):
    val eff = Eff.unit[IO, String]
    runEff(eff).map(result => assertEquals(result, Right(())))

  test("fromTry converts failures via mapper"):
    val boom = new RuntimeException("boom")
    val eff = Eff.fromTry[IO, String, Int](Try(throw boom), _.getMessage) // scalafix:ok DisableSyntax.throw
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

    program.value.start.flatMap(_.join).map { outcome =>
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

    program.value.start.flatMap(_.join).map { outcome =>
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

  test("leftFlatMap transforms error channel"):
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom")
    val recovered = eff.leftFlatMap(e => Eff.fail[IO, Int, Int](e.length))
    runEff(recovered).map(result => assertEquals(result, Left(4)))

  test("leftFlatMap can recover to success"):
    val eff: Eff[IO, String, Int] = Eff.fail[IO, String, Int]("boom")
    val recovered = eff.leftFlatMap(_ => Eff.succeed[IO, String, Int](42))
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
end EffSuite
