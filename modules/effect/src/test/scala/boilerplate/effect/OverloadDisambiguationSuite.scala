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
import cats.effect.syntax.all.*
import cats.syntax.all.*
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IOError.*

// The whole suite runs under BOTH blanket syntax imports - the scope in which every package-level
// twin must stay selected ahead of cats' and cats-effect's Ops conversions. A conversion that
// captures one of our names resolves a single `F` for both operands, which loses the precise union;
// where our signature differs from the conversion's, the call does not compile at all.
//
// Each behavioural row pairs an infix call with the expanded `Eff.method(eff)(...)` control and
// asserts they agree. The `Twinned` block below adds a compile-time row per twinned name.

// Stable paths for the per-name rows below: one value per twinned name, composed from two distinct
// channels and never given a type, so each row's witness fails if a conversion ever captures the
// call and resolves a single `F` for both operands.
object Twinned:
  val a: Eff[AppError, Int] = Eff.succeed(1)
  val b: Eff[IOError, String] = Eff.succeed("kept")
  val fin: Eff[IOError, Unit] = Eff.succeed(())
  val ra: EffResource[AppError, Int] = EffResource.pure(1)
  val rb: EffResource[IOError, String] = EffResource.pure("kept")

  val productR = a *> b
  val productL = a <* b
  val parProductR = a &> b
  val parProductL = a <& b
  val product = a.product(b)
  val flatTap = a.flatTap(_ => b)
  val void = a.void
  val as = a.as("x")
  val bracket = a.bracket(_ => b)(_ => IO.unit)
  val bracketCase = a.bracketCase(_ => b)((_, _) => IO.unit)
  val start = a.start
  val background = a.background
  val race = a.race(b)
  val both = a.both(b)
  val onCancel = a.onCancel(fin)
  val guarantee = a.guarantee(fin)
  val guaranteeCase = a.guaranteeCase(_ => IO.unit)
  val delayBy = a.delayBy(1.second)
  val andWait = a.andWait(1.second)
  val timed = a.timed
  val evalOn = a.evalOn(scala.concurrent.ExecutionContext.global)
  val timeout = a.timeout(1.second, Closed)
  val timeoutTo = a.timeoutTo(1.second, b.as(0))
  val map = a.map(_ + 1)
  val flatMap = a.flatMap(_ => b)
  val resourceBoth = ra.both(rb)
  val resourceMap = ra.map(_ + 1)
  val resourceFlatMap = ra.flatMap(_ => rb)
  val attemptTap = a.attemptTap(_ => fin)

  // Names cats declares on an Ops class but provides no conversion for on an `Eff` receiver. The
  // rows below assert each still resolves to ours, so the exception cannot outlive its reason.
  val fold = a.fold(_ => 0, identity)
  val valueOr = a.valueOr(_ => 0)
  val catchOnly = a.catchOnly((_: AppError) => Eff.succeed(0))
end Twinned

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

  test("<* on Eff selects Eff extension over Apply syntax, keeping both channels"):
    val left: Eff[AppError, Int] = Eff.succeed(42)
    val right: Eff[IOError, String] = Eff.succeed("ignored")

    // cats' `Apply` syntax would pin the channel to the receiver's; ours unions the two.
    val result: Eff[AppError | IOError, Int] = left <* right
    val control: Eff[AppError | IOError, Int] = Eff.<*(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("*> on Eff selects Eff extension over Apply syntax, keeping both channels"):
    val left: Eff[AppError, Int] = Eff.succeed(1)
    val right: Eff[IOError, String] = Eff.succeed("kept")

    val result: Eff[AppError | IOError, String] = left *> right
    val control: Eff[AppError | IOError, String] = Eff.*>(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("kept"))
      assertEquals(c, Right("kept"))

  test("an inferred union channel is observed infix, with no name and no ascription"):
    // The composed value is never given a type here. If a conversion captured `*>`, it would resolve
    // one `F` for both operands and `either` would then be asked for evidence at the join.
    val left: Eff[AppError, Int] = Eff.succeed(1)
    val right: Eff[IOError, String] = Eff.succeed("kept")

    for observed <- (left *> right).either
    yield assertEquals(observed, Right("kept"))

  test("cats syntax this package does not shadow keeps working on Eff"):
    // The twins cover the colliding names only; everything else cats and cats-effect provide for an
    // `F` with our instances stays available, which is what the blanket imports are here to allow.
    val one: Eff[AppError, Int] = Eff.succeed(1)
    val two: Eff[AppError, Int] = Eff.succeed(2)
    val failed: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

    val paired: Eff[AppError, Int] = (one, two).parMapN(_ + _)
    val chosen: Eff[AppError, Int] = failed <+> two
    val memoised = one.memoize

    for
      p <- paired.either
      c <- chosen.either
      m <- memoised.flatMap(identity).either
    yield
      assertEquals(p, Right(3))
      assertEquals(c, Right(2))
      assertEquals(m, Right(1))

  test("flatTap on Eff selects Eff extension over FlatMap syntax"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[AppError, Int] = Eff.succeed(42)

    val result = eff.flatTap(n => IO { observed = Some(n) })
    val control = Eff.flatTap(eff)(n => IO { observed = Some(n) })
    // The `IO` lambda contributes `Nothing`, so the receiver's channel survives unchanged.
    val _ = summon[result.type <:< Eff[AppError, Int]]

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
    val result: Eff[IOError, String] = eff.redeemAll(
      e => Eff.succeed(s"recovered: ${e.getMessage}"),
      a => Eff.succeed(s"value: $a")
    )
    val control: Eff[IOError, String] = Eff.redeemAll(eff)(
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

  test("alt on Eff is unique to boilerplate-effect (allows error type change)"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("first error"))
    val fallback: Eff[IOError, Int] = Eff.succeed(42)

    val result: Eff[IOError, Int] = eff.alt(fallback)
    val control: Eff[IOError, Int] = Eff.alt(eff)(fallback)

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

    val result: Eff[IOError, Int] = eff.orElseFail(Closed)
    val control: Eff[IOError, Int] = Eff.orElseFail(eff)(Closed)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left(Closed))
      assertEquals(c, Left(Closed))

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

    val result: UEff[String] = eff.fold(e => s"error: ${e.getMessage}", a => s"value: $a")
    val control: UEff[String] = Eff.fold(eff)(e => s"error: ${e.getMessage}", a => s"value: $a")

    for
      r <- result
      c <- control
    yield
      assertEquals(r, "error: invalid: boom")
      assertEquals(c, "error: invalid: boom")

  test("foldF on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[AppError, Int] = Eff.fail(Invalid("boom"))

    val result: UEff[String] = eff.foldF(e => IO.pure(s"error: ${e.getMessage}"), a => IO.pure(s"value: $a"))
    val control: UEff[String] = Eff.foldF(eff)(e => IO.pure(s"error: ${e.getMessage}"), a => IO.pure(s"value: $a"))

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
    val recovered: Eff[IOError, Int] =
      (Eff.fail(Invalid("boom")): Eff[AppError, Int]).alt(Eff.succeed(42))
    val untouched: Eff[IOError, Int] =
      (Eff.succeed(1): Eff[AppError, Int]).alt(Eff.succeed(2))

    for
      r <- recovered.either
      u <- untouched.either
    yield
      assertEquals(r, Right(42))
      assertEquals(u, Right(1))

  test("IO's own recover is unaffected by the package-level Eff extensions"):
    val io: IO[Int] = IO.raiseError(RuntimeException("boom"))

    val result: IO[Int] = io.recover { case _: RuntimeException => 42 }

    for r <- result
    yield assertEquals(r, 42)

  test("IO's own handleError is unaffected by the package-level Eff extensions"):
    val io: IO[Int] = IO.raiseError(RuntimeException("boom"))

    val result: IO[Int] = io.handleError(_ => 42)

    for r <- result
    yield assertEquals(r, 42)

  test("IO's own redeemWith is unaffected by the package-level Eff extensions"):
    val io: IO[Int] = IO.raiseError(RuntimeException("boom"))

    val result: IO[String] = io.redeemWith(
      _ => IO.pure("recovered"),
      n => IO.pure(s"value: $n")
    )

    for r <- result
    yield assertEquals(r, "recovered")

  test("IO's own flatTap is unaffected by the package-level Eff extensions"):
    // `flatTap`, `recover`, `handleError` and `redeemWith` are all members of `IO`
    // (IO.scala:474, 557, 532, 724), and a member is selected ahead of any extension - so an `IO`
    // receiver keeps `IO`'s behaviour and result type whatever this package brings into scope.
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val io: IO[Int] = IO.pure(42)

    val result: IO[Int] = io.flatTap(n => IO { observed = Some(n) })

    for r <- result
    yield
      assertEquals(r, 42)
      assertEquals(observed, Some(42))

  // `retry` ships parameter-pinned `Nothing` twins. Abstract-`E` generic code must keep resolving
  // the general overloads (a twin selects only when `E` is statically `Nothing`); if a twin ever
  // shadowed the general form, this helper would not compile.
  private def retryGeneric[E <: Throwable, A](eff: Eff[E, A], n: Int)(using
    boilerplate.ErrorTest[E]
  ): Eff[E, A] =
    Eff.retry(eff, RetryPolicy.constant(1.milli).withMaxAttempts(n))

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

  // One row per twinned name. For a name whose result carries a union, OBSERVING the composed value
  // is the assertion that bites: a captured call resolves a single `F` for both operands, and the
  // union that produces is widened again when the observer's `E` is instantiated from it - so the
  // static type can look right while `either` no longer typechecks. A `<:<` witness cannot see that.

  test("`*>` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.productR.either"))

  test("`<*` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.productL.either"))

  test("`&>` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.parProductR.either"))

  test("`<&` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.parProductL.either"))

  test("`product` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.product.either"))

  test("`flatTap` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.flatTap.either"))

  test("`bracket` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.bracket.either"))

  test("`bracketCase` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.bracketCase.either"))

  test("`race` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.race.either"))

  test("`both` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.both.either"))

  test("`onCancel` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.onCancel.either"))

  test("`guarantee` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.guarantee.either"))

  test("`timeout` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.timeout.either"))

  test("`timeoutTo` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.timeoutTo.either"))

  test("`flatMap` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.flatMap.either"))

  test("`attemptTap` resolves to the Eff extension, and its union survives observation"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.attemptTap.either"))

  test("`EffResource.both` resolves to the EffResource extension, and its union survives observation"):
    assert(
      scala.compiletime.testing.typeChecks(
        "boilerplate.effect.Twinned.resourceBoth.use(v => boilerplate.effect.Eff.succeed(v)).either"
      )
    )

  test("`EffResource.flatMap` resolves to the EffResource extension, and its union survives observation"):
    assert(
      scala.compiletime.testing.typeChecks(
        "boilerplate.effect.Twinned.resourceFlatMap.use(v => boilerplate.effect.Eff.succeed(v)).either"
      )
    )

  test("`background` resolves to the Eff extension - its result type differs from the conversion's"):
    // Ours yields `Resource[IO, IO[Outcome[Eff.Of[E], ...]]]`; cats-effect's yields the whole thing
    // over `Eff.Of[E]`, so here a witness does distinguish them.
    assert(
      scala.compiletime.testing.typeChecks(
        "summon[boilerplate.effect.Twinned.background.type <:< cats.effect.kernel.Resource[cats.effect.IO, cats.effect.IO[cats.effect.kernel.Outcome[boilerplate.effect.Eff.Of[boilerplate.effect.AppError], Throwable, Int]]]]"
      )
    )

  test("the names whose signature differs from the conversion's compile only as ours"):
    // `timeout` takes the failure to raise, `flatTap` and `bracketCase` take `Eff` continuations,
    // and `guaranteeCase` takes a raw `IO` finaliser - none of which the corresponding conversion
    // accepts. Without the twin these four do not compile at all; `Twinned` compiling is the guard,
    // and these rows state which calls it is guarding.
    assert(
      scala.compiletime.testing.typeChecks(
        "boilerplate.effect.Twinned.a.timeout(scala.concurrent.duration.Duration.Zero, boilerplate.effect.IOError.Closed)"
      )
    )
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.a.flatTap(_ => boilerplate.effect.Twinned.b)"))
    assert(
      scala.compiletime.testing.typeChecks(
        "boilerplate.effect.Twinned.a.bracketCase(_ => boilerplate.effect.Twinned.b)((_, _) => cats.effect.IO.unit)"
      )
    )
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Twinned.a.guaranteeCase(_ => cats.effect.IO.unit)"))

  test("the type-preserving twins yield the Eff surface, not a conversion's projection"):
    // For these the conversion and the extension agree on the result type, so no row can tell them
    // apart. They are twinned for uniformity and so that a later signature change cannot regress
    // silently; what is asserted here is only that each call yields the `Eff` surface.
    val _ = summon[Twinned.void.type <:< Eff[AppError, Unit]]
    val _ = summon[Twinned.as.type <:< Eff[AppError, String]]
    val _ = summon[Twinned.map.type <:< Eff[AppError, Int]]
    val _ = summon[Twinned.delayBy.type <:< Eff[AppError, Int]]
    val _ = summon[Twinned.andWait.type <:< Eff[AppError, Int]]
    val _ = summon[Twinned.evalOn.type <:< Eff[AppError, Int]]
    val _ = summon[Twinned.guaranteeCase.type <:< Eff[AppError, Int]]
    val _ = summon[Twinned.timed.type <:< Eff[AppError, (FiniteDuration, Int)]]
    val _ = summon[Twinned.start.type <:< Eff[AppError, Fiber[Eff.Of[AppError], Throwable, Int]]]
    val _ = summon[Twinned.resourceMap.type <:< EffResource[AppError, Int]]
    assert(true)

  // The guard: which names still need a twin is derived from the classpath, not from a list here.

  test("every combinator cats or cats-effect syntax also names has a lexical twin"):
    val (_, collisions, twins, untwinned) = CollisionGuard.enumerate
    // `fold`, `valueOr` and `catchOnly` are declared on cats Ops classes, but no conversion that
    // provides them accepts an `Eff` receiver - measured, and held by the row below. Every other
    // collision must have a twin, or an imported conversion captures the call.
    val inapplicable = List("catchOnly", "fold", "valueOr")
    assertEquals(
      untwinned,
      inapplicable,
      s"untwinned collisions: ${untwinned.mkString(", ")}; collisions: ${collisions.mkString(", ")}; twins: ${twins.mkString(", ")}"
    )

  test("the names cats declares but does not provide for Eff still resolve to ours"):
    // If a cats release ever did provide one of these for an `Eff` receiver, the result type would
    // change or the call would stop compiling, and this row - not the exception list - would say so.
    val _ = summon[Twinned.fold.type <:< UEff[Int]]
    val _ = summon[Twinned.valueOr.type <:< UEff[Int]]
    val _ = summon[Twinned.catchOnly.type <:< UEff[Int]]
    assert(true)

  test("the collision set is derived from the classpath, not assumed"):
    val (targets, collisions, twins, _) = CollisionGuard.enumerate
    assert(targets.sizeIs >= 100, s"the conversion-target enumeration looks vacuous: ${targets.size} names")
    List("*>", "guarantee", "timeout", "background").foreach(name =>
      assert(targets.contains(name), s"$name is missing from the conversion-target enumeration")
    )
    // Every name twinned in this package is one the enumeration independently reports as a
    // collision; a cats release that dropped one would surface here as a twin that is no longer
    // needed. `eff`, `joinNever` and `joinOrFail` are ours alone and collide with nothing.
    val ownNames = List("eff", "joinNever", "joinOrFail")
    assertEquals(
      twins.filterNot(ownNames.contains).filterNot(collisions.contains),
      Nil,
      s"twins with no corresponding collision: ${twins.filterNot(ownNames.contains).filterNot(collisions.contains).mkString(", ")}"
    )

end OverloadDisambiguationSuite
