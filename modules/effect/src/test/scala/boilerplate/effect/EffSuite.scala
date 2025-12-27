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

import scala.concurrent.duration.*
import scala.util.Try

import cats.Monad
import cats.effect.IO
import cats.effect.MonadCancelThrow
import cats.effect.kernel.Outcome
import cats.effect.kernel.Ref
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

  test("map transforms success"):
    runEff(Eff.succeed[IO, String, Int](21).map(_ * 2)).map(r => assertEquals(r, Right(42)))

  test("map preserves failure"):
    runEff(Eff.fail[IO, String, Int]("err").map(_ * 2)).map(r => assertEquals(r, Left("err")))

  test("mapError transforms error"):
    runEff(Eff.fail[IO, String, Int]("boom").mapError(_.length)).map(r => assertEquals(r, Left(4)))

  test("mapError preserves success"):
    runEff(Eff.succeed[IO, String, Int](42).mapError(_.length)).map(r => assertEquals(r, Right(42)))

  test("bimap transforms both channels"):
    for
      s <- runEff(Eff.succeed[IO, String, Int](21).bimap(_.length, _ * 2))
      f <- runEff(Eff.fail[IO, String, Int]("boom").bimap(_.length, _ * 2))
    yield
      assertEquals(s, Right(42))
      assertEquals(f, Left(4))

  test("flatMap short-circuits on failure"):
    var called = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("err").flatMap { a => called = true; Eff.succeed(a) }
    runEff(eff).map { r =>
      assertEquals(r, Left("err"))
      assert(!called)
    }

  test("flatMap chains success"):
    runEff(Eff.succeed[IO, String, Int](21).flatMap(a => Eff.succeed(a * 2))).map(r => assertEquals(r, Right(42)))

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

  test("recover handles matching errors"):
    runEff(Eff.fail[IO, String, Int]("boom").recover { case "boom" => 42 }).map(r => assertEquals(r, Right(42)))

  test("recover passes through non-matching errors"):
    runEff(Eff.fail[IO, String, Int]("other").recover { case "boom" => 42 }).map(r => assertEquals(r, Left("other")))

  test("recoverWith handles matching errors effectfully"):
    val eff = Eff.fail[IO, String, Int]("boom").recoverWith { case "boom" => Eff.succeed(42) }
    runEff(eff).map(r => assertEquals(r, Right(42)))

  test("recoverWith passes through non-matching errors"):
    val eff = Eff.fail[IO, String, Int]("other").recoverWith { case "boom" => Eff.succeed(42) }
    runEff(eff).map(r => assertEquals(r, Left("other")))

  test("onError executes side effect on matching error"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("boom").onError { case e => observed = Some(e); Eff.unit }
    runEff(eff).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("onError skips non-matching errors"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("other").onError { case "boom" => executed = true; Eff.unit }
    runEff(eff).map { r =>
      assertEquals(r, Left("other"))
      assert(!executed)
    }

  test("onError skips on success"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).onError { case _ => executed = true; Eff.unit }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assert(!executed)
    }

  test("adaptError transforms matching errors"):
    runEff(Eff.fail[IO, String, Int]("boom").adaptError { case "boom" => "BOOM" }).map(r => assertEquals(r, Left("BOOM")))

  test("adaptError passes through non-matching errors"):
    runEff(Eff.fail[IO, String, Int]("other").adaptError { case "boom" => "BOOM" }).map(r => assertEquals(r, Left("other")))

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
  // Guards
  // ===========================================================================

  test("ensure fails when predicate false"):
    runEff(Eff.succeed[IO, String, Int](5).ensure("small")(_ > 10)).map(r => assertEquals(r, Left("small")))

  test("ensure passes when predicate true"):
    runEff(Eff.succeed[IO, String, Int](15).ensure("small")(_ > 10)).map(r => assertEquals(r, Right(15)))

  test("ensure short-circuits on prior failure"):
    runEff(Eff.fail[IO, String, Int]("prior").ensure("guard")(_ > 0)).map(r => assertEquals(r, Left("prior")))

  test("ensureOr fails with computed error"):
    runEff(Eff.succeed[IO, String, Int](5).ensureOr(a => s"$a too small")(_ > 10)).map(r => assertEquals(r, Left("5 too small")))

  test("ensureOr passes when predicate true"):
    runEff(Eff.succeed[IO, String, Int](15).ensureOr(a => s"$a too small")(_ > 10)).map(r => assertEquals(r, Right(15)))

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

  test("redeem always succeeds"):
    for
      s <- runEff(Eff.succeed[IO, String, Int](42).redeem(_.length, _.toString))
      f <- runEff(Eff.fail[IO, String, Int]("boom").redeem(_.length, _.toString))
    yield
      assertEquals(s, Right("42"))
      assertEquals(f, Right(4))

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

  test("*> sequences discarding left result"):
    runEff(Eff.succeed[IO, String, Int](1) *> Eff.succeed(2)).map(r => assertEquals(r, Right(2)))

  test("*> short-circuits on left failure"):
    var executed = false // scalafix:ok DisableSyntax.var
    val eff = Eff.fail[IO, String, Int]("err") *> Eff.liftF(IO { executed = true; 2 })
    runEff(eff).map { r =>
      assertEquals(r, Left("err"))
      assert(!executed)
    }

  test("<* sequences discarding right result"):
    runEff(Eff.succeed[IO, String, Int](1) <* Eff.succeed(2)).map(r => assertEquals(r, Right(1)))

  test("<* short-circuits on right failure"):
    runEff(Eff.succeed[IO, String, Int](1) <* Eff.fail("err")).map(r => assertEquals(r, Left("err")))

  test("product combines into tuple"):
    runEff(Eff.succeed[IO, String, Int](1).product(Eff.succeed("two"))).map(r => assertEquals(r, Right((1, "two"))))

  test("flatTap keeps original value"):
    var sideEffect = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.succeed[IO, String, Int](42).flatTap { a => sideEffect = a; Eff.succeed("ignored") }
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(sideEffect, 42)
    }

  test("flatTap short-circuits on side effect failure"):
    runEff(Eff.succeed[IO, String, Int](42).flatTap(_ => Eff.fail("tap failed"))).map(r => assertEquals(r, Left("tap failed")))

  test("void discards success value"):
    runEff(Eff.succeed[IO, String, Int](42).void).map(r => assertEquals(r, Right(())))

  test("as replaces success value"):
    runEff(Eff.succeed[IO, String, Int](42).as("hello")).map(r => assertEquals(r, Right("hello")))

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

  test("rethrow re-raises error into F"):
    val ex = new RuntimeException("boom")
    Eff.fail[IO, RuntimeException, Int](ex).rethrow.attempt.map(r => assertEquals(r.left.toOption.map(_.getMessage), Some("boom")))

  test("rethrow returns value on success"):
    Eff.succeed[IO, RuntimeException, Int](42).rethrow.map(r => assertEquals(r, 42))

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

  test("Monad instance works with for-comprehension"):
    val M = summon[Monad[Eff.Of[IO, String]]]
    val prog = M.flatMap(M.pure(20))(a => M.pure(a + 22))
    runEff(prog).map(r => assertEquals(r, Right(42)))

  test("Monad.tailRecM terminates"):
    val M = summon[Monad[Eff.Of[IO, String]]]
    val eff = M.tailRecM(0)(n => Eff.succeed(if n >= 5 then Right(n) else Left(n + 1)))
    runEff(eff).map(r => assertEquals(r, Right(5)))

  test("MonadError[E] raises typed errors"):
    val ME = summon[cats.MonadError[Eff.Of[IO, String], String]]
    runEff(ME.raiseError[Int]("err")).map(r => assertEquals(r, Left("err")))

  test("MonadError[E] handles typed errors"):
    val ME = summon[cats.MonadError[Eff.Of[IO, String], String]]
    val eff = ME.handleErrorWith(ME.raiseError[Int]("err"))(_ => ME.pure(42))
    runEff(eff).map(r => assertEquals(r, Right(42)))

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

  test("MonadCancel.canceled propagates cancellation"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]
    var finalizerRan = false // scalafix:ok DisableSyntax.var
    val prog = MC.guaranteeCase(MC.canceled) {
      case Outcome.Canceled() => finalizerRan = true; Eff.unit
      case _                  => Eff.unit
    }
    prog.either.start.flatMap(_.join).map { outcome =>
      assert(outcome.isCanceled)
      assert(finalizerRan)
    }

  test("MonadCancel.forceR discards typed errors from left"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[Eff.Of[IO, String]]]
    runEff(MC.forceR(Eff.fail("ignored"))(Eff.succeed(42))).map(r => assertEquals(r, Right(42)))

  test("Parallel instance enables parMapN"):
    val eff = (Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Int](2)).parMapN(_ + _)
    runEff(eff).map(r => assertEquals(r, Right(3)))

  test("Parallel instance short-circuits on error"):
    val eff = (Eff.succeed[IO, String, Int](1), Eff.fail[IO, String, Int]("err")).parMapN(_ + _)
    runEff(eff).map(r => assertEquals(r, Left("err")))
end EffSuite
