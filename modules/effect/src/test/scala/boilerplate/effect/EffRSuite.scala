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
  private def runEffR[R, E, A](er: EffR[IO, R, E, A], env: R): IO[Either[E, A]] = er.run(env).value

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
    val some = EffR.fromOption[IO, Unit, String, Int](Some(1), "none")
    val none = EffR.fromOption[IO, Unit, String, Int](None, "none")
    for
      ok <- runEffR(some, ())
      ko <- runEffR(none, ())
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left("none"))

  test("fromTry translates throwables"):
    val boom = new RuntimeException("boom")
    val success = EffR.fromTry[IO, Unit, String, Int](Try(42), _.getMessage)
    val failure = EffR.fromTry[IO, Unit, String, Int](Try(throw boom), _.getMessage) // scalafix:ok DisableSyntax.throw
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

    var wasPolled = false // scalafix:ok DisableSyntax.var
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

    var finalizerRan = false // scalafix:ok DisableSyntax.var
    val fin = EffR.lift[IO, Unit, String, Unit](Eff.liftF(IO { finalizerRan = true }))
    val program = MC.onCancel(MC.canceled, fin)

    program.run(()).value.start.flatMap(_.join).map { outcome =>
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

    var outcome: Option[String] = None // scalafix:ok DisableSyntax.var
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

  test("contramap transforms environment"):
    val base: EffR[IO, Int, String, Int] = EffR.service[IO, Int, String].map(_ * 2)
    val mapped = base.contramap[String](_.length)
    runEffR(mapped, "hello").map(result => assertEquals(result, Right(10)))

  test("andThen composes reader chains"):
    val first: EffR[IO, Int, String, String] = EffR.service[IO, Int, String].map(n => "x" * n)
    val second: EffR[IO, String, String, Int] = EffR.service[IO, String, String].map(_.length)
    val composed = first.andThen(second)
    runEffR(composed, 5).map(result => assertEquals(result, Right(5)))

  test("EffR.semiflatMap applies effectful function"):
    val prog = EffR.succeed[IO, Unit, String, Int](21).semiflatMap(n => IO.pure(n * 2))
    runEffR(prog, ()).map(result => assertEquals(result, Right(42)))

  test("EffR.semiflatMap short-circuits on failure"):
    val prog = EffR.fail[IO, Unit, String, Int]("boom").semiflatMap(_ => IO.pure(42))
    runEffR(prog, ()).map(result => assertEquals(result, Left("boom")))

  test("EffR.leftFlatMap transforms error channel"):
    val prog = EffR.fail[IO, Unit, String, Int]("err").leftFlatMap(e => EffR.fail[IO, Unit, Int, Int](e.length))
    runEffR(prog, ()).map(result => assertEquals(result, Left(3)))

  test("EffR.leftFlatMap can recover to success"):
    val prog = EffR.fail[IO, Unit, String, Int]("42").leftFlatMap(e => EffR.succeed[IO, Unit, Int, Int](e.toInt))
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
end EffRSuite
