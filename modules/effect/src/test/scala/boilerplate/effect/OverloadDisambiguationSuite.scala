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

// Every row pairs an infix `eff.map`/`.flatMap`/... call with the expanded `Eff.method(eff)(...)`
// control and asserts they agree, all under a blanket `import cats.syntax.all.*` - the scope in
// which the package-level twins must stay selected ahead of cats' Ops conversions, whose `flatMap`
// would pin `E` to the receiver's and reject an error-widening for-comprehension.
class OverloadDisambiguationSuite extends CatsEffectSuite:

  test("map on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[AppError, Int] = Eff.succeed(21)

    val result = eff.map(_ * 2)
    val control = Eff.map(eff)(_ * 2)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("void on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[AppError, Int] = Eff.succeed(42)

    val result: Eff[AppError, Unit] = eff.void
    val control: Eff[AppError, Unit] = Eff.void(eff)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(()))
      assertEquals(c, Right(()))

  test("as on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[AppError, Int] = Eff.succeed(42)

    val result = eff.as("done")
    val control = Eff.as(eff)("done")

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("done"))
      assertEquals(c, Right("done"))

  test("flatMap on Eff selects Eff extension over FlatMap syntax"):
    val eff: Eff[AppError, Int] = Eff.succeed(21)

    val result = eff.flatMap(n => Eff.succeed(n * 2))
    val control = Eff.flatMap(eff)(n => Eff.succeed(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("product on Eff selects Eff extension over Apply syntax"):
    val left: Eff[AppError, Int] = Eff.succeed(1)
    val right: Eff[AppError, String] = Eff.succeed("two")

    val result = left.product(right)
    val control = Eff.product(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right((1, "two")))
      assertEquals(c, Right((1, "two")))

  test("productL (<*) on Eff selects Eff extension over Apply syntax"):
    val left: Eff[AppError, Int] = Eff.succeed(42)
    val right: Eff[AppError, String] = Eff.succeed("ignored")

    val result = left <* right
    val control = Eff.productL(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("productR (*>) on Eff selects Eff extension over Apply syntax"):
    val left: Eff[AppError, Int] = Eff.succeed(1)
    val right: Eff[AppError, String] = Eff.succeed("kept")

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
    val eff: Eff[AppError, Int] = Eff.succeed(42)

    val result = eff.flatTap(n => IO { observed = Some(n) })
    val control = Eff.flatTap(eff)(n => IO { observed = Some(n) })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))
      assertEquals(observed, Some(42))

  test("valueOr on Eff is unique to boilerplate-effect (total error recovery, typed channel)"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

    // `E` is the typed `AppError`, so the handler pattern-matches its subtypes.
    val result: UEff[Int] = eff.valueOr {
      case Invalid(reason) => reason.length
      case _               => -1
    }
    val control: UEff[Int] = Eff.valueOr(eff) {
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
    val eff: Eff[AppError, Int] = Eff.fail(NotFound("x"))

    val result: Eff[Nothing, Int] = eff.catchAll {
      case NotFound(id) => Eff.succeed(id.length)
      case _            => Eff.succeed(-1)
    }
    val control: Eff[Nothing, Int] = Eff.catchAll(eff) {
      case NotFound(id) => Eff.succeed(id.length)
      case _            => Eff.succeed(-1)
    }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(1))
      assertEquals(c, Right(1))

  test("catchOnly disambiguates the infallible-handler twin from the general overload"):
    val consumed: Eff[NotFound | Invalid, Int] = Eff.fail(Invalid("boom"))
    // Infallible handler -> the twin: the residual is inferred narrow with no ascription.
    val narrowed = consumed.catchOnly((_: Invalid) => Eff.succeed(0))
    val _ = summon[narrowed.type <:< Eff[NotFound, Int]]
    // Fallible handler -> the general overload: the handler's return pins the residual.
    val pinned = consumed.catchOnly((_: Invalid) => Eff.fail[NotFound](NotFound("x")))
    val _ = summon[pinned.type <:< Eff[NotFound, Int]]
    for
      n <- narrowed.either
      p <- pinned.either
    yield
      assertEquals(n, Right(0))
      assertEquals(p, Left(NotFound("x")))

  test("catchSome on Eff is unique to boilerplate-effect (partial effectful recovery)"):
    val eff: Eff[AppError, Int] = Eff.fail(NotFound("known"))

    val result: Eff[AppError, Int] = eff.catchSome { case NotFound(_) => Eff.succeed(1) }
    val control: Eff[AppError, Int] = Eff.catchSome(eff) { case NotFound(_) => Eff.succeed(1) }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(1))
      assertEquals(c, Right(1))

  test("redeemAll on Eff is unique to boilerplate-effect (effectful fold with error type change)"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

    // cats uses `redeemWith` with a different signature; `redeemAll` is ours and may change `E`.
    val result: Eff[IoError, String] = eff.redeemAll(
      e => Eff.succeed(s"recovered: ${e.getMessage}"),
      a => Eff.succeed(s"value: $a")
    )
    val control: Eff[IoError, String] = Eff.redeemAll(eff)(
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
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

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
    val eff: Eff[AppError, Int] = Eff.succeed(42)

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
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("first error"))
    val fallback: Eff[IoError, Int] = Eff.succeed(42)

    val result: Eff[IoError, Int] = eff.alt(fallback)
    val control: Eff[IoError, Int] = Eff.alt(eff)(fallback)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("orElseSucceed on Eff is unique to boilerplate-effect"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("error"))

    val result: UEff[Int] = eff.orElseSucceed(0)
    val control: UEff[Int] = Eff.orElseSucceed(eff)(0)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(0))
      assertEquals(c, Right(0))

  test("orElseFail on Eff is unique to boilerplate-effect"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("error"))

    val result: Eff[IoError, Int] = eff.orElseFail(Closed)
    val control: Eff[IoError, Int] = Eff.orElseFail(eff)(Closed)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left(Closed))
      assertEquals(c, Left(Closed))

  test("semiflatMap on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[AppError, Int] = Eff.succeed(21)

    val result = eff.semiflatMap(n => IO.pure(n * 2))
    val control = Eff.semiflatMap(eff)(n => IO.pure(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("subflatMap on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[AppError, Int] = Eff.succeed(21)

    val result = eff.subflatMap(n => Right(n * 2))
    val control = Eff.subflatMap(eff)(n => Right(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("fold on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

    val result: IO[String] = eff.fold(e => s"error: ${e.getMessage}", a => s"value: $a")
    val control: IO[String] = Eff.fold(eff)(e => s"error: ${e.getMessage}", a => s"value: $a")

    for
      r <- result
      c <- control
    yield
      assertEquals(r, "error: invalid: boom")
      assertEquals(c, "error: invalid: boom")

  test("foldF on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

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
    val acquire: Eff[AppError, Int] = Eff.succeed(42)

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
    // The release outcome is over `Eff.Of[AppError]` and the phantom-erased value channel `Int`.
    var outcome: Option[Outcome[Eff.Of[AppError], Throwable, Int]] = None // scalafix:ok DisableSyntax.var
    val acquire: Eff[AppError, Int] = Eff.succeed(42)

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
    val eff: Eff[AppError, Int] = Eff.succeed(42)

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

    val result = Eff.traverse[AppError, Int, Int](items)(n => Eff.succeed(n * 2))

    for r <- result.either
    yield assertEquals(r, Right(List(2, 4, 6)))

  test("Eff.sequence is unique to boilerplate-effect"):
    val effs: List[Eff[AppError, Int]] = List(Eff.succeed(1), Eff.succeed(2), Eff.succeed(3))

    val result = Eff.sequence[AppError, Int](effs)

    for r <- result.either
    yield assertEquals(r, Right(List(1, 2, 3)))

  test("Eff.parTraverse is unique to boilerplate-effect"):
    val items = List(1, 2, 3)

    val result = Eff.parTraverse[AppError, Int, Int](items)(n => Eff.succeed(n * 2))

    for r <- result.either
    yield assertEquals(r, Right(List(2, 4, 6)))

  test("Eff.parSequence is unique to boilerplate-effect"):
    val effs: List[Eff[AppError, Int]] = List(Eff.succeed(1), Eff.succeed(2), Eff.succeed(3))

    val result = Eff.parSequence[AppError, Int](effs)

    for r <- result.either
    yield assertEquals(r, Right(List(1, 2, 3)))

  // orElse is intentionally absent from the Eff API: it would collide with cats
  // ApplicativeError.orElse (which requires the same error type). `alt` is the unique equivalent,
  // and allows the error type to change.

  test("alt falls back only on failure, leaving a success untouched"):
    val recovered: Eff[IoError, Int] =
      (Eff.fail(Invalid("boom")): Eff[AppError, Int]).alt(Eff.succeed(42))
    val untouched: Eff[IoError, Int] =
      (Eff.succeed(1): Eff[AppError, Int]).alt(Eff.succeed(2))

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
  private def retryGeneric[E <: Throwable, A](eff: Eff[E, A], n: Int)(using
    scala.reflect.TypeTest[Throwable, E]
  ): Eff[E, A] =
    Eff.retry(Eff.retryWithBackoff(Eff.retry(eff, n), n, 1.milli, None), RetryPolicy.constant(1.milli).withMaxAttempts(n))

  test("retry on an abstract E resolves the general overload without ambiguity"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

    for r <- retryGeneric(eff, 2).either
    yield assertEquals(r, Left(Invalid("boom")))

  // The package-level map/flatMap twins are selected ahead of cats' Ops conversions, whose
  // `flatMap` would pin `E` to the first step's type. These two tests ARE the guard: either fails
  // to compile if resolution regresses under the blanket cats syntax import at the top of this file.
  test("for-comprehension union widening on Eff survives cats.syntax.all"):
    def findE(id: String): Eff[NotFound, Int] = if id == "1" then Eff.succeed(1) else Eff.fail(NotFound(id))
    def checkE(n: Int): Eff[Invalid, Int] = if n > 0 then Eff.succeed(n) else Eff.fail(Invalid("neg"))

    val widened: Eff[NotFound | Invalid, Int] =
      for
        a <- findE("1")
        b <- checkE(a)
      yield b

    for r <- widened.either
    yield assertEquals(r, Right(1))

  test("for-comprehension union widening on EffResource survives cats.syntax.all"):
    def openR(id: String): EffResource[NotFound, Int] =
      EffResource.eval(if id == "1" then Eff.succeed(1) else Eff.fail(NotFound(id)))
    def checkR(n: Int): EffResource[Invalid, Int] =
      EffResource.eval(if n > 0 then Eff.succeed(n) else Eff.fail(Invalid("neg")))

    val widened: EffResource[NotFound | Invalid, Int] =
      for
        a <- openR("1")
        b <- checkR(a)
      yield b

    for r <- widened.use(n => Eff.succeed(n)).either
    yield assertEquals(r, Right(1))

  test("policy retry disambiguates the retryOn and onRetry overloads by arity, twins included"):
    val policy = RetryPolicy.constant(1.milli).withMaxAttempts(2)
    val typed: Eff[AppError, Int] = Eff.fail(Invalid("boom"))
    val infallible: UEff[Int] = Eff.succeed(1)

    // General overloads on a concrete typed channel: a one-arg lambda selects `retryOn`, a
    // three-arg lambda selects `onRetry`, and both together select the full form.
    val withPred = Eff.retry(typed, policy, (_: AppError) => false)
    val withHook = Eff.retry(typed, policy, (_: Int, _: AppError, _: FiniteDuration) => IO.unit)
    val withBoth = Eff.retry(typed, policy, (_: AppError) => false, (_: Int, _: AppError, _: FiniteDuration) => IO.unit)

    // The `Nothing` twins resolve for a statically infallible receiver - no TypeTest, no retries.
    val twinPlain = Eff.retry(infallible, policy)
    val twinPred = Eff.retry(infallible, policy, (_: Nothing) => true)
    val twinHook = Eff.retry(infallible, policy, (_: Int, _: Nothing, _: FiniteDuration) => IO.unit)
    val twinBoth = Eff.retry(infallible, policy, (_: Nothing) => true, (_: Int, _: Nothing, _: FiniteDuration) => IO.unit)

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
