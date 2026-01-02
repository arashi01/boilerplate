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
import scala.util.Try

import cats.*
import cats.effect.*
import cats.effect.std.AtomicCell
import cats.syntax.all.*
import munit.CatsEffectSuite

/** Test suite for Eff - the zero-cost typed error channel abstraction.
  *
  * Design principles:
  *   - Test OUR logic paths, not upstream cats/cats-effect behaviour
  *   - Cover each code path in our implementation
  *   - Focus on edge cases and regression protection
  *   - Avoid redundant tests that merely confirm upstream behaviour
  */
class EffSuite extends CatsEffectSuite:
  private def runEff[E, A](eff: Eff[IO, E, A]): IO[Either[E, A]] = eff.either

  // ===========================================================================
  // Type Aliases
  // ===========================================================================

  test("UEff represents infallible effect"):
    val eff: UEff[IO, Int] = Eff[IO].succeed(42)
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("TEff represents Throwable-errored effect"):
    val ex = new RuntimeException("boom")
    val eff: TEff[IO, Int] = Eff.fail(ex)
    runEff(eff).map(r => assertEquals(r, Left(ex)))

  // ===========================================================================
  // Constructors - Partially Applied
  // ===========================================================================

  test("Eff[F].succeed creates Right"):
    runEff(Eff[IO].succeed(42)).map(r => assertEquals(r, Right(42)))

  test("Eff[F].fail creates Left"):
    runEff(Eff[IO].fail("err")).map(r => assertEquals(r, Left("err")))

  test("Eff[F].from wraps Either unchanged"):
    for
      r <- runEff(Eff[IO].from(Right(42): Either[String, Int]))
      l <- runEff(Eff[IO].from(Left("err"): Either[String, Int]))
    yield
      assertEquals(r, Right(42))
      assertEquals(l, Left("err"))

  test("Eff[F].liftF embeds F[A] as success"):
    runEff(Eff[IO].liftF(IO.pure(42))).map(r => assertEquals(r, Right(42)))

  test("Eff[F].unit produces Right(())"):
    runEff(Eff[IO].unit).map(r => assertEquals(r, Right(())))

  // ===========================================================================
  // Constructors - Static Methods
  // ===========================================================================

  test("Eff.from(Option) converts None to Left"):
    runEff(Eff.from[IO, String, Int](None, "missing")).map(r => assertEquals(r, Left("missing")))

  test("Eff.from(Option) converts Some to Right"):
    runEff(Eff.from[IO, String, Int](Some(42), "missing")).map(r => assertEquals(r, Right(42)))

  test("Eff.from(Try) converts Failure via mapper"):
    val ex = new RuntimeException("boom")
    val eff = Eff.from[IO, String, Int](Try(throw ex), _.getMessage) // scalafix:ok DisableSyntax.throw
    runEff(eff).map(r => assertEquals(r, Left("boom")))

  test("Eff.from(Try) converts Success to Right"):
    runEff(Eff.from[IO, String, Int](Try(42), _.getMessage)).map(r => assertEquals(r, Right(42)))

  test("Eff.from(EitherT) extracts underlying computation"):
    import cats.data.EitherT
    val et = EitherT.leftT[IO, Int]("err")
    runEff(Eff.from(et)).map(r => assertEquals(r, Left("err")))

  test("Eff.lift wraps F[Either] directly"):
    val fea = IO.pure(Left("err"): Either[String, Int])
    runEff(Eff.lift(fea)).map(r => assertEquals(r, Left("err")))

  test("Eff.lift(F[Option]) converts None via ifNone"):
    runEff(Eff.lift(IO.pure(Option.empty[Int]), "missing")).map(r => assertEquals(r, Left("missing")))

  test("Eff.lift(F[Option]) converts Some to Right"):
    runEff(Eff.lift(IO.pure(Some(42)), "missing")).map(r => assertEquals(r, Right(42)))

  test("Eff.attempt captures F errors via mapper"):
    val ex = new RuntimeException("boom")
    runEff(Eff.attempt(IO.raiseError[Int](ex), _.getMessage)).map(r => assertEquals(r, Left("boom")))

  test("Eff.attempt passes through success"):
    runEff(Eff.attempt(IO.pure(42), _.getMessage)).map(r => assertEquals(r, Right(42)))

  test("Eff.defer delays evaluation until run"):
    var evaluated = false // scalafix:ok DisableSyntax.var
    val eff = Eff.defer[IO, String, Int] { evaluated = true; Eff.succeed(42) }
    assert(!evaluated)
    runEff(eff).map { r =>
      assert(evaluated)
      assertEquals(r, Right(42))
    }

  test("Eff.delay suspends side effect until run"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.delay[IO, String, Int] { executed = true; Right(42) }
    assert(!executed)
    runEff(eff).map { r =>
      assert(executed)
      assertEquals(r, Right(42))
    }

  test("Eff.delay captures Left result"):
    val eff = Eff.delay[IO, String, Int](Left("boom"))
    runEff(eff).map(r => assertEquals(r, Left("boom")))

  test("Eff.delay captures Right result"):
    val eff = Eff.delay[IO, String, Int](Right(42))
    runEff(eff).map(r => assertEquals(r, Right(42)))

  // ===========================================================================
  // Conditional Execution
  // ===========================================================================

  test("Eff.when executes on true"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.when[IO, String](true)(Eff.liftF(IO { executed = true }))
    runEff(eff).map(_ => assert(executed))

  test("Eff.when skips on false"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.when[IO, String](false)(Eff.liftF(IO { executed = true }))
    runEff(eff).map(_ => assert(!executed))

  test("Eff.unless executes on false"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.unless[IO, String](false)(Eff.liftF(IO { executed = true }))
    runEff(eff).map(_ => assert(executed))

  test("Eff.unless skips on true"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.unless[IO, String](true)(Eff.liftF(IO { executed = true }))
    runEff(eff).map(_ => assert(!executed))

  // ===========================================================================
  // Mapping and Transformation
  // ===========================================================================

  test("leftMap transforms error"):
    runEff(Eff.fail[IO, String, Int]("boom").leftMap(_.length)).map(r => assertEquals(r, Left(4)))

  test("leftMap preserves success"):
    runEff(Eff.succeed[IO, String, Int](42).leftMap(_.length)).map(r => assertEquals(r, Right(42)))

  test("bimap transforms both channels"):
    for
      s <- runEff(Eff.succeed[IO, String, Int](21).bimap(_.length, _ * 2))
      f <- runEff(Eff.fail[IO, String, Int]("boom").bimap(_.length, _ * 2))
    yield
      assertEquals(s, Right(42))
      assertEquals(f, Left(4))

  test("semiflatMap applies effectful function"):
    runEff(Eff.succeed[IO, String, Int](21).semiflatMap(a => IO.pure(a * 2))).map(r => assertEquals(r, Right(42)))

  test("semiflatMap short-circuits on failure"):
    var called = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("err").semiflatMap { _ => called = true; IO.pure(42) }
    runEff(eff).map { r =>
      assertEquals(r, Left("err"))
      assert(!called)
    }

  test("subflatMap chains pure Either"):
    val eff = Eff.succeed[IO, String, Int](10).subflatMap(a => if a > 5 then Right(a * 2) else Left("small"))
    runEff(eff).map(r => assertEquals(r, Right(20)))

  test("subflatMap propagates inner Left"):
    val eff = Eff.succeed[IO, String, Int](3).subflatMap(a => if a > 5 then Right(a * 2) else Left("small"))
    runEff(eff).map(r => assertEquals(r, Left("small")))

  test("transform applies function to Either"):
    val eff = Eff.succeed[IO, String, Int](21).transform(_.map(_ * 2))
    runEff(eff).map(r => assertEquals(r, Right(42)))

  // ===========================================================================
  // Error Recovery
  // ===========================================================================

  test("valueOr maps all errors to success"):
    runEff(Eff.fail[IO, String, Int]("boom").valueOr(_.length)).map(r => assertEquals(r, Right(4)))

  test("valueOr preserves success"):
    runEff(Eff.succeed[IO, String, Int](42).valueOr(_ => 0)).map(r => assertEquals(r, Right(42)))

  test("catchAll switches to alternative computation"):
    val eff = Eff.fail[IO, String, Int]("boom").catchAll(_ => Eff.succeed[IO, Int, Int](42))
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("catchAll allows error type change"):
    val eff: Eff[IO, Int, Int] = Eff.fail[IO, String, Int]("boom").catchAll(e => Eff.fail(e.length))
    runEff(eff).map(r => assertEquals(r, Left(4)))

  // ===========================================================================
  // Alternative
  // ===========================================================================

  test("alt falls back on failure"):
    runEff(Eff.fail[IO, String, Int]("err").alt(Eff.succeed(42))).map(r => assertEquals(r, Right(42)))

  test("alt returns original on success"):
    runEff(Eff.succeed[IO, String, Int](1).alt(Eff.succeed(2))).map(r => assertEquals(r, Right(1)))

  test("alt allows error type change"):
    val eff = Eff.fail[IO, String, Int]("err").alt(Eff.fail[IO, Int, Int](42))
    runEff(eff).map(r => assertEquals(r, Left(42)))

  test("orElseSucceed recovers to constant"):
    runEff(Eff.fail[IO, String, Int]("err").orElseSucceed(42)).map(r => assertEquals(r, Right(42)))

  test("orElseFail replaces error"):
    runEff(Eff.fail[IO, String, Int]("err").orElseFail(404)).map(r => assertEquals(r, Left(404)))

  // ===========================================================================
  // Observation
  // ===========================================================================

  test("tap observes success"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).tap(a => IO { observed = Some(a) })
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(observed, Some(42))
    }

  test("tap skips on failure"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("err").tap(_ => IO { executed = true })
    runEff(eff).map { r =>
      assertEquals(r, Left("err"))
      assert(!executed)
    }

  test("tapError observes failure"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("boom").tapError(e => IO { observed = Some(e) })
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("tapError skips on success"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).tapError(_ => IO { executed = true })
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(!executed)
    }

  test("flatTapError observes failure with fallible side effect"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("boom").flatTapError { e =>
      observed = Some(e)
      Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("flatTapError propagates side effect failure"):
    val eff = Eff.fail[IO, String, Int]("original").flatTapError(_ => Eff.fail("side-effect"))
    runEff(eff).map(r => assertEquals(r, Left("side-effect")))

  // ===========================================================================
  // Folding
  // ===========================================================================

  test("fold applies appropriate function"):
    for
      s <- Eff.succeed[IO, String, Int](42).fold(_.length, _.toString)
      f <- Eff.fail[IO, String, Int]("boom").fold(_.length, _.toString)
    yield
      assertEquals(s, "42")
      assertEquals(f, 4)

  test("foldF allows effectful handlers"):
    Eff.fail[IO, String, Int]("boom").foldF(e => IO.pure(e.length), a => IO.pure(a)).map(r => assertEquals(r, 4))

  test("redeemAll allows error type change"):
    val eff = Eff
      .fail[IO, String, Int]("boom")
      .redeemAll[Int, String](
        e => Eff.fail(e.length),
        a => Eff.succeed(a.toString)
      )
    runEff(eff).map(r => assertEquals(r, Left(4)))

  test("redeemAll can produce success from error"):
    val eff = Eff
      .fail[IO, String, Int]("boom")
      .redeemAll[Nothing, String](
        e => Eff.succeed(s"recovered: $e"),
        a => Eff.succeed(a.toString)
      )
    runEff(eff).map(r => assertEquals(r, Right("recovered: boom")))

  // ===========================================================================
  // Composition
  // ===========================================================================

  test("flatTap keeps original value"):
    var sideEffect = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).flatTap { a => sideEffect = a; Eff.succeed("ignored") }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(sideEffect, 42)
    }

  test("flatTap short-circuits on side effect failure"):
    runEff(Eff.succeed[IO, String, Int](42).flatTap(_ => Eff.fail("tap failed"))).map(r => assertEquals(r, Left("tap failed")))

  // ===========================================================================
  // Variance
  // ===========================================================================

  test("widen upcasts success type"):
    val eff: Eff[IO, String, Any] = Eff.succeed[IO, String, Int](42).widen[Any]
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("widenError upcasts error type"):
    val eff: Eff[IO, Any, Int] = Eff.fail[IO, String, Int]("err").widenError[Any]
    runEff(eff).map(r => assertEquals(r, Left("err")))

  test("assume downcasts success type"):
    val eff = Eff.succeed[IO, String, Any](42).assume[Int]
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("assumeError downcasts error type"):
    val eff = Eff.fail[IO, Any, Int]("err").assumeError[String]
    runEff(eff).map(r => assertEquals(r, Left("err")))

  // ===========================================================================
  // Extraction
  // ===========================================================================

  test("option converts success to Some"):
    runEff(Eff.succeed[IO, String, Int](42).option).map(r => assertEquals(r, Right(Some(42))))

  test("option converts failure to None"):
    runEff(Eff.fail[IO, String, Int]("err").option).map(r => assertEquals(r, Right(None)))

  test("collectSome extracts inner Some"):
    val eff: Eff[IO, String, Option[Int]] = Eff.succeed(Some(42))
    runEff(eff.collectSome("missing")).map(r => assertEquals(r, Right(42)))

  test("collectSome fails on inner None"):
    val eff: Eff[IO, String, Option[Int]] = Eff.succeed(None)
    runEff(eff.collectSome("missing")).map(r => assertEquals(r, Left("missing")))

  test("collectSome preserves prior failure"):
    val eff: Eff[IO, String, Option[Int]] = Eff.fail("prior")
    runEff(eff.collectSome("missing")).map(r => assertEquals(r, Left("prior")))

  test("collectRight extracts inner Right"):
    val eff: Eff[IO, String, Either[Int, String]] = Eff.succeed(Right("ok"))
    runEff(eff.collectRight(n => s"code: $n")).map(r => assertEquals(r, Right("ok")))

  test("collectRight fails on inner Left"):
    val eff: Eff[IO, String, Either[Int, String]] = Eff.succeed(Left(404))
    runEff(eff.collectRight(n => s"code: $n")).map(r => assertEquals(r, Left("code: 404")))

  // ===========================================================================
  // Conversion
  // ===========================================================================

  test("either unwraps to F[Either]"):
    Eff.succeed[IO, String, Int](42).either.map(r => assertEquals(r, Right(42)))

  test("eitherT wraps as EitherT"):
    Eff.fail[IO, String, Int]("err").eitherT.value.map(r => assertEquals(r, Left("err")))

  test("absolve re-raises error into F"):
    val ex = new RuntimeException("boom")
    Eff.fail[IO, Throwable, Int](ex).absolve.attempt.map(r => assertEquals(r.left.toOption.map(_.getMessage), Some("boom")))

  test("absolve returns value on success"):
    Eff.succeed[IO, Throwable, Int](42).absolve.map(r => assertEquals(r, 42))

  // ===========================================================================
  // Bracket
  // ===========================================================================

  test("bracket runs release on success"):
    var released = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).bracket(a => Eff.succeed(a * 2))(_ => IO { released = true })
    runEff(eff).map { r =>
      assertEquals(r, Right(84))
      assert(released)
    }

  test("bracket runs release on typed error"):
    var released = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).bracket(_ => Eff.fail("use failed"))(_ => IO { released = true })
    runEff(eff).map { r =>
      assertEquals(r, Left("use failed"))
      assert(released)
    }

  test("bracket skips when acquire fails"):
    var released = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("acquire failed").bracket(a => Eff.succeed(a))(_ => IO { released = true })
    runEff(eff).map { r =>
      assertEquals(r, Left("acquire failed"))
      assert(!released)
    }

  test("bracketCase provides outcome to release"):
    var outcomeSucceeded = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).bracketCase(a => Eff.succeed(a)) { (_, oc) =>
      oc match
        case Outcome.Succeeded(_) => IO { outcomeSucceeded = true }
        case _                    => IO.unit
    }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(outcomeSucceeded)
    }

  // ===========================================================================
  // Timeout
  // ===========================================================================

  test("timeout returns value within duration"):
    runEff(Eff.succeed[IO, String, Int](42).timeout(1.second, "timeout")).map(r => assertEquals(r, Right(42)))

  test("timeout returns error when exceeded"):
    val eff = Eff.liftF[IO, String, Int](IO.sleep(1.second) *> IO.pure(42)).timeout(10.millis, "timeout")
    runEff(eff).map(r => assertEquals(r, Left("timeout")))

  // ===========================================================================
  // Collection Operations
  // ===========================================================================

  test("traverse short-circuits on first error"):
    var count = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.traverse[IO, String, Int, Int](List(1, 2, 3)) { n =>
      count += 1
      if n == 2 then Eff.fail("stop") else Eff.succeed(n * 2)
    }
    runEff(eff).map { r =>
      assertEquals(r, Left("stop"))
      assertEquals(count, 2)
    }

  test("traverse collects all successes"):
    val eff = Eff.traverse[IO, String, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2))
    runEff(eff).map(r => assertEquals(r, Right(List(2, 4, 6))))

  test("sequence collects all successes"):
    val effs = List(Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Int](2), Eff.succeed[IO, String, Int](3))
    runEff(Eff.sequence(effs)).map(r => assertEquals(r, Right(List(1, 2, 3))))

  test("parTraverse runs in parallel"):
    val eff = Eff.parTraverse[IO, String, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2))
    runEff(eff).map(r => assertEquals(r, Right(List(2, 4, 6))))

  test("parTraverse short-circuits on error"):
    val eff = Eff.parTraverse[IO, String, Int, Int](List(1, 2, 3)) { n =>
      if n == 2 then Eff.fail("stop") else Eff.succeed(n * 2)
    }
    runEff(eff).map(r => assertEquals(r, Left("stop")))

  test("retry retries on failure"):
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.retry(
      Eff.liftF[IO, String, Unit](IO(attempts += 1)).flatMap(_ => if attempts < 3 then Eff.fail("retry") else Eff.succeed(42)),
      maxRetries = 5
    )
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(attempts, 3)
    }

  test("retry fails after max retries"):
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.retry(Eff.liftF[IO, String, Unit](IO(attempts += 1)) *> Eff.fail("fail"), maxRetries = 3)
    runEff(eff).map { r =>
      assertEquals(r, Left("fail"))
      assertEquals(attempts, 4) // 1 initial + 3 retries
    }

  test("parSequence collects all successes in parallel"):
    val effs = List(Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Int](2), Eff.succeed[IO, String, Int](3))
    runEff(Eff.parSequence(effs)).map(r => assertEquals(r, Right(List(1, 2, 3))))

  test("parSequence short-circuits on first error"):
    val effs = List(Eff.succeed[IO, String, Int](1), Eff.fail[IO, String, Int]("stop"), Eff.succeed[IO, String, Int](3))
    runEff(Eff.parSequence(effs)).map(r => assertEquals(r, Left("stop")))

  test("retryWithBackoff succeeds after transient failures"):
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.retryWithBackoff(
      Eff.liftF[IO, String, Unit](IO(attempts += 1)).flatMap(_ => if attempts < 3 then Eff.fail("retry") else Eff.succeed(42)),
      maxRetries = 5,
      initialDelay = 1.millis,
      maxDelay = Some(10.millis)
    )
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(attempts, 3)
    }

  test("retryWithBackoff fails after exhausting retries"):
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.retryWithBackoff(
      Eff.liftF[IO, String, Unit](IO(attempts += 1)) *> Eff.fail("fail"),
      maxRetries = 2,
      initialDelay = 1.millis,
      maxDelay = None
    )
    runEff(eff).map { r =>
      assertEquals(r, Left("fail"))
      assertEquals(attempts, 3) // 1 initial + 2 retries
    }

  test("retryWithBackoff respects maxDelay cap"):
    val eff = Eff.retryWithBackoff(
      Eff.liftF[IO, String, Unit](IO.unit) *> Eff.fail("fail"),
      maxRetries = 3,
      initialDelay = 100.millis,
      maxDelay = Some(50.millis) // Cap should prevent 200ms, 400ms delays
    )
    for
      start <- IO.monotonic
      result <- runEff(eff)
      end <- IO.monotonic
      elapsed = end - start
    yield
      assertEquals(result, Left("fail"))
      // With 50ms cap: delays are 50ms, 50ms, 50ms = 150ms total, not 100+200+400=700ms
      assert(clue(elapsed) < 300.millis)

  // ===========================================================================
  // Primitive Lifts
  // ===========================================================================

  test("liftRef transforms Ref to Eff context"):
    for
      ref <- Ref.of[IO, Int](0)
      liftedRef = Eff.liftRef[IO, String, Int](ref)
      _ <- liftedRef.update(_ + 1).either
      value <- liftedRef.get.either
    yield assertEquals(value, Right(1))

  test("liftCell transforms AtomicCell to Eff context"):
    for
      cell <- AtomicCell[IO].of(0)
      liftedCell = Eff.liftCell[IO, String, Int](cell)
      _ <- liftedCell.update(_ + 1).either
      value <- liftedCell.get.either
    yield assertEquals(value, Right(1))

  test("liftCell.evalModify handles typed errors"):
    for
      cell <- AtomicCell[IO].of(0)
      liftedCell = Eff.liftCell[IO, String, Int](cell)
      result <- liftedCell.evalModify(_ => Eff.fail[IO, String, (Int, Int)]("err")).either
      value <- liftedCell.get.either
    yield
      assertEquals(result, Left("err"))
      assertEquals(value, Right(0)) // Cell unchanged

  // ===========================================================================
  // Typeclass Instances
  // ===========================================================================

  test("typed errors and defects are distinguishable"):
    val typedError: Eff[IO, String, Int] = Eff.fail("typed")
    val defect: Eff[IO, String, Int] = Eff.liftF(IO.raiseError(new RuntimeException("defect")))
    for
      typed <- runEff(typedError).attempt
      defect <- runEff(defect).attempt
    yield
      assert(typed.isRight) // IO succeeds with Left
      assertEquals(typed.toOption.get, Left("typed"))
      assert(defect.isLeft) // IO fails
      assertEquals(defect.left.toOption.map(_.getMessage), Some("defect"))

  test("Parallel instance enables parMapN"):
    val eff = (Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Int](2)).parMapN(_ + _)
    runEff(eff).map(r => assertEquals(r, Right(3)))

  test("Parallel instance short-circuits on error"):
    val eff = (Eff.succeed[IO, String, Int](1), Eff.fail[IO, String, Int]("err")).parMapN(_ + _)
    runEff(eff).map(r => assertEquals(r, Left("err")))

  // ===========================================================================
  // GenConcurrent Primitives (ref, deferred via typeclass)
  // ===========================================================================

  test("GenConcurrent.ref creates Ref in Eff context"):
    import cats.effect.kernel.GenConcurrent
    val C = summon[GenConcurrent[Eff.Of[IO, String], Throwable]]
    for
      ref <- C.ref(42).either
      _ <- ref.fold(
             _ => IO.pure(fail("Ref creation should succeed")),
             r => r.get.either.map(v => assertEquals(v, Right(42)))
           )
    yield ()

  test("GenConcurrent.ref supports Eff operations"):
    import cats.effect.kernel.GenConcurrent
    val C = summon[GenConcurrent[Eff.Of[IO, String], Throwable]]
    for
      refResult <- C.ref(0).either
      _ <- refResult match
             case Right(ref) =>
               for
                 _ <- ref.update(_ + 10).either
                 value <- ref.get.either
               yield assertEquals(value, Right(10))
             case Left(e) => IO.pure(fail(s"Ref creation failed: $e"))
    yield ()

  test("GenConcurrent.deferred creates Deferred in Eff context"):
    import cats.effect.kernel.GenConcurrent
    val C = summon[GenConcurrent[Eff.Of[IO, String], Throwable]]
    for
      defResult <- C.deferred[Int].either
      _ <- defResult match
             case Right(d) =>
               for
                 _ <- d.complete(42).either
                 value <- d.get.either
               yield assertEquals(value, Right(42))
             case Left(e) => IO.pure(fail(s"Deferred creation failed: $e"))
    yield ()

  // ===========================================================================
  // GenTemporal Primitives (sleep via typeclass)
  // ===========================================================================

  test("GenTemporal.sleep suspends for duration"):
    import cats.effect.kernel.GenTemporal
    val T = summon[GenTemporal[Eff.Of[IO, String], Throwable]]
    for
      start <- IO.monotonic
      _ <- T.sleep(10.millis).either
      end <- IO.monotonic
    yield assert(clue(end - start) >= 10.millis)

  test("GenTemporal.timeout fails with TimeoutException on exceeded"):
    import cats.effect.kernel.GenTemporal
    val T = summon[GenTemporal[Eff.Of[IO, String], Throwable]]
    val slow = T.sleep(1.second) *> T.pure(42)
    // TimeoutException is raised in the F[_] defect channel, not the typed error channel
    T.timeout(slow, 10.millis).either.attempt.map {
      case Left(e: java.util.concurrent.TimeoutException) => assert(true)
      case Right(_)                                       => fail("Should have timed out")
      case Left(e)                                        => fail(s"Wrong error type: ${e.getClass.getName}")
    }

  test("GenTemporal.timeout succeeds when within duration"):
    import cats.effect.kernel.GenTemporal
    val T = summon[GenTemporal[Eff.Of[IO, String], Throwable]]
    val fast = T.pure(42)
    T.timeout(fast, 1.second).either.map(r => assertEquals(r, Right(42)))

  test("GenTemporal.monotonic returns time in Eff context"):
    import cats.effect.kernel.GenTemporal
    val T = summon[GenTemporal[Eff.Of[IO, String], Throwable]]
    for
      t1 <- T.monotonic.either
      _ <- T.sleep(5.millis).either
      t2 <- T.monotonic.either
    yield
      assert(t1.isRight)
      assert(t2.isRight)
      assert(t2.toOption.get >= t1.toOption.get)

  test("GenTemporal.realTime returns wall clock time"):
    import cats.effect.kernel.GenTemporal
    val T = summon[GenTemporal[Eff.Of[IO, String], Throwable]]
    T.realTime.either.map { r =>
      assert(r.isRight)
      assert(r.toOption.get.toMillis > 0)
    }

  // ===========================================================================
  // Data Typeclass Instances
  // ===========================================================================
  // These tests verify OUR implementation logic for data typeclasses.
  // Law tests verify algebraic properties; these verify design decisions.

  test("Foldable.foldLeft skips error values"):
    import cats.Foldable
    // Using Option as base since IO doesn't have Foldable
    val F = summon[Foldable[Eff.Of[Option, String]]]
    val failure: Eff[Option, String, Int] = Eff.fail("err")
    val result = F.foldLeft(failure, 0)(_ + _)
    assertEquals(result, 0) // Error treated as empty

  test("Foldable.foldLeft accumulates success values"):
    import cats.Foldable
    val F = summon[Foldable[Eff.Of[Option, String]]]
    val success: Eff[Option, String, Int] = Eff.succeed(42)
    val result = F.foldLeft(success, 10)(_ + _)
    assertEquals(result, 52)

  test("Foldable.foldRight skips error values"):
    import cats.{Eval, Foldable}
    val F = summon[Foldable[Eff.Of[Option, String]]]
    val failure: Eff[Option, String, Int] = Eff.fail("err")
    val result = F.foldRight(failure, Eval.now(0))((a, acc) => acc.map(_ + a)).value
    assertEquals(result, 0)

  test("Foldable handles None in outer layer"):
    import cats.Foldable
    val F = summon[Foldable[Eff.Of[Option, String]]]
    val absent: Eff[Option, String, Int] = Eff.lift(None)
    val result = F.foldLeft(absent, 100)(_ + _)
    assertEquals(result, 100) // Outer None means no values to fold

  test("Traverse.traverse transforms success values"):
    import cats.Traverse
    val T = summon[Traverse[Eff.Of[Option, String]]]
    val success: Eff[Option, String, Int] = Eff.succeed(21)
    val result: Option[Eff[Option, String, Int]] = T.traverse(success)(a => Option(a * 2))
    assertEquals(result.map(_.either), Some(Some(Right(42))))

  test("Traverse.traverse preserves error values unchanged"):
    import cats.Traverse
    val T = summon[Traverse[Eff.Of[Option, String]]]
    val failure: Eff[Option, String, Int] = Eff.fail("err")
    val result: Option[Eff[Option, String, Int]] = T.traverse(failure)(a => Option(a * 2))
    assertEquals(result.map(_.either), Some(Some(Left("err"))))

  test("Traverse.traverse handles None in function result"):
    import cats.Traverse
    val T = summon[Traverse[Eff.Of[Option, String]]]
    val success: Eff[Option, String, Int] = Eff.succeed(42)
    val result: Option[Eff[Option, String, Int]] = T.traverse(success)(_ => None)
    assertEquals(result, None)

  test("Bifoldable.bifoldLeft folds error channel"):
    import cats.Bifoldable
    val BF = summon[Bifoldable[[E, A] =>> Eff[Option, E, A]]]
    val failure: Eff[Option, String, Int] = Eff.fail("err")
    val result = BF.bifoldLeft(failure, "")(
      (acc, e) => acc + e, // error handler
      (acc, _) => acc // success handler (shouldn't run)
    )
    assertEquals(result, "err")

  test("Bifoldable.bifoldLeft folds success channel"):
    import cats.Bifoldable
    val BF = summon[Bifoldable[[E, A] =>> Eff[Option, E, A]]]
    val success: Eff[Option, String, Int] = Eff.succeed(42)
    val result = BF.bifoldLeft(success, 0)(
      (acc, _) => acc, // error handler (shouldn't run)
      (acc, a) => acc + a // success handler
    )
    assertEquals(result, 42)

  test("Bifoldable handles None in outer layer"):
    import cats.Bifoldable
    val BF = summon[Bifoldable[[E, A] =>> Eff[Option, E, A]]]
    val absent: Eff[Option, String, Int] = Eff.lift(None)
    val result = BF.bifoldLeft(absent, "default")(
      (acc, e) => acc + e,
      (acc, a) => acc + a.toString
    )
    assertEquals(result, "default") // Outer None means no values to fold

  test("Bitraverse.bitraverse transforms error channel"):
    import cats.Bitraverse
    val BT = summon[Bitraverse[[E, A] =>> Eff[Option, E, A]]]
    val failure: Eff[Option, String, Int] = Eff.fail("err")
    val result: Option[Eff[Option, Int, String]] = BT.bitraverse(failure)(
      e => Option(e.length), // error to Int
      a => Option(a.toString) // success to String
    )
    assertEquals(result.map(_.either), Some(Some(Left(3)))) // "err".length = 3

  test("Bitraverse.bitraverse transforms success channel"):
    import cats.Bitraverse
    val BT = summon[Bitraverse[[E, A] =>> Eff[Option, E, A]]]
    val success: Eff[Option, String, Int] = Eff.succeed(42)
    val result: Option[Eff[Option, Int, String]] = BT.bitraverse(success)(
      e => Option(e.length),
      a => Option(a.toString)
    )
    assertEquals(result.map(_.either), Some(Some(Right("42"))))

  test("Bitraverse.bitraverse handles None in function result"):
    import cats.Bitraverse
    val BT = summon[Bitraverse[[E, A] =>> Eff[Option, E, A]]]
    val success: Eff[Option, String, Int] = Eff.succeed(42)
    val result: Option[Eff[Option, Int, String]] = BT.bitraverse(success)(
      _ => Option(0),
      _ => None // Function returns None
    )
    assertEquals(result, None)

  // ===========================================================================
  // Static Factory Tests - New APIs
  // ===========================================================================

  test("Eff.suspend captures side effect as success"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.suspend[IO, String, Int] { executed = true; 42 }
    assert(!executed)
    runEff(eff).map { r =>
      assert(executed)
      assertEquals(r, Right(42))
    }

  test("Eff[F].suspend is equivalent to static Eff.suspend"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff[IO].suspend { executed = true; 42 }
    assert(!executed)
    runEff(eff).map { r =>
      assert(executed)
      assertEquals(r, Right(42))
    }

  test("Eff.sleep suspends for specified duration"):
    for
      start <- IO.monotonic
      _ <- runEff(Eff.sleep[IO, String](10.millis))
      end <- IO.monotonic
    yield assert(clue(end - start) >= 10.millis)

  test("Eff.monotonic returns monotonic time"):
    for
      t1 <- runEff(Eff.monotonic[IO, String])
      _ <- IO.sleep(5.millis)
      t2 <- runEff(Eff.monotonic[IO, String])
    yield
      assert(t1.isRight)
      assert(t2.isRight)
      assert(t2.toOption.get >= t1.toOption.get)

  test("Eff.realTime returns wall clock time"):
    runEff(Eff.realTime[IO, String]).map { r =>
      assert(r.isRight)
      assert(r.toOption.get.toMillis > 0)
    }

  test("Eff.ref creates Ref directly"):
    for
      refResult <- runEff(Eff.ref[IO, String, Int](42))
      value <- refResult match
                 case Right(ref) => ref.get.either
                 case Left(e)    => IO.pure(Left(e))
    yield assertEquals(value, Right(42))

  test("Eff.deferred creates Deferred directly"):
    for
      deferredResult <- runEff(Eff.deferred[IO, String, Int])
      _ <- deferredResult match
             case Right(d) =>
               for
                 _ <- d.complete(42).either
                 value <- d.get.either
               yield assertEquals(value, Right(42))
             case Left(e) => IO.pure(fail(s"Deferred creation failed: $e"))
    yield ()

  test("Eff.canceled introduces cancellation"):
    val prog = Eff.canceled[IO, String].void
    prog.either.start.flatMap(_.join).map(oc => assert(oc.isCanceled))

  test("Eff.cede yields to scheduler"):
    runEff(Eff.cede[IO, String]).map(r => assertEquals(r, Right(())))

  test("Eff.never never completes"):
    val prog = Eff.never[IO, String, Int]
    prog.either.timeout(50.millis).attempt.map { r =>
      assert(r.isLeft) // Should timeout
    }

  test("Eff.fromFuture converts successful Future"):
    import scala.concurrent.Future
    val eff = Eff.fromFuture(IO(Future.successful(42)), _.getMessage)
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("Eff.fromFuture converts failed Future via mapper"):
    import scala.concurrent.Future
    val ex = new RuntimeException("boom")
    val eff = Eff.fromFuture(IO(Future.failed[Int](ex)), _.getMessage)
    runEff(eff).map(r => assertEquals(r, Left("boom")))

  test("Eff.raiseWhen raises on true"):
    runEff(Eff.raiseWhen[IO, String](true)("boom")).map(r => assertEquals(r, Left("boom")))

  test("Eff.raiseWhen succeeds on false"):
    runEff(Eff.raiseWhen[IO, String](false)("boom")).map(r => assertEquals(r, Right(())))

  test("Eff.raiseUnless raises on false"):
    runEff(Eff.raiseUnless[IO, String](false)("boom")).map(r => assertEquals(r, Left("boom")))

  test("Eff.raiseUnless succeeds on true"):
    runEff(Eff.raiseUnless[IO, String](true)("boom")).map(r => assertEquals(r, Right(())))

  // ===========================================================================
  // Concurrency Extension Tests
  // ===========================================================================

  test("start spawns fibre and allows join"):
    val eff = Eff.succeed[IO, String, Int](42).start
    for
      fibResult <- runEff(eff)
      outcome <- fibResult match
                   case Right(fib) => fib.join.either
                   case Left(e)    => IO.pure(Left(e))
    yield outcome match
      case Right(Outcome.Succeeded(effResult)) =>
        // Need to extract the actual value from the Eff
        ()
      case _ => fail("Expected Succeeded outcome")

  test("race returns winner"):
    val slow = Eff.liftF[IO, String, Int](IO.sleep(1.second) *> IO.pure(1))
    val fast = Eff.succeed[IO, String, Int](2)
    runEff(slow.race(fast)).map { r =>
      assertEquals(r, Right(Right(2))) // fast wins
    }

  test("race cancels loser"):
    var slowRan = false // scalafix:ok DisableSyntax.var
    val slow = Eff.liftF[IO, String, Int](IO.sleep(1.second) *> IO { slowRan = true; 1 })
    val fast = Eff.succeed[IO, String, Int](2)
    runEff(slow.race(fast))
      .flatMap { _ =>
        IO.sleep(50.millis).as(!slowRan) // slow should have been cancelled
      }
      .map(assert(_))

  test("both runs concurrently and returns tuple"):
    val a = Eff.succeed[IO, String, Int](1)
    val b = Eff.succeed[IO, String, Int](2)
    runEff(a.both(b)).map(r => assertEquals(r, Right((1, 2))))

  test("both fails fast on error"):
    val good = Eff.liftF[IO, String, Int](IO.sleep(1.second) *> IO.pure(1))
    val bad = Eff.fail[IO, String, Int]("boom")
    runEff(good.both(bad)).map(r => assertEquals(r, Left("boom")))

  test("background spawns supervised fibre"):
    val eff = Eff.succeed[IO, String, Int](42)
    eff.background
      .use { join =>
        IO.sleep(10.millis) *> join
      }
      .map {
        case Outcome.Succeeded(effResult) => () // Success
        case _                            => fail("Expected Succeeded outcome")
      }

  // ===========================================================================
  // Temporal Extension Tests
  // ===========================================================================

  test("delayBy delays execution"):
    for
      start <- IO.monotonic
      result <- runEff(Eff.succeed[IO, String, Int](42).delayBy(10.millis))
      end <- IO.monotonic
    yield
      assertEquals(result, Right(42))
      assert(clue(end - start) >= 10.millis)

  test("andWait waits after execution"):
    for
      start <- IO.monotonic
      result <- runEff(Eff.succeed[IO, String, Int](42).andWait(10.millis))
      end <- IO.monotonic
    yield
      assertEquals(result, Right(42))
      assert(clue(end - start) >= 10.millis)

  test("timed returns duration with result"):
    runEff(Eff.succeed[IO, String, Int](42).timed).map {
      case Right((dur, value)) =>
        assertEquals(value, 42)
        assert(dur >= 0.nanos)
      case Left(e) => fail(s"Unexpected error: $e")
    }

  test("timeoutTo returns fallback on timeout"):
    val slow = Eff.liftF[IO, String, Int](IO.sleep(1.second) *> IO.pure(1))
    val fallback = Eff.succeed[IO, String, Int](42)
    runEff(slow.timeoutTo(10.millis, fallback)).map(r => assertEquals(r, Right(42)))

  test("timeoutTo returns value within duration"):
    val fast = Eff.succeed[IO, String, Int](42)
    val fallback = Eff.succeed[IO, String, Int](0)
    runEff(fast.timeoutTo(1.second, fallback)).map(r => assertEquals(r, Right(42)))

  // ===========================================================================
  // Cancellation Extension Tests
  // ===========================================================================

  test("onCancel runs finaliser on cancellation"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val eff = Eff.canceled[IO, String].onCancel(Eff.liftF(IO { finRan = true }))
    eff.either.start.flatMap(_.join).map { oc =>
      assert(oc.isCanceled)
      assert(finRan)
    }

  test("onCancel does not run on success"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).onCancel(Eff.liftF(IO { finRan = true }))
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(!finRan)
    }

  test("guarantee runs finaliser on success"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).guarantee(Eff.liftF(IO { finRan = true }))
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(finRan)
    }

  test("guarantee runs finaliser on error"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("boom").guarantee(Eff.liftF(IO { finRan = true }))
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assert(finRan)
    }

  test("guaranteeCase provides outcome"):
    var observedSuccess = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).guaranteeCase {
      case Outcome.Succeeded(_) => Eff.liftF(IO { observedSuccess = true })
      case _                    => Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(observedSuccess)
    }

  // ===========================================================================
  // Parallel Extension Tests
  // ===========================================================================

  test("&> runs in parallel discarding left"):
    val a = Eff.succeed[IO, String, Int](1)
    val b = Eff.succeed[IO, String, String]("two")
    runEff(a &> b).map(r => assertEquals(r, Right("two")))

  test("<& runs in parallel discarding right"):
    val a = Eff.succeed[IO, String, Int](1)
    val b = Eff.succeed[IO, String, String]("two")
    runEff(a <& b).map(r => assertEquals(r, Right(1)))

  test("&> short-circuits on left error"):
    val a = Eff.fail[IO, String, Int]("boom")
    val b = Eff.succeed[IO, String, String]("two")
    runEff(a &> b).map(r => assertEquals(r, Left("boom")))

  test("<& short-circuits on right error"):
    val a = Eff.succeed[IO, String, Int](1)
    val b = Eff.fail[IO, String, String]("boom")
    runEff(a <& b).map(r => assertEquals(r, Left("boom")))

  // ===========================================================================
  // Error Observation Extension Tests
  // ===========================================================================

  test("attemptTap observes success"):
    var observed: Option[Either[String, Int]] = None // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).attemptTap { ea =>
      observed = Some(ea)
      Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(observed, Some(Right(42)))
    }

  test("attemptTap observes error"):
    var observed: Option[Either[String, Int]] = None // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("boom").attemptTap { ea =>
      observed = Some(ea)
      Eff.unit[IO, String]
    }
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some(Left("boom")))
    }

  test("attemptTap propagates side effect error"):
    val eff = Eff.succeed[IO, String, Int](42).attemptTap(_ => Eff.fail("side-effect"))
    runEff(eff).map(r => assertEquals(r, Left("side-effect")))
end EffSuite
