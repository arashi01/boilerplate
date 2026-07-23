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

import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IoError.*

// With `import cats.syntax.all.*` in scope, each test pairs an infix `eff.map`/`.flatMap`/... call
// with an expanded `Eff.method(eff)(...)` control and asserts they agree - whichever of the `Eff`
// extension or cats' generic syntax wins resolution, behaviour must be identical. Resolution itself
// is not pinned here: cats' imported syntax CAN win, and on an error-widening flatMap it pins `E`
// to the receiver's - the documented reason the README scopes cats syntax imports narrowly.
class OverloadDisambiguationSuite extends CatsEffectSuite:

  test("map on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(21)

    val result = eff.map(_ * 2)
    val control = Eff.map(eff)(_ * 2)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("void on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result: Eff[IO, AppError, Unit] = eff.void
    val control: Eff[IO, AppError, Unit] = Eff.void(eff)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(()))
      assertEquals(c, Right(()))

  test("as on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result = eff.as("done")
    val control = Eff.as(eff)("done")

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("done"))
      assertEquals(c, Right("done"))

  test("flatMap on Eff selects Eff extension over FlatMap syntax"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(21)

    val result = eff.flatMap(n => Eff.succeed(n * 2))
    val control = Eff.flatMap(eff)(n => Eff.succeed(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("product on Eff selects Eff extension over Apply syntax"):
    val left: Eff[IO, AppError, Int] = Eff.succeed(1)
    val right: Eff[IO, AppError, String] = Eff.succeed("two")

    val result = left.product(right)
    val control = Eff.product(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right((1, "two")))
      assertEquals(c, Right((1, "two")))

  test("productL (<*) on Eff selects Eff extension over Apply syntax"):
    val left: Eff[IO, AppError, Int] = Eff.succeed(42)
    val right: Eff[IO, AppError, String] = Eff.succeed("ignored")

    val result = left <* right
    val control = Eff.productL(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("productR (*>) on Eff selects Eff extension over Apply syntax"):
    val left: Eff[IO, AppError, Int] = Eff.succeed(1)
    val right: Eff[IO, AppError, String] = Eff.succeed("kept")

    val result = left *> right
    val control = Eff.productR(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("kept"))
      assertEquals(c, Right("kept"))

  test("flatTap on Eff selects Eff extension over FlatMap syntax"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result = eff.flatTap(n => Eff.liftF(IO { observed = Some(n) }))
    val control = Eff.flatTap(eff)(n => Eff.liftF(IO { observed = Some(n) }))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))
      assertEquals(observed, Some(42))

  test("valueOr on Eff is unique to boilerplate-effect (total error recovery, typed channel)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("boom"))

    // `E` is the typed `AppError`, so the handler pattern-matches its subtypes.
    val result: UEff[IO, Int] = eff.valueOr {
      case Invalid(reason) => reason.length
      case _               => -1
    }
    val control: UEff[IO, Int] = Eff.valueOr(eff) {
      case Invalid(reason) => reason.length
      case _               => -1
    }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(4))
      assertEquals(c, Right(4))

  test("catchAll on Eff is unique to boilerplate-effect (total effectful recovery, typed channel)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(NotFound("x"))

    val result: Eff[IO, Nothing, Int] = eff.catchAll {
      case NotFound(id) => Eff.succeed(id.length)
      case _            => Eff.succeed(-1)
    }
    val control: Eff[IO, Nothing, Int] = Eff.catchAll(eff) {
      case NotFound(id) => Eff.succeed(id.length)
      case _            => Eff.succeed(-1)
    }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(1))
      assertEquals(c, Right(1))

  test("catchSome on Eff is unique to boilerplate-effect (partial effectful recovery)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(NotFound("known"))

    val result: Eff[IO, AppError, Int] = eff.catchSome { case NotFound(_) => Eff.succeed(1) }
    val control: Eff[IO, AppError, Int] = Eff.catchSome(eff) { case NotFound(_) => Eff.succeed(1) }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(1))
      assertEquals(c, Right(1))

  test("redeemAll on Eff is unique to boilerplate-effect (effectful fold with error type change)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("boom"))

    // cats uses `redeemWith` with a different signature; `redeemAll` is ours and may change `E`.
    val result: Eff[IO, IoError, String] = eff.redeemAll(
      e => Eff.succeed(s"recovered: ${e.getMessage}"),
      a => Eff.succeed(s"value: $a")
    )
    val control: Eff[IO, IoError, String] = Eff.redeemAll(eff)(
      e => Eff.succeed(s"recovered: ${e.getMessage}"),
      a => Eff.succeed(s"value: $a")
    )

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("recovered: invalid: boom"))
      assertEquals(c, Right("recovered: invalid: boom"))

  test("tapError on Eff is unique to boilerplate-effect"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("boom"))

    val result = eff.tapError(e => IO { observed = Some(e.getMessage) })
    val control = Eff.tapError(eff)(e => IO { observed = Some(e.getMessage) })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left(Invalid("boom")))
      assertEquals(c, Left(Invalid("boom")))
      assertEquals(observed, Some("invalid: boom"))

  test("tap on Eff is unique to boilerplate-effect"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result = eff.tap(n => IO { observed = Some(n) })
    val control = Eff.tap(eff)(n => IO { observed = Some(n) })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))
      assertEquals(observed, Some(42))

  test("alt on Eff is unique to boilerplate-effect (allows error type change)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("first error"))
    val fallback: Eff[IO, IoError, Int] = Eff.succeed(42)

    val result: Eff[IO, IoError, Int] = eff.alt(fallback)
    val control: Eff[IO, IoError, Int] = Eff.alt(eff)(fallback)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("orElseSucceed on Eff is unique to boilerplate-effect"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("error"))

    val result: UEff[IO, Int] = eff.orElseSucceed(0)
    val control: UEff[IO, Int] = Eff.orElseSucceed(eff)(0)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(0))
      assertEquals(c, Right(0))

  test("orElseFail on Eff is unique to boilerplate-effect"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("error"))

    val result: Eff[IO, IoError, Int] = eff.orElseFail(Closed)
    val control: Eff[IO, IoError, Int] = Eff.orElseFail(eff)(Closed)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left(Closed))
      assertEquals(c, Left(Closed))

  test("semiflatMap on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(21)

    val result = eff.semiflatMap(n => IO.pure(n * 2))
    val control = Eff.semiflatMap(eff)(n => IO.pure(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("subflatMap on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(21)

    val result = eff.subflatMap(n => Right(n * 2))
    val control = Eff.subflatMap(eff)(n => Right(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("fold on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("boom"))

    val result: IO[String] = eff.fold(e => s"error: ${e.getMessage}", a => s"value: $a")
    val control: IO[String] = Eff.fold(eff)(e => s"error: ${e.getMessage}", a => s"value: $a")

    for
      r <- result
      c <- control
    yield
      assertEquals(r, "error: invalid: boom")
      assertEquals(c, "error: invalid: boom")

  test("foldF on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, AppError, Int] = Eff.fail(Invalid("boom"))

    val result: IO[String] = eff.foldF(e => IO.pure(s"error: ${e.getMessage}"), a => IO.pure(s"value: $a"))
    val control: IO[String] = Eff.foldF(eff)(e => IO.pure(s"error: ${e.getMessage}"), a => IO.pure(s"value: $a"))

    for
      r <- result
      c <- control
    yield
      assertEquals(r, "error: invalid: boom")
      assertEquals(c, "error: invalid: boom")

  test("bracket on Eff selects Eff extension (not MonadCancel)"):
    var released = false // scalafix:ok DisableSyntax.var
    val acquire: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result = acquire.bracket(n => Eff.succeed(n * 2))(_ => IO { released = true })
    val control = Eff.bracket(acquire)(n => Eff.succeed(n * 2))(_ => IO { released = true })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(84))
      assertEquals(c, Right(84))
      assert(released)

  test("bracketCase on Eff selects Eff extension (not MonadCancel)"):
    // The release outcome is over `Eff.Of[IO, AppError]` and the phantom-erased value channel `Int`.
    var outcome: Option[Outcome[Eff.Of[IO, AppError], Throwable, Int]] = None // scalafix:ok DisableSyntax.var
    val acquire: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result = acquire.bracketCase(n => Eff.succeed(n * 2)) { (_, oc) =>
      IO { outcome = Some(oc) }
    }
    val control = Eff.bracketCase(acquire)(n => Eff.succeed(n * 2)) { (_, oc) =>
      IO { outcome = Some(oc) }
    }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(84))
      assertEquals(c, Right(84))
      assert(outcome.exists(_.isSuccess))

  test("timeout on Eff selects Eff extension (not Temporal)"):
    val eff: Eff[IO, AppError, Int] = Eff.succeed(42)

    val result = eff.timeout(1.second, Timeout)
    val control = Eff.timeout(eff)(1.second, Timeout)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("Eff.traverse is unique to boilerplate-effect"):
    val items = List(1, 2, 3)

    val result = Eff.traverse[IO, AppError, Int, Int](items)(n => Eff.succeed(n * 2))

    for r <- result.either
    yield assertEquals(r, Right(List(2, 4, 6)))

  test("Eff.sequence is unique to boilerplate-effect"):
    val effs: List[Eff[IO, AppError, Int]] =
      List(Eff.succeed[IO, AppError, Int](1), Eff.succeed[IO, AppError, Int](2), Eff.succeed[IO, AppError, Int](3))

    val result = Eff.sequence[IO, AppError, Int](effs)

    for r <- result.either
    yield assertEquals(r, Right(List(1, 2, 3)))

  test("Eff.parTraverse is unique to boilerplate-effect"):
    val items = List(1, 2, 3)

    val result = Eff.parTraverse[IO, AppError, Int, Int](items)(n => Eff.succeed(n * 2))

    for r <- result.either
    yield assertEquals(r, Right(List(2, 4, 6)))

  test("Eff.parSequence is unique to boilerplate-effect"):
    val effs: List[Eff[IO, AppError, Int]] =
      List(Eff.succeed[IO, AppError, Int](1), Eff.succeed[IO, AppError, Int](2), Eff.succeed[IO, AppError, Int](3))

    val result = Eff.parSequence[IO, AppError, Int](effs)

    for r <- result.either
    yield assertEquals(r, Right(List(1, 2, 3)))

  // orElse is intentionally absent from the Eff API: it would collide with cats
  // ApplicativeError.orElse (which requires the same error type). `alt` is the unique equivalent,
  // and allows the error type to change.

  test("alt falls back only on failure, leaving a success untouched"):
    val recovered: Eff[IO, IoError, Int] =
      (Eff.fail(Invalid("boom")): Eff[IO, AppError, Int]).alt(Eff.succeed(42))
    val untouched: Eff[IO, IoError, Int] =
      (Eff.succeed(1): Eff[IO, AppError, Int]).alt(Eff.succeed(2))

    for
      r <- recovered.either
      u <- untouched.either
    yield
      assertEquals(r, Right(42))
      assertEquals(u, Right(1))

  test("recover on IO selects cats ApplicativeError when Eff syntax in scope"):
    val io: IO[Int] = IO.raiseError(RuntimeException("boom"))

    val result: IO[Int] = io.recover { case _: RuntimeException => 42 }

    for r <- result
    yield assertEquals(r, 42)

  test("handleError on IO selects cats ApplicativeError when Eff syntax in scope"):
    val io: IO[Int] = IO.raiseError(RuntimeException("boom"))

    val result: IO[Int] = io.handleError(_ => 42)

    for r <- result
    yield assertEquals(r, 42)

  test("redeemWith on IO selects cats MonadError when Eff syntax in scope"):
    val io: IO[Int] = IO.raiseError(RuntimeException("boom"))

    val result: IO[String] = io.redeemWith(
      _ => IO.pure("recovered"),
      n => IO.pure(s"value: $n")
    )

    for r <- result
    yield assertEquals(r, "recovered")

  test("flatTap on IO selects cats FlatMap when Eff syntax in scope"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val io: IO[Int] = IO.pure(42)

    val result: IO[Int] = io.flatTap(n => IO { observed = Some(n) })

    for r <- result
    yield
      assertEquals(r, 42)
      assertEquals(observed, Some(42))

  // `retry`/`retryWithBackoff` (counted and policy-driven) ship parameter-pinned `Nothing` twins.
  // Abstract-`E` generic code must keep resolving the general overloads (a twin selects only when
  // `E` is statically `Nothing`); if a twin ever shadowed the general form, this helper would not
  // compile.
  private def retryGeneric[E <: Throwable, A](eff: EffIO[E, A], n: Int)(using
    scala.reflect.TypeTest[Throwable, E]
  ): EffIO[E, A] =
    EffIO.retry(EffIO.retryWithBackoff(EffIO.retry(eff, n), n, 1.milli, None), RetryPolicy.constant(1.milli).withMaxAttempts(n))

  private def retryGenericEff[F[_], E <: Throwable, A](eff: Eff[F, E, A], n: Int)(using
    cats.effect.kernel.GenTemporal[F, Throwable],
    scala.reflect.TypeTest[Throwable, E]
  ): Eff[F, E, A] =
    Eff.retry(Eff.retryWithBackoff(Eff.retry(eff, n), n, 1.milli, None), RetryPolicy.constant(1.milli).withMaxAttempts(n))

  test("retry on an abstract E resolves the general overload without ambiguity"):
    val eff: EffIO[AppError, Int] = EffIO.fail(Invalid("boom"))
    val control: Eff[IO, AppError, Int] = Eff.fail(Invalid("boom"))

    for
      r <- retryGeneric(eff, 2).either
      c <- retryGenericEff(control, 2).either
    yield
      assertEquals(r, Left(Invalid("boom")))
      assertEquals(c, Left(Invalid("boom")))

  test("policy retry disambiguates the retryOn and onRetry overloads by arity, twins included"):
    val policy = RetryPolicy.constant(1.milli).withMaxAttempts(2)
    val typed: EffIO[AppError, Int] = EffIO.fail(Invalid("boom"))
    val infallible: UEffIO[Int] = EffIO.succeed(1)

    // General overloads on a concrete typed channel: a one-arg lambda selects `retryOn`, a
    // three-arg lambda selects `onRetry`, and both together select the full form.
    val withPred = EffIO.retry(typed, policy, (_: AppError) => false)
    val withHook = EffIO.retry(typed, policy, (_: Int, _: AppError, _: FiniteDuration) => IO.unit)
    val withBoth = EffIO.retry(typed, policy, (_: AppError) => false, (_: Int, _: AppError, _: FiniteDuration) => IO.unit)

    // The `Nothing` twins resolve for a statically infallible receiver - no TypeTest, no retries.
    val twinPlain = EffIO.retry(infallible, policy)
    val twinPred = EffIO.retry(infallible, policy, (_: Nothing) => true)
    val twinHook = EffIO.retry(infallible, policy, (_: Int, _: Nothing, _: FiniteDuration) => IO.unit)
    val twinBoth = EffIO.retry(infallible, policy, (_: Nothing) => true, (_: Int, _: Nothing, _: FiniteDuration) => IO.unit)

    for
      p <- withPred.either
      h <- withHook.either
      b <- withBoth.either
      t1 <- twinPlain.either
      t2 <- twinPred.either
      t3 <- twinHook.either
      t4 <- twinBoth.either
    yield
      assertEquals(p, Left(Invalid("boom")))
      assertEquals(h, Left(Invalid("boom")))
      assertEquals(b, Left(Invalid("boom")))
      assertEquals(t1, Right(1))
      assertEquals(t2, Right(1))
      assertEquals(t3, Right(1))
      assertEquals(t4, Right(1))
    end for

end OverloadDisambiguationSuite
