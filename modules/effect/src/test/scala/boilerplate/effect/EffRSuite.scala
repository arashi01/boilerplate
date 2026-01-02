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
import cats.syntax.all.*
import munit.CatsEffectSuite

/** Test suite for EffR - the reader-style typed error effect.
  *
  * These tests focus on:
  *   - Environment threading (the core value proposition)
  *   - Our logic paths, not upstream cats/cats-effect behavior
  *   - Edge cases specific to our implementation
  *   - Regression protection for API contracts
  */
class EffRSuite extends CatsEffectSuite:
  // Test configuration type
  case class Config(name: String, maxRetries: Int)

  private def runEffR[R, E, A](er: EffR[IO, R, E, A], env: R): IO[Either[E, A]] =
    er.run(env).either

  // ---------------------------------------------------------------------------
  // Core Environment Threading
  // ---------------------------------------------------------------------------
  // These tests verify that the environment is correctly threaded through
  // all combinators - the core value proposition of EffR.

  test("ask retrieves the environment"):
    val prog = EffR.ask[IO, Config, String]
    val config = Config("test", 3)
    runEffR(prog, config).map(r => assertEquals(r, Right(config)))

  test("flatMap threads environment to continuation"):
    val prog = for
      cfg <- EffR.ask[IO, Config, String]
      len <- EffR.succeed[IO, Config, String, Int](cfg.name.length)
    yield len + cfg.maxRetries

    runEffR(prog, Config("hello", 2)).map(r => assertEquals(r, Right(7)))

  test("provide substitutes environment for inner computation"):
    val inner: EffR[IO, String, Nothing, Int] = EffR.ask[IO, String, Nothing].map(_.length)
    val outer: EffR[IO, Config, Nothing, Int] = inner.provide(EffR.ask[IO, Config, Nothing].map(_.name))
    runEffR(outer, Config("hello", 0)).map(r => assertEquals(r, Right(5)))

  test("provide with failing layer propagates error"):
    val inner: EffR[IO, String, String, Int] = EffR.ask[IO, String, String].map(_.length)
    val failingLayer: EffR[IO, Config, String, String] = EffR.fail("layer failed")
    val provided = inner.provide(failingLayer)
    runEffR(provided, Config("ignored", 0)).map(r => assertEquals(r, Left("layer failed")))

  test("contramap transforms environment before use"):
    val base: EffR[IO, String, Nothing, Int] = EffR.ask[IO, String, Nothing].map(_.length)
    val adapted = base.contramap[Config](_.name)
    runEffR(adapted, Config("hello", 0)).map(r => assertEquals(r, Right(5)))

  test("andThen chains readers with output as next environment"):
    val first: EffR[IO, Int, String, String] = EffR.ask[IO, Int, String].map(n => "x" * n)
    val second: EffR[IO, String, String, Int] = EffR.ask[IO, String, String].map(_.length)
    val composed = first.andThen(second)
    runEffR(composed, 5).map(r => assertEquals(r, Right(5)))

  test("andThen propagates error from first computation"):
    val first: EffR[IO, Int, String, String] = EffR.fail("boom")
    val second: EffR[IO, String, String, Int] = EffR.ask[IO, String, String].map(_.length)
    runEffR(first.andThen(second), 5).map(r => assertEquals(r, Left("boom")))

  // ---------------------------------------------------------------------------
  // Constructors - Testing Our Wrapping Logic
  // ---------------------------------------------------------------------------

  test("succeed ignores environment and returns value"):
    val prog = EffR.succeed[IO, String, Nothing, Int](42)
    runEffR(prog, "ignored").map(r => assertEquals(r, Right(42)))

  test("fail ignores environment and returns error"):
    val prog = EffR.fail[IO, String, String, Int]("boom")
    runEffR(prog, "ignored").map(r => assertEquals(r, Left("boom")))

  test("lift discards environment"):
    val eff = Eff.succeed[IO, String, Int](42)
    val prog = EffR.lift[IO, Config, String, Int](eff)
    runEffR(prog, Config("ignored", 0)).map(r => assertEquals(r, Right(42)))

  test("from(Either) wraps pure Either without environment"):
    val right = EffR.from[IO, Unit, String, Int](Right(42))
    val left = EffR.from[IO, Unit, String, Int](Left("boom"))
    for
      r <- runEffR(right, ())
      l <- runEffR(left, ())
    yield
      assertEquals(r, Right(42))
      assertEquals(l, Left("boom"))

  test("from(Option) converts None to error"):
    val some = EffR.from[IO, Unit, String, Int](Some(42), "missing")
    val none = EffR.from[IO, Unit, String, Int](None, "missing")
    for
      s <- runEffR(some, ())
      n <- runEffR(none, ())
    yield
      assertEquals(s, Right(42))
      assertEquals(n, Left("missing"))

  test("from(Try) translates throwables via mapper"):
    val ex = new RuntimeException("boom")
    val success = EffR.from[IO, Unit, String, Int](Try(42), _.getMessage)
    val failure = EffR.from[IO, Unit, String, Int](Try(throw ex), _.getMessage) // scalafix:ok DisableSyntax.throw
    for
      s <- runEffR(success, ())
      f <- runEffR(failure, ())
    yield
      assertEquals(s, Right(42))
      assertEquals(f, Left("boom"))

  test("attempt captures F errors with mapper"):
    val boom = IO.raiseError[Int](new RuntimeException("boom"))
    val prog = EffR.attempt[IO, Unit, String, Int](boom, _.getMessage)
    runEffR(prog, ()).map(r => assertEquals(r, Left("boom")))

  test("defer delays evaluation until run"):
    var evaluated = false // scalafix:ok DisableSyntax.var
    val prog = EffR.defer[IO, Unit, String, Int] {
      evaluated = true
      EffR.succeed(42)
    }
    assert(!evaluated, "defer should not evaluate immediately")
    runEffR(prog, ()).map { r =>
      assert(evaluated, "defer should evaluate when run")
      assertEquals(r, Right(42))
    }

  test("delay suspends side effect until run"):
    var executed = false // scalafix:ok DisableSyntax.var
    val prog = EffR.delay[IO, Unit, String, Int] { executed = true; Right(42) }
    assert(!executed, "delay should not execute immediately")
    runEffR(prog, ()).map { r =>
      assert(executed, "delay should execute when run")
      assertEquals(r, Right(42))
    }

  test("delay captures Left result"):
    val prog = EffR.delay[IO, Unit, String, Int](Left("boom"))
    runEffR(prog, ()).map(r => assertEquals(r, Left("boom")))

  test("delay captures Right result"):
    val prog = EffR.delay[IO, Unit, String, Int](Right(42))
    runEffR(prog, ()).map(r => assertEquals(r, Right(42)))

  test("wrap and fromContext create environment-dependent computations"):
    val wrapped = EffR.wrap[IO, Int, String, Int]((n: Int) => Eff.succeed(n * 2))
    val fromCtx = EffR.fromContext[IO, Int, String, Int](Eff.succeed(summon[Int] * 3))
    for
      w <- runEffR(wrapped, 5)
      c <- runEffR(fromCtx, 5)
    yield
      assertEquals(w, Right(10))
      assertEquals(c, Right(15))

  // ---------------------------------------------------------------------------
  // Mapping and Transformation - Testing Bifunctor Instance
  // ---------------------------------------------------------------------------

  test("leftMap transforms error"):
    runEffR(EffR.fail[IO, Unit, String, Int]("boom").leftMap(_.length), ()).map(r => assertEquals(r, Left(4)))

  test("leftMap preserves success"):
    runEffR(EffR.succeed[IO, Unit, String, Int](42).leftMap(_.length), ()).map(r => assertEquals(r, Right(42)))

  test("bimap transforms both channels"):
    for
      s <- runEffR(EffR.succeed[IO, Unit, String, Int](21).bimap(_.length, _ * 2), ())
      f <- runEffR(EffR.fail[IO, Unit, String, Int]("boom").bimap(_.length, _ * 2), ())
    yield
      assertEquals(s, Right(42))
      assertEquals(f, Left(4))

  // ---------------------------------------------------------------------------
  // Error Recovery - Environment Threading Through Recovery
  // ---------------------------------------------------------------------------

  test("catchAll threads environment to recovery handler"):
    val prog: EffR[IO, Config, String, Int] = EffR
      .fail[IO, Config, String, Int]("boom")
      .catchAll(_ => EffR.ask[IO, Config, String].map(_.maxRetries))
    runEffR(prog, Config("test", 42)).map(r => assertEquals(r, Right(42)))

  test("recoverWith threads environment to partial recovery"):
    val prog: EffR[IO, Config, String, Int] = EffR
      .fail[IO, Config, String, Int]("boom")
      .recoverWith { case "boom" => EffR.ask[IO, Config, String].map(_.maxRetries) }
    runEffR(prog, Config("test", 42)).map(r => assertEquals(r, Right(42)))

  test("recoverWith passes through non-matching errors"):
    val prog: EffR[IO, Unit, String, Int] = EffR
      .fail[IO, Unit, String, Int]("other")
      .recoverWith { case "boom" => EffR.succeed(42) }
    runEffR(prog, ()).map(r => assertEquals(r, Left("other")))

  test("alt threads environment to fallback"):
    val prog = EffR
      .fail[IO, Config, String, Int]("boom")
      .alt(EffR.ask[IO, Config, Nothing].map(_.maxRetries))
    runEffR(prog, Config("test", 99)).map(r => assertEquals(r, Right(99)))

  test("alt returns original on success"):
    val prog = EffR.succeed[IO, Unit, String, Int](1).alt(EffR.succeed(2))
    runEffR(prog, ()).map(r => assertEquals(r, Right(1)))

  test("orElseSucceed recovers to constant value"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").orElseSucceed(42)
    runEffR(prog, ()).map(r => assertEquals(r, Right(42)))

  test("orElseFail replaces error"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").orElseFail(404)
    runEffR(prog, ()).map(r => assertEquals(r, Left(404)))

  test("onError threads environment to side effect"):
    var observed: Option[(Config, String)] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.fail[IO, Config, String, Int]("boom").onError { case e =>
      EffR.ask[IO, Config, String].flatMap { cfg =>
        observed = Some((cfg, e))
        EffR.unit[IO, Config, String]
      }
    }
    runEffR(prog, Config("test", 5)).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some((Config("test", 5), "boom")))
    }

  test("redeemAll threads environment to both handlers"):
    val success: EffR[IO, Config, String, Int] = EffR.succeed(21)
    val failure: EffR[IO, Config, String, Int] = EffR.fail("boom")

    val successRedeemed = success.redeemAll(
      _ => EffR.succeed("failed"),
      a => EffR.ask[IO, Config, Int].map(c => s"got $a with ${c.name}")
    )
    val failureRedeemed = failure.redeemAll(
      e => EffR.ask[IO, Config, Int].map(c => s"recovered $e for ${c.name}"),
      _ => EffR.succeed("ok")
    )

    for
      s <- runEffR(successRedeemed, Config("myapp", 0))
      f <- runEffR(failureRedeemed, Config("myapp", 0))
    yield
      assertEquals(s, Right("got 21 with myapp"))
      assertEquals(f, Right("recovered boom for myapp"))

  // ---------------------------------------------------------------------------
  // Side Effect Observation - tapError Variants
  // ---------------------------------------------------------------------------

  test("tapError(F[Unit]) observes error without environment"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.fail[IO, Unit, String, Int]("boom").tapError { e =>
      IO { observed = Some(e) }
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some("boom"))
    }

  test("flatTapError(EffR) threads environment and observes error"):
    var observed: Option[(Config, String)] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.fail[IO, Config, String, Int]("boom").flatTapError { (e: String) =>
      EffR.ask[IO, Config, String].flatMap { cfg =>
        observed = Some((cfg, e))
        EffR.unit[IO, Config, String]
      }
    }
    runEffR(prog, Config("test", 5)).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some((Config("test", 5), "boom")))
    }

  test("flatTapError propagates side effect failure"):
    val prog = EffR
      .fail[IO, Unit, String, Int]("original")
      .flatTapError(_ => EffR.fail[IO, Unit, String, Unit]("side-effect-error"))
    // Per implementation: if side effect fails, we still return original error
    runEffR(prog, ()).map(r => assertEquals(r, Left("original")))

  test("tap observes success values"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.succeed[IO, Unit, String, Int](42).tap { a =>
      IO { observed = Some(a) }
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assertEquals(observed, Some(42))
    }

  // ---------------------------------------------------------------------------
  // Composition Operators - Environment Preserved
  // ---------------------------------------------------------------------------

  test("*> threads environment to both computations"):
    val prog = EffR.ask[IO, Int, String].as("first") *> EffR.ask[IO, Int, String].map(_ * 2)
    runEffR(prog, 21).map(r => assertEquals(r, Right(42)))

  test("<* threads environment to both computations"):
    val prog = EffR.ask[IO, Int, String].map(_ * 2) <* EffR.ask[IO, Int, String].as("second")
    runEffR(prog, 21).map(r => assertEquals(r, Right(42)))

  test("flatTap threads environment"):
    var observed: Option[(Config, Int)] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.succeed[IO, Config, String, Int](42).flatTap { a =>
      EffR.ask[IO, Config, String].map { cfg =>
        observed = Some((cfg, a))
        ()
      }
    }
    runEffR(prog, Config("test", 0)).map { r =>
      assertEquals(r, Right(42))
      assertEquals(observed, Some((Config("test", 0), 42)))
    }

  test("product threads environment to both computations"):
    val prog = EffR.ask[IO, Config, String].map(_.name).product(EffR.ask[IO, Config, String].map(_.maxRetries))
    runEffR(prog, Config("hello", 5)).map(r => assertEquals(r, Right(("hello", 5))))

  // ---------------------------------------------------------------------------
  // Option/Either Extraction
  // ---------------------------------------------------------------------------

  test("option converts success to Some"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).option
    runEffR(prog, ()).map(r => assertEquals(r, Right(Some(42))))

  test("option converts failure to None"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").option
    runEffR(prog, ()).map(r => assertEquals(r, Right(None)))

  test("collectSome extracts inner Some"):
    val prog: EffR[IO, Unit, String, Option[Int]] = EffR.succeed(Some(42))
    runEffR(prog.collectSome("missing"), ()).map(r => assertEquals(r, Right(42)))

  test("collectSome fails on None"):
    val prog: EffR[IO, Unit, String, Option[Int]] = EffR.succeed(None)
    runEffR(prog.collectSome("missing"), ()).map(r => assertEquals(r, Left("missing")))

  test("collectRight extracts inner Right"):
    val prog: EffR[IO, Unit, String, Either[Int, String]] = EffR.succeed(Right("ok"))
    runEffR(prog.collectRight(n => s"code: $n"), ()).map(r => assertEquals(r, Right("ok")))

  test("collectRight fails on Left"):
    val prog: EffR[IO, Unit, String, Either[Int, String]] = EffR.succeed(Left(404))
    runEffR(prog.collectRight(n => s"code: $n"), ()).map(r => assertEquals(r, Left("code: 404")))

  // ---------------------------------------------------------------------------
  // Bracket - Resource Safety with Environment
  // ---------------------------------------------------------------------------

  test("bracket runs release on success"):
    var released = false // scalafix:ok DisableSyntax.var
    val prog = EffR
      .succeed[IO, Unit, String, Int](42)
      .bracket(a => EffR.succeed[IO, Unit, String, Int](a * 2))(_ => IO { released = true })
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(84))
      assert(released)
    }

  test("bracket runs release on typed error"):
    var released = false // scalafix:ok DisableSyntax.var
    val prog = EffR
      .succeed[IO, Unit, String, Int](42)
      .bracket(_ => EffR.fail[IO, Unit, String, Int]("boom"))(_ => IO { released = true })
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assert(released)
    }

  test("bracket threads environment to use function"):
    val prog = EffR
      .succeed[IO, Config, String, Int](10)
      .bracket(n => EffR.ask[IO, Config, String].map(c => n + c.maxRetries))(_ => IO.unit)
    runEffR(prog, Config("test", 5)).map(r => assertEquals(r, Right(15)))

  test("bracketCase provides outcome to release"):
    var outcomeWasSuccess = false // scalafix:ok DisableSyntax.var
    val prog = EffR
      .succeed[IO, Unit, String, Int](42)
      .bracketCase(a => EffR.succeed[IO, Unit, String, Int](a)) { (_, outcome) =>
        outcome match
          case Outcome.Succeeded(_) => IO { outcomeWasSuccess = true }
          case _                    => IO.unit
      }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assert(outcomeWasSuccess)
    }

  // ---------------------------------------------------------------------------
  // Timeout
  // ---------------------------------------------------------------------------

  test("timeout returns value when within duration"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).timeout(1.second, "timeout")
    runEffR(prog, ()).map(r => assertEquals(r, Right(42)))

  test("timeout returns error when exceeded"):
    val prog = EffR
      .lift[IO, Unit, String, Int](Eff.liftF(IO.sleep(1.second) *> IO.pure(42)))
      .timeout(10.millis, "timeout")
    runEffR(prog, ()).map(r => assertEquals(r, Left("timeout")))

  // ---------------------------------------------------------------------------
  // Typeclass Instances - Direct Implementation Tests
  // ---------------------------------------------------------------------------

  test("Monad.flatMap correctly threads environment"):
    val M = summon[Monad[EffR.Of[IO, Config, String]]]
    val prog = M.flatMap(M.pure(10))(a => EffR.ask[IO, Config, String].map(c => a + c.maxRetries))
    runEffR(prog, Config("test", 5)).map(r => assertEquals(r, Right(15)))

  test("Monad.tailRecM terminates correctly"):
    val M = summon[Monad[EffR.Of[IO, Int, String]]]
    val prog = M.tailRecM(0) { n =>
      EffR.ask[IO, Int, String].map { limit =>
        if n >= limit then Right(n)
        else Left(n + 1)
      }
    }
    runEffR(prog, 5).map(r => assertEquals(r, Right(5)))

  test("MonadError.raiseError and handleErrorWith thread environment"):
    import cats.MonadError as ME
    val me = summon[ME[EffR.Of[IO, Config, String], String]]
    val prog = me.handleErrorWith(me.raiseError[Int]("boom")) { _ =>
      EffR.ask[IO, Config, String].map(_.maxRetries)
    }
    runEffR(prog, Config("test", 42)).map(r => assertEquals(r, Right(42)))

  // ---------------------------------------------------------------------------
  // MonadCancel - Cancellation with Environment
  // ---------------------------------------------------------------------------

  test("MonadCancel.uncancelable threads environment through poll"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Config, String]]]
    val prog = MC.uncancelable { poll =>
      poll(EffR.ask[IO, Config, String].map(_.maxRetries))
    }
    runEffR(prog, Config("test", 42)).map(r => assertEquals(r, Right(42)))

  test("MonadCancel.onCancel threads environment to finalizer"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Config, String]]]

    var finalizerConfig: Option[Config] = None // scalafix:ok DisableSyntax.var
    val fin = EffR.ask[IO, Config, String].flatMap { cfg =>
      finalizerConfig = Some(cfg)
      EffR.unit[IO, Config, String]
    }
    val prog = MC.onCancel(MC.canceled, fin)

    val config = Config("finalizer-test", 99)
    prog.run(config).either.start.flatMap(_.join).map { outcome =>
      assert(outcome.isCanceled)
      assertEquals(finalizerConfig, Some(config))
    }

  test("MonadCancel.guaranteeCase provides outcome with environment"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Config, String]]]

    var observedOutcome: Option[String] = None // scalafix:ok DisableSyntax.var
    val prog = MC.guaranteeCase(EffR.succeed[IO, Config, String, Int](42)) {
      case Outcome.Succeeded(_) =>
        EffR.ask[IO, Config, String].map { cfg =>
          observedOutcome = Some(s"succeeded with ${cfg.name}")
          ()
        }
      case _ =>
        EffR.unit[IO, Config, String]
    }

    runEffR(prog, Config("my-app", 0)).map { r =>
      assertEquals(r, Right(42))
      assertEquals(observedOutcome, Some("succeeded with my-app"))
    }

  test("MonadCancel.forceR threads environment to both computations"):
    given MonadCancelThrow[IO] = IO.asyncForIO
    val MC = summon[MonadCancelThrow[EffR.Of[IO, Int, String]]]
    val prog = MC.forceR(EffR.fail[IO, Int, String, Unit]("ignored"))(
      EffR.ask[IO, Int, String].map(_ * 2)
    )
    runEffR(prog, 21).map(r => assertEquals(r, Right(42)))

  // ---------------------------------------------------------------------------
  // Parallel Instance - Environment Threading in Parallel Operations
  // ---------------------------------------------------------------------------

  test("Parallel.parMapN threads environment to all branches"):
    val prog = (
      EffR.ask[IO, Config, String].map(_.name),
      EffR.ask[IO, Config, String].map(_.maxRetries)
    ).parMapN((name, retries) => s"$name:$retries")
    runEffR(prog, Config("app", 3)).map(r => assertEquals(r, Right("app:3")))

  test("Parallel.parTraverse threads environment to all elements"):
    val inputs = List(1, 2, 3)
    val prog = inputs.parTraverse(n => EffR.ask[IO, Config, String].map(c => n * c.maxRetries))
    runEffR(prog, Config("test", 10)).map(r => assertEquals(r, Right(List(10, 20, 30))))

  // ---------------------------------------------------------------------------
  // Conversion Utilities
  // ---------------------------------------------------------------------------

  test("kleisli converts to Kleisli for interop"):
    import cats.data.Kleisli
    val prog: EffR[IO, String, Nothing, Int] = EffR.ask[IO, String, Nothing].map(_.length)
    val k: Kleisli[Eff.Of[IO, Nothing], String, Int] = prog.kleisli
    k.run("hello").either.map(r => assertEquals(r, Right(5)))

  test("from(Kleisli) converts Kleisli to EffR preserving computation"):
    import cats.data.Kleisli
    val k: Kleisli[Eff.Of[IO, String], Config, Int] =
      Kleisli((cfg: Config) => Eff.succeed[IO, String, Int](cfg.name.length))
    val effr: EffR[IO, Config, String, Int] = EffR.from(k)
    runEffR(effr, Config("hello", 0)).map(r => assertEquals(r, Right(5)))

  test("from(Kleisli) preserves typed errors"):
    import cats.data.Kleisli
    val k: Kleisli[Eff.Of[IO, String], Config, Int] =
      Kleisli((_: Config) => Eff.fail[IO, String, Int]("kleisli error"))
    val effr: EffR[IO, Config, String, Int] = EffR.from(k)
    runEffR(effr, Config("ignored", 0)).map(r => assertEquals(r, Left("kleisli error")))

  test("kleisli.effR round-trips correctly"):
    val original: EffR[IO, Config, String, Int] = EffR.ask[IO, Config, String].map(_.name.length)
    val roundTripped: EffR[IO, Config, String, Int] = original.kleisli.effR
    for
      o <- runEffR(original, Config("hello", 0))
      r <- runEffR(roundTripped, Config("hello", 0))
    yield assertEquals(o, r)

  test("Kleisli.effR extension converts to EffR"):
    import cats.data.Kleisli
    val k: Kleisli[Eff.Of[IO, Nothing], String, Int] =
      Kleisli((s: String) => Eff.succeed[IO, Nothing, Int](s.length * 2))
    val effr: EffR[IO, String, Nothing, Int] = k.effR
    runEffR(effr, "test").map(r => assertEquals(r, Right(8)))

  test("functionK creates FunctionK for natural transformation"):
    import cats.arrow.FunctionK
    val fk: FunctionK[EffR.Of[IO, Config, String], Eff.Of[IO, String]] =
      EffR.functionK(Config("test", 0))(FunctionK.id)

    val prog = EffR.ask[IO, Config, String].map(_.name)
    fk(prog).either.map(r => assertEquals(r, Right("test")))

  test("fold extracts F[B] from both channels"):
    val success: EffR[IO, Unit, String, Int] = EffR.succeed(42)
    val failure: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    for
      s <- success.fold(_.length, _.toString)(())
      f <- failure.fold(_.length, _.toString)(())
    yield
      assertEquals(s, "42")
      assertEquals(f, 4)

  test("foldF allows effectful fold"):
    val prog: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    prog.foldF(e => IO.pure(e.length), a => IO.pure(a))(()).map(r => assertEquals(r, 4))

  // ---------------------------------------------------------------------------
  // Variance Operations
  // ---------------------------------------------------------------------------

  test("widenEnv allows providing more specific environment"):
    val base: EffR[IO, Any, String, String] = EffR.succeed("hello")
    val narrowed: EffR[IO, Config, String, String] = base.widenEnv[Config]
    runEffR(narrowed, Config("ignored", 0)).map(r => assertEquals(r, Right("hello")))

  test("widenError allows widening error type"):
    val base: EffR[IO, Unit, String, Int] = EffR.fail("boom")
    val widened: EffR[IO, Unit, Any, Int] = base.widenError[Any]
    runEffR(widened, ()).map(r => assertEquals(r, Left("boom")))

  // ---------------------------------------------------------------------------
  // Type Alias Tests
  // ---------------------------------------------------------------------------

  test("UEffR type alias represents infallible reader effect"):
    val prog: UEffR[IO, String, Int] = EffR.ask[IO, String, Nothing].map(_.length)
    runEffR(prog, "hello").map(r => assertEquals(r, Right(5)))

  test("TEffR type alias represents Throwable-errored reader effect"):
    val ex = new RuntimeException("boom")
    val prog: TEffR[IO, Unit, Int] = EffR.fail[IO, Unit, Throwable, Int](ex)
    runEffR(prog, ()).map(r => assertEquals(r, Left(ex)))

  // ---------------------------------------------------------------------------
  // Partially Applied Builder
  // ---------------------------------------------------------------------------

  test("EffR[F, R] builder provides ergonomic construction"):
    val prog = EffR[IO, Config].succeed[String, Int](42)
    runEffR(prog, Config("test", 0)).map(r => assertEquals(r, Right(42)))

  test("EffR[F, R].ask retrieves environment"):
    val prog = EffR[IO, Config].ask[String]
    runEffR(prog, Config("myapp", 5)).map(r => assertEquals(r, Right(Config("myapp", 5))))

  // ---------------------------------------------------------------------------
  // Static Factory Tests - New APIs
  // ---------------------------------------------------------------------------

  test("EffR.suspend captures side effect as success"):
    var executed = false // scalafix:ok DisableSyntax.var
    val prog = EffR.suspend[IO, Unit, String, Int] { executed = true; 42 }
    assert(!executed)
    runEffR(prog, ()).map { r =>
      assert(executed)
      assertEquals(r, Right(42))
    }

  test("EffR.sleep suspends for specified duration"):
    for
      start <- IO.monotonic
      _ <- runEffR(EffR.sleep[IO, Unit, String](10.millis), ())
      end <- IO.monotonic
    yield assert(clue(end - start) >= 10.millis)

  test("EffR.monotonic returns monotonic time"):
    for
      t1 <- runEffR(EffR.monotonic[IO, Unit, String], ())
      _ <- IO.sleep(5.millis)
      t2 <- runEffR(EffR.monotonic[IO, Unit, String], ())
    yield
      assert(t1.isRight)
      assert(t2.isRight)
      assert(t2.toOption.get >= t1.toOption.get)

  test("EffR.realTime returns wall clock time"):
    runEffR(EffR.realTime[IO, Unit, String], ()).map { r =>
      assert(r.isRight)
      assert(r.toOption.get.toMillis > 0)
    }

  test("EffR.ref creates Ref directly"):
    for
      refResult <- runEffR(EffR.ref[IO, Unit, String, Int](42), ())
      value <- refResult match
                 case Right(ref) => ref.get.either
                 case Left(e)    => IO.pure(Left(e))
    yield assertEquals(value, Right(42))

  test("EffR.deferred creates Deferred directly"):
    for
      deferredResult <- runEffR(EffR.deferred[IO, Unit, String, Int], ())
      _ <- deferredResult match
             case Right(d) =>
               for
                 _ <- d.complete(42).either
                 value <- d.get.either
               yield assertEquals(value, Right(42))
             case Left(e) => IO.pure(fail(s"Deferred creation failed: $e"))
    yield ()

  test("EffR.canceled introduces cancellation"):
    val prog = EffR.canceled[IO, Unit, String].void
    prog.run(()).either.start.flatMap(_.join).map(oc => assert(oc.isCanceled))

  test("EffR.cede yields to scheduler"):
    runEffR(EffR.cede[IO, Unit, String], ()).map(r => assertEquals(r, Right(())))

  test("EffR.never never completes"):
    val prog = EffR.never[IO, Unit, String, Int]
    prog.run(()).either.timeout(50.millis).attempt.map { r =>
      assert(r.isLeft) // Should timeout
    }

  test("EffR.fromFuture converts successful Future"):
    import scala.concurrent.Future
    val prog: EffR[IO, Unit, String, Int] = EffR.fromFuture(IO(Future.successful(42)), _.getMessage)
    runEffR(prog, ()).map(r => assertEquals(r, Right(42)))

  test("EffR.fromFuture converts failed Future via mapper"):
    import scala.concurrent.Future
    val ex = new RuntimeException("boom")
    val prog: EffR[IO, Unit, String, Int] = EffR.fromFuture(IO(Future.failed[Int](ex)), _.getMessage)
    runEffR(prog, ()).map(r => assertEquals(r, Left("boom")))

  test("EffR.when executes on true"):
    var executed = false // scalafix:ok DisableSyntax.var
    val prog = EffR.when[IO, Unit, String](true)(EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { executed = true })))
    runEffR(prog, ()).map(_ => assert(executed))

  test("EffR.when skips on false"):
    var executed = false // scalafix:ok DisableSyntax.var
    val prog = EffR.when[IO, Unit, String](false)(EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { executed = true })))
    runEffR(prog, ()).map(_ => assert(!executed))

  test("EffR.unless executes on false"):
    var executed = false // scalafix:ok DisableSyntax.var
    val prog = EffR.unless[IO, Unit, String](false)(EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { executed = true })))
    runEffR(prog, ()).map(_ => assert(executed))

  test("EffR.unless skips on true"):
    var executed = false // scalafix:ok DisableSyntax.var
    val prog = EffR.unless[IO, Unit, String](true)(EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { executed = true })))
    runEffR(prog, ()).map(_ => assert(!executed))

  test("EffR.raiseWhen raises on true"):
    runEffR(EffR.raiseWhen[IO, Unit, String](true)("boom"), ()).map(r => assertEquals(r, Left("boom")))

  test("EffR.raiseWhen succeeds on false"):
    runEffR(EffR.raiseWhen[IO, Unit, String](false)("boom"), ()).map(r => assertEquals(r, Right(())))

  test("EffR.raiseUnless raises on false"):
    runEffR(EffR.raiseUnless[IO, Unit, String](false)("boom"), ()).map(r => assertEquals(r, Left("boom")))

  test("EffR.raiseUnless succeeds on true"):
    runEffR(EffR.raiseUnless[IO, Unit, String](true)("boom"), ()).map(r => assertEquals(r, Right(())))

  // ---------------------------------------------------------------------------
  // Concurrency Extension Tests
  // ---------------------------------------------------------------------------

  test("start spawns fibre and allows join"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).start
    for
      fibResult <- runEffR(prog, ())
      outcome <- fibResult match
                   case Right(fib) => fib.join.either
                   case Left(e)    => IO.pure(Left(e))
    yield outcome match
      case Right(Outcome.Succeeded(_)) => () // Success
      case _                           => fail("Expected Succeeded outcome")

  test("race returns winner"):
    val slow = EffR.lift[IO, Unit, String, Int](Eff.liftF(IO.sleep(1.second) *> IO.pure(1)))
    val fast = EffR.succeed[IO, Unit, String, Int](2)
    runEffR(slow.race(fast), ()).map { r =>
      assertEquals(r, Right(Right(2))) // fast wins
    }

  test("both runs concurrently and returns tuple"):
    val a = EffR.succeed[IO, Unit, String, Int](1)
    val b = EffR.succeed[IO, Unit, String, Int](2)
    runEffR(a.both(b), ()).map(r => assertEquals(r, Right((1, 2))))

  test("both fails fast on error"):
    val good = EffR.lift[IO, Unit, String, Int](Eff.liftF(IO.sleep(1.second) *> IO.pure(1)))
    val bad = EffR.fail[IO, Unit, String, Int]("boom")
    runEffR(good.both(bad), ()).map(r => assertEquals(r, Left("boom")))

  // ---------------------------------------------------------------------------
  // Temporal Extension Tests
  // ---------------------------------------------------------------------------

  test("delayBy delays execution"):
    for
      start <- IO.monotonic
      result <- runEffR(EffR.succeed[IO, Unit, String, Int](42).delayBy(10.millis), ())
      end <- IO.monotonic
    yield
      assertEquals(result, Right(42))
      assert(clue(end - start) >= 10.millis)

  test("andWait waits after execution"):
    for
      start <- IO.monotonic
      result <- runEffR(EffR.succeed[IO, Unit, String, Int](42).andWait(10.millis), ())
      end <- IO.monotonic
    yield
      assertEquals(result, Right(42))
      assert(clue(end - start) >= 10.millis)

  test("timed returns duration with result"):
    runEffR(EffR.succeed[IO, Unit, String, Int](42).timed, ()).map {
      case Right((dur, value)) =>
        assertEquals(value, 42)
        assert(dur >= 0.nanos)
      case Left(e) => fail(s"Unexpected error: $e")
    }

  test("timeoutTo returns fallback on timeout"):
    val slow = EffR.lift[IO, Unit, String, Int](Eff.liftF(IO.sleep(1.second) *> IO.pure(1)))
    val fallback = EffR.succeed[IO, Unit, String, Int](42)
    runEffR(slow.timeoutTo(10.millis, fallback), ()).map(r => assertEquals(r, Right(42)))

  test("timeoutTo returns value within duration"):
    val fast = EffR.succeed[IO, Unit, String, Int](42)
    val fallback = EffR.succeed[IO, Unit, String, Int](0)
    runEffR(fast.timeoutTo(1.second, fallback), ()).map(r => assertEquals(r, Right(42)))

  // ---------------------------------------------------------------------------
  // Cancellation Extension Tests
  // ---------------------------------------------------------------------------

  test("onCancel runs finaliser on cancellation"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val prog = EffR.canceled[IO, Unit, String].onCancel(EffR.lift(Eff.liftF(IO { finRan = true })))
    prog.run(()).either.start.flatMap(_.join).map { oc =>
      assert(oc.isCanceled)
      assert(finRan)
    }

  test("onCancel does not run on success"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val prog = EffR.succeed[IO, Unit, String, Int](42).onCancel(EffR.lift(Eff.liftF(IO { finRan = true })))
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assert(!finRan)
    }

  test("guarantee runs finaliser on success"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val prog = EffR.succeed[IO, Unit, String, Int](42).guarantee(EffR.lift(Eff.liftF(IO { finRan = true })))
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assert(finRan)
    }

  test("guarantee runs finaliser on error"):
    var finRan = false // scalafix:ok DisableSyntax.var
    val prog = EffR.fail[IO, Unit, String, Int]("boom").guarantee(EffR.lift(Eff.liftF(IO { finRan = true })))
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assert(finRan)
    }

  test("guaranteeCase provides outcome"):
    var observedSuccess = false // scalafix:ok DisableSyntax.var
    val prog = EffR.succeed[IO, Unit, String, Int](42).guaranteeCase {
      case Outcome.Succeeded(_) => EffR.lift(Eff.liftF(IO { observedSuccess = true }))
      case _                    => EffR.unit[IO, Unit, String]
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assert(observedSuccess)
    }

  // ---------------------------------------------------------------------------
  // Parallel Extension Tests
  // ---------------------------------------------------------------------------

  test("&> runs in parallel discarding left"):
    val a = EffR.succeed[IO, Unit, String, Int](1)
    val b = EffR.succeed[IO, Unit, String, String]("two")
    runEffR(a &> b, ()).map(r => assertEquals(r, Right("two")))

  test("<& runs in parallel discarding right"):
    val a = EffR.succeed[IO, Unit, String, Int](1)
    val b = EffR.succeed[IO, Unit, String, String]("two")
    runEffR(a <& b, ()).map(r => assertEquals(r, Right(1)))

  test("&> short-circuits on left error"):
    val a = EffR.fail[IO, Unit, String, Int]("boom")
    val b = EffR.succeed[IO, Unit, String, String]("two")
    runEffR(a &> b, ()).map(r => assertEquals(r, Left("boom")))

  test("<& short-circuits on right error"):
    val a = EffR.succeed[IO, Unit, String, Int](1)
    val b = EffR.fail[IO, Unit, String, String]("boom")
    runEffR(a <& b, ()).map(r => assertEquals(r, Left("boom")))

  // ---------------------------------------------------------------------------
  // Error Observation Extension Tests
  // ---------------------------------------------------------------------------

  test("attemptTap observes success"):
    var observed: Option[Either[String, Int]] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.succeed[IO, Unit, String, Int](42).attemptTap { ea =>
      observed = Some(ea)
      EffR.unit[IO, Unit, String]
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Right(42))
      assertEquals(observed, Some(Right(42)))
    }

  test("attemptTap observes error"):
    var observed: Option[Either[String, Int]] = None // scalafix:ok DisableSyntax.var
    val prog = EffR.fail[IO, Unit, String, Int]("boom").attemptTap { ea =>
      observed = Some(ea)
      EffR.unit[IO, Unit, String]
    }
    runEffR(prog, ()).map { r =>
      assertEquals(r, Left("boom"))
      assertEquals(observed, Some(Left("boom")))
    }

  test("attemptTap propagates side effect error"):
    val prog = EffR.succeed[IO, Unit, String, Int](42).attemptTap(_ => EffR.fail("side-effect"))
    runEffR(prog, ()).map(r => assertEquals(r, Left("side-effect")))
end EffRSuite
