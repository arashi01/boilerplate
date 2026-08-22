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

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Try

import cats.data.EitherT
import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.syntax.parallel.*
import munit.CatsEffectSuite

import boilerplate.ErrorTest
import boilerplate.effect.AppError.*
import boilerplate.effect.IOError.*

// An enum root, for the one arm shape whose singleton the compiler widens away.
enum Refused extends Exception derives CanEqual:
  case Malformed
  case Expired(at: Long)

// Stable paths, so a compile-time row can name them from inside a `typeChecks` snippet.
object Channel:
  val io: Eff[IOError, Int] = Eff.succeed(1)
  val app: Eff[AppError, String] = Eff.succeed("x")
  val ue: UEff[Int] = Eff.succeed(1)

  val sequenced = io.flatMap(_ => app)
  val discarded = io *> app
  val paired = io.product(app)
  val concurrent = io.both(app)
  val raced = io.race(app)
  val tapped = io.flatTap(_ => app)
  val guarded = io.guarantee(Eff.fail(Timeout))
  val cancelled = io.onCancel(Eff.fail(Timeout))
  val timedOut = io.timeout(1.second, Timeout)
  val fellBack = io.timeoutTo(1.second, app.as(0))
  val subflat = io.subflatMap(n => if n > 0 then Right(n) else Left(Timeout))
  val someCaught = (io: Eff[IOError | AppError, Int]).catchSome { case Failed(_) => app.as(0) }
  val partlyMapped = io.mapErrorPartial { case Closed => Timeout }
  val bracketed = io.bracket(_ => app)(_ => IO.unit)
  val tappedError = io.flatTapError(_ => app.void)
  val attemptTapped = io.attemptTap(_ => app.void)
  val collectedSome = (Eff.succeed(Some(1)): Eff[IOError, Option[Int]]).collectSome(Timeout)
  val collectedRight = (Eff.succeed(Right(1)): Eff[IOError, Either[String, Int]]).collectRight(_ => Timeout)

  // A raw `IO` argument names no error type, so it contributes `Nothing` and the channel stays exact.
  val rawArgument = io.flatMap(_ => IO.pure("x"))
  val infallibleReceiver = ue.flatMap(_ => app)

  // The two limits: a branch-derived continuation widens to the join, and an enum's simple case
  // widens to the enum type. Neither is introduced by the union result - both are Scala's own
  // widening of an inferred union - and both are documented on `Eff`.
  val branched = ue.flatMap(n => if n > 0 then Eff.fail(Closed) else Eff.fail(Timeout))
  val ascribed = ue.flatMap(n => if n > 0 then Eff.fail(Closed): Eff[Closed.type | Timeout.type, Nothing] else Eff.fail(Timeout))
  val enumArm = Eff.fail(Refused.Malformed)
end Channel

class EffSuite extends CatsEffectSuite:
  private def run[E <: Throwable, A](eff: Eff[E, A])(using ErrorTest[E]): IO[Either[E, A]] = eff.either.absolve

  // Constructors

  test("succeed lands in the success channel and fail in IO's Throwable channel"):
    for
      s <- run(Eff.succeed(1))
      f <- run(Eff.fail[IOError](Closed))
    yield
      assertEquals(s, Right(1))
      assertEquals(f, Left(Closed))

  test("from lifts Either, Option, Try, and EitherT into the effect"):
    for
      e <- run(Eff.from[AppError, Int](Right(1): Either[AppError, Int]))
      oS <- run(Eff.from[AppError, Int](Some(2), Timeout))
      oN <- run(Eff.from[AppError, Int](None: Option[Int], Timeout))
      tS <- run(Eff.from[AppError, Int](Try(3), t => Invalid(t.getMessage)))
      tF <- run(Eff.from[AppError, Int](Failure(RuntimeException("t")), t => Invalid(t.getMessage)))
      et <- run(Eff.from(EitherT.fromEither[IO](Left(Timeout): Either[AppError, Int])))
    yield
      assertEquals(e, Right(1))
      assertEquals(oS, Right(2))
      assertEquals(oN, Left(Timeout))
      assertEquals(tS, Right(3))
      assertEquals(tF, Left(Invalid("t")))
      assertEquals(et, Left(Timeout))

  test("lift absorbs an IO[Either] Left and supplies ifNone for an empty IO[Option]"):
    for
      l <- run(Eff.lift(IO.pure(Left(Closed): Either[IOError, Int])))
      lo <- run(Eff.lift(IO.pure(None: Option[Int]), Closed))
      ls <- run(Eff.lift(IO.pure(Some(5): Option[Int]), Closed))
    yield
      assertEquals(l, Left(Closed))
      assertEquals(lo, Left(Closed))
      assertEquals(ls, Right(5))

  test("attempt(io, ifFailure) translates any raised throwable to a typed error"):
    for
      ok <- run(Eff.attempt(IO.pure(1), t => Invalid(t.getMessage)))
      ko <- run(Eff.attempt(IO.raiseError[Int](RuntimeException("x")), t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Invalid("x")))

  test("attempt(pf) catches matching throwables and lets unmatched propagate as a defect"):
    val matched = Eff.attempt(IO.raiseError[Int](IllegalArgumentException("bad"))):
      case _: IllegalArgumentException => Invalid("bad")
    val unmatched = Eff.attempt(IO.raiseError[Int](RuntimeException("untouched"))):
      case _: IllegalArgumentException => Invalid("bad")
    for
      m <- run(matched)
      u <- unmatched.absolve.attempt
    yield
      assertEquals(m, Left(Invalid("bad")))
      assert(u.left.exists(_.getMessage == "untouched"))

  test("suspend defers a side effect until run"):
    var count = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.suspend { count += 1; 42 }
    assertEquals(count, 0)
    for value <- eff.absolve
    yield
      assertEquals(value, 42)
      assertEquals(count, 1)

  test("delay suspends an Either-producing side effect until run, capturing Left and Right"):
    var executed = false // scalafix:ok DisableSyntax.var
    val ok = Eff.delay[IOError, Int] { executed = true; Right(42) }
    val ko = Eff.delay[IOError, Int](Left(Closed))
    assert(!executed)
    for
      r <- run(ok)
      l <- run(ko)
    yield
      assert(executed)
      assertEquals(r, Right(42))
      assertEquals(l, Left(Closed))

  test("blocking and suspendBlocking run on the blocking pool"):
    for
      r <- run(Eff.blocking[IOError, Int](Right(7)))
      l <- run(Eff.blocking[IOError, Int](Left(Closed)))
      s <- run(Eff.suspendBlocking(6 * 7))
    yield
      assertEquals(r, Right(7))
      assertEquals(l, Left(Closed))
      assertEquals(s, Right(42))

  test("defer delays evaluation until run"):
    var evaluated = false // scalafix:ok DisableSyntax.var
    val eff = Eff.defer[IOError, Int] { evaluated = true; Eff.succeed(42) }
    assert(!evaluated)
    run(eff).map { r =>
      assert(evaluated)
      assertEquals(r, Right(42))
    }

  test("when/unless run the effect only on the intended condition"):
    for
      exec <- IO.ref(0)
      _ <- run(Eff.when(true)(exec.update(_ + 1)))
      _ <- run(Eff.when(false)(exec.update(_ + 1)))
      _ <- run(Eff.unless(false)(exec.update(_ + 1)))
      _ <- run(Eff.unless(true)(exec.update(_ + 1)))
      count <- exec.get
    yield assertEquals(count, 2) // when(true) + unless(false)

  test("raiseWhen/raiseUnless raise only on the intended condition"):
    for
      rw <- run(Eff.raiseWhen[IOError](true)(Closed))
      rwN <- run(Eff.raiseWhen[IOError](false)(Closed))
      ru <- run(Eff.raiseUnless[IOError](false)(Closed))
      ruN <- run(Eff.raiseUnless[IOError](true)(Closed))
    yield
      assertEquals(rw, Left(Closed))
      assertEquals(rwN, Right(()))
      assertEquals(ru, Left(Closed))
      assertEquals(ruN, Right(()))

  test("cond lifts a predicate, evaluating only the selected branch"):
    var trueSide = 0 // scalafix:ok DisableSyntax.var
    var falseSide = 0 // scalafix:ok DisableSyntax.var
    val ok = Eff.cond[AppError, Int](true, { trueSide += 1; 42 }, { falseSide += 1; Timeout })
    val ko = Eff.cond[AppError, Int](false, { trueSide += 1; 42 }, { falseSide += 1; Timeout })
    for
      okR <- run(ok)
      koR <- run(ko)
    yield
      assertEquals(okR, Right(42))
      assertEquals(koR, Left(Timeout))
      assertEquals(trueSide, 1)
      assertEquals(falseSide, 1)

  // Covariance in E and A, and union inference

  test("a narrow error widens to a broad error with no call-site method"):
    val narrow: Eff[NotFound, Int] = Eff.fail(NotFound("u1"))
    val wide: Eff[AppError, Int] = narrow
    run(wide).map(r => assertEquals(r, Left(NotFound("u1"))))

  test("a narrow success widens to a broad success with no call-site method"):
    val narrow: Eff[AppError, NotFound] = Eff.succeed(NotFound("u1"))
    val wide: Eff[AppError, AppError] = narrow
    run(wide).map(r => assertEquals(r, Right(NotFound("u1"))))

  test("a for-comprehension over distinct Throwable error types infers their union"):
    def find(id: String): Eff[NotFound, Int] =
      if id == "1" then Eff.succeed(1) else Eff.fail(NotFound(id))
    def validate(n: Int): Eff[Invalid, Int] =
      if n > 0 then Eff.succeed(n) else Eff.fail(Invalid("non-positive"))
    val workflow: Eff[NotFound | Invalid, Int] =
      for
        n <- find("1")
        v <- validate(n)
      yield v
    val failing: Eff[NotFound | Invalid, Int] =
      for
        n <- find("2")
        v <- validate(n)
      yield v
    for
      ok <- run(workflow)
      ko <- run(failing)
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(NotFound("2")))

  test("an infallible and a fallible effect compose in one for-comprehension"):
    val workflow: Eff[NotFound, Int] =
      for
        base <- Eff.succeed(100) // UEff[Int]
        found <- Eff.succeed(1): Eff[NotFound, Int]
      yield base + found
    run(workflow).map(r => assertEquals(r, Right(101)))

  // Mapping

  test("map and flatMap act on success and short-circuit on a typed failure"):
    val base: Eff[IOError, Int] = Eff.succeed(10)
    for
      m <- run(base.map(_ + 1))
      fm <- run(base.flatMap(n => Eff.succeed(n * 2)))
      skip <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).map(_ => 0))
    yield
      assertEquals(m, Right(11))
      assertEquals(fm, Right(20))
      assertEquals(skip, Left(Closed))

  test("flatMap and subflatMap keep the receiver's typed error, joined with the continuation's"):
    // Both sequence, so the receiver's `E` must survive into the result. Without it in the union the
    // error silently vanishes from the type and escapes as a defect.
    val viaFlatMap: Eff[IOError, Int] = Eff.fail(Closed).flatMap(_ => Eff.succeed(1))
    val viaSubflatMap: Eff[IOError, Int] = Eff.fail(Closed).subflatMap(_ => Right(1))

    // Narrowing the receiver's `IOError` away to an infallible channel must NOT typecheck.
    val flatMapDrop = scala.compiletime.testing.typeCheckErrors(
      "val bad: boilerplate.effect.UEff[Int] = boilerplate.effect.Eff.fail(boilerplate.effect.IOError.Closed).flatMap(_ => boilerplate.effect.Eff.succeed(1))"
    )
    val subflatMapDrop = scala.compiletime.testing.typeCheckErrors(
      "val bad: boilerplate.effect.UEff[Int] = boilerplate.effect.Eff.fail(boilerplate.effect.IOError.Closed).subflatMap(_ => Right(1))"
    )
    assert(
      flatMapDrop.map(_.message).mkString.contains("boilerplate.effect.UEff[Int]"),
      s"flatMap must be rejected for narrowing to UEff, got: ${flatMapDrop.map(_.message).mkString}"
    )
    assert(
      subflatMapDrop.map(_.message).mkString.contains("boilerplate.effect.UEff[Int]"),
      s"subflatMap must be rejected for narrowing to UEff, got: ${subflatMapDrop.map(_.message).mkString}"
    )

    for
      a <- viaFlatMap.either
      b <- viaSubflatMap.either
    yield
      assertEquals(a, Left(Closed))
      assertEquals(b, Left(Closed))

  test("an IO continuation keeps the channel exact rather than widening it"):
    // `semiflatMap`/`tap` retired into `flatMap`/`flatTap` because `IO[A] <: Eff[Nothing, A]`, so an
    // `IO` lambda contributes `Nothing` to the union and the receiver's channel survives unchanged.
    val base: Eff[AppError, Int] = Eff.succeed(2)
    val mapped = base.flatMap(n => IO.pure(n * 10))
    val tapped = base.flatTap(n => IO.pure(n))
    val _ = summon[mapped.type <:< Eff[AppError, Int]]
    val _ = summon[tapped.type <:< Eff[AppError, Int]]
    for
      m <- run(mapped)
      t <- run(tapped)
      skip <- run((Eff.fail[AppError](Timeout): Eff[AppError, Int]).flatMap(_ => IO.pure(0)))
    yield
      assertEquals(m, Right(20))
      assertEquals(t, Right(2))
      assertEquals(skip, Left(Timeout))

  test("subflatMap and transform reshape through a pure Either"):
    for
      sub <- run((Eff.succeed(6): Eff[AppError, Int]).subflatMap(n => if n > 5 then Right(n * 2) else Left(Invalid("small"))))
      subL <- run((Eff.succeed(3): Eff[AppError, Int]).subflatMap(n => if n > 5 then Right(n * 2) else Left(Invalid("small"))))
      tr <- run((Eff.succeed(21): Eff[AppError, Int]).transform(_.map(_ * 2)))
      trErr <- run((Eff.fail[AppError](Timeout): Eff[AppError, Int]).transform(_ => Right(0): Either[AppError, Int]))
    yield
      assertEquals(sub, Right(12))
      assertEquals(subL, Left(Invalid("small")))
      assertEquals(tr, Right(42))
      assertEquals(trErr, Right(0))

  // Recovery

  test("catchAll recovers a typed error, allows an error-type change, and never swallows a defect"):
    val boom: Eff[IOError, Int] = IO.raiseError(RuntimeException("boom"))
    for
      recovered <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).catchAll(e => Eff.succeed(e.getMessage.length)))
      changed <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).catchAll(_ => Eff.fail[AppError](Timeout)))
      defect <- boom.catchAll(_ => Eff.succeed(0)).absolve.attempt
    yield
      assertEquals(recovered, Right(6))
      assertEquals(changed, Left(Timeout))
      assert(defect.left.exists(_.getMessage == "boom"))

  test("catchSome recovers matched errors and passes unmatched through"):
    val f: Eff[AppError, Int] => Eff[AppError, Int] =
      _.catchSome { case _: NotFound => Eff.succeed(1) }
    for
      m <- run(f(Eff.fail(NotFound("x"))))
      u <- run(f(Eff.fail(Invalid("y"))))
    yield
      assertEquals(m, Right(1))
      assertEquals(u, Left(Invalid("y")))

  test("catchOnly handles one union arm and narrows the residual at compile time"):
    // The `Eff[IOError, Int]` ascriptions are load-bearing: they assert the residual is narrowed.
    val onApp: Eff[IOError | AppError, Int] = Eff.fail(NotFound("u1"))
    val onIo: Eff[IOError | AppError, Int] = Eff.fail(Failed(500))
    val recovered: Eff[IOError, Int] = onApp.catchOnly((_: AppError) => Eff.succeed(-1))
    val residual: Eff[IOError, Int] = onIo.catchOnly((_: AppError) => Eff.succeed(-1))
    for
      r <- run(recovered)
      s <- run(residual)
    yield
      assertEquals(r, Right(-1))
      assertEquals(s, Left(Failed(500)))

  test("catchOnly lets the handler re-fail into the residual channel"):
    val onApp: Eff[IOError | AppError, Int] = Eff.fail(Invalid("bad"))
    // Re-failing into the residual: ascribe to the residual root so it is not inferred too narrowly.
    val narrowed: Eff[IOError, Int] = onApp.catchOnly((_: AppError) => Eff.fail[IOError](Closed))
    run(narrowed).map(r => assertEquals(r, Left(Closed)))

  test("catchOnly with an infallible handler infers the narrow residual unascribed"):
    val onApp: Eff[IOError | AppError, Int] = Eff.fail(NotFound("u2"))
    // No ascription: the infallible-handler twin subtracts the handled arm. The witness is the
    // compile-time lock - it fails if the residual ever widens again.
    val handled = onApp.catchOnly((_: AppError) => Eff.succeed(-1))
    val _ = summon[handled.type <:< Eff[IOError, Int]]
    run(handled).map(r => assertEquals(r, Right(-1)))

  test("catchOnly whose handler covers the whole channel infers an infallible residual"):
    val root: Eff[AppError, Int] = Eff.fail(Invalid("all"))
    val closed = root.catchOnly((_: AppError) => Eff.succeed(0))
    val _ = summon[closed.type <:< UEff[Int]]
    run(closed).map(r => assertEquals(r, Right(0)))

  test("catchOnly on a root-typed channel stays bounded by the root"):
    val root: Eff[AppError, Int] = Eff.fail(NotFound("x"))
    val bounded = root.catchOnly((_: NotFound) => Eff.succeed(1))
    val _ = summon[bounded.type <:< Eff[AppError, Int]]
    run(bounded).map(r => assertEquals(r, Right(1)))

  test("a defect downstream of an infallible-handler catchOnly propagates through catchAll"):
    // The narrow residual is what keeps later observers honest: on a Throwable-widened channel the
    // identity TypeTest would reify this defect as a typed Left and catchAll would swallow it.
    val rogue: Eff[IOError | AppError, Int] = IO.raiseError(RuntimeException("DEFECT"))
    val recovered = rogue.catchOnly((_: AppError) => Eff.succeed(-1))
    recovered.catchAll(_ => Eff.succeed(0)).absolve.attempt.map(r => assert(r.left.exists(_.getMessage == "DEFECT")))

  test("mapError transforms the typed channel and leaves a defect untouched"):
    val boom: Eff[IOError, Int] = IO.raiseError(RuntimeException("boom"))
    for
      mapped <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).mapError(e => Invalid(e.getMessage)))
      defect <- boom.mapError(e => Invalid(e.getMessage)).absolve.attempt
    yield
      assertEquals(mapped, Left(Invalid("closed")))
      assert(defect.left.exists(_.getMessage == "boom"))

  test("mapErrorPartial transforms matched errors and passes others through"):
    val f: Eff[AppError, Int] => Eff[AppError, Int] =
      _.mapErrorPartial { case _: NotFound => Timeout }
    for
      m <- run(f(Eff.fail(NotFound("x"))))
      u <- run(f(Eff.fail(Invalid("y"))))
    yield
      assertEquals(m, Left(Timeout))
      assertEquals(u, Left(Invalid("y")))

  test("redeemAll handles both channels and can change the error type"):
    val boom: Eff[IOError, Int] = Eff.fail(Closed)
    for
      fromErr <- run(boom.redeemAll(_ => Eff.succeed(-1), a => Eff.succeed(a)))
      fromOk <- run((Eff.succeed(5): Eff[IOError, Int]).redeemAll(_ => Eff.succeed(-1), a => Eff.succeed(a)))
      changed <- run(boom.redeemAll(e => Eff.fail[AppError](Invalid(e.getMessage)), a => Eff.succeed(a)))
    yield
      assertEquals(fromErr, Right(-1))
      assertEquals(fromOk, Right(5))
      assertEquals(changed, Left(Invalid("closed")))

  test("fold and foldF collapse both channels to the base IO"):
    val boom: Eff[IOError, Int] = Eff.fail(Closed)
    for
      e <- boom.fold(_.getMessage.length, _ => 0)
      a <- (Eff.succeed(7): Eff[IOError, Int]).fold(_ => -1, identity)
      ef <- boom.foldF(err => IO.pure(err.getMessage.length), v => IO.pure(v))
    yield
      assertEquals(e, 6)
      assertEquals(a, 7)
      assertEquals(ef, 6)

  test("orElseSucceed, orElseFail, valueOr, and alt"):
    val boom: Eff[IOError, Int] = Eff.fail(Closed)
    for
      os <- run(boom.orElseSucceed(0))
      of <- run(boom.orElseFail(Timeout))
      vo <- run(boom.valueOr(_.getMessage.length))
      al <- run(boom.alt(Eff.succeed(1)))
      alChange <- run(boom.alt(Eff.fail[AppError](Timeout)))
    yield
      assertEquals(os, Right(0))
      assertEquals(of, Left(Timeout))
      assertEquals(vo, Right(6))
      assertEquals(al, Right(1))
      assertEquals(alChange, Left(Timeout))
    end for

  test("tapError and flatTapError observe typed failures; a failing flatTapError replaces the error"):
    val boom: Eff[IOError, Int] = Eff.fail(Closed)
    for
      tapObs <- IO.ref(Option.empty[String])
      tapR <- run(boom.tapError(e => tapObs.set(Some(e.getMessage))))
      tapSeen <- tapObs.get
      ftObs <- IO.ref(Option.empty[String])
      ftR <- run(boom.flatTapError(e => ftObs.set(Some(e.getMessage))))
      ftSeen <- ftObs.get
      replaced <- run(boom.flatTapError(_ => Eff.fail[IOError](Failed(1))))
    yield
      assertEquals(tapR, Left(Closed))
      assertEquals(tapSeen, Some("closed"))
      assertEquals(ftR, Left(Closed))
      assertEquals(ftSeen, Some("closed"))
      assertEquals(replaced, Left(Failed(1)))
    end for

  test("attemptTap observes the reified result and propagates a failing side effect"):
    for
      seenErr <- IO.ref(Option.empty[Either[IOError, Int]])
      errR <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).attemptTap(ea => seenErr.set(Some(ea))))
      errObs <- seenErr.get
      seenOk <- IO.ref(Option.empty[Either[IOError, Int]])
      okR <- run((Eff.succeed(42): Eff[IOError, Int]).attemptTap(ea => seenOk.set(Some(ea))))
      okObs <- seenOk.get
      prop <- run((Eff.succeed(42): Eff[IOError, Int]).attemptTap(_ => Eff.fail[IOError](Failed(9))))
    yield
      assertEquals(errR, Left(Closed))
      assertEquals(errObs, Some(Left(Closed)))
      assertEquals(okR, Right(42))
      assertEquals(okObs, Some(Right(42)))
      assertEquals(prop, Left(Failed(9)))

  test("option, collectSome, and collectRight"):
    for
      optS <- run((Eff.succeed(42): Eff[IOError, Int]).option)
      optE <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).option)
      cs <- run((Eff.succeed(Some(5)): Eff[AppError, Option[Int]]).collectSome(Timeout))
      csN <- run((Eff.succeed(None): Eff[AppError, Option[Int]]).collectSome(Timeout))
      cr <- run((Eff.succeed(Right(9)): Eff[AppError, Either[Int, Int]]).collectRight(n => Invalid(n.toString)))
      crL <- run((Eff.succeed(Left(404)): Eff[AppError, Either[Int, Int]]).collectRight(n => Invalid(n.toString)))
    yield
      assertEquals(optS, Right(Some(42)))
      assertEquals(optE, Right(None))
      assertEquals(cs, Right(5))
      assertEquals(csN, Left(Timeout))
      assertEquals(cr, Right(9))
      assertEquals(crL, Left(Invalid("404")))

  test("either reifies the typed channel and eitherT wraps it as EitherT"):
    for
      e <- (Eff.succeed(42): Eff[IOError, Int]).either
      et <- (Eff.fail[IOError](Closed): Eff[IOError, Int]).eitherT.value
    yield
      assertEquals(e, Right(42))
      assertEquals(et, Left(Closed))

  test("absolve raises the typed error into IO's channel; success passes through"):
    for
      ok <- (Eff.succeed(1): Eff[IOError, Int]).absolve
      ko <- (Eff.fail[IOError](Closed): Eff[IOError, Int]).absolve.attempt
    yield
      assertEquals(ok, 1)
      assertEquals(ko.left.toOption, Some(Closed))

  test("a typed error reifies to Left; a defect stays on the IO channel"):
    val boom: Eff[IOError, Int] = IO.raiseError(RuntimeException("defect"))
    for
      typed <- run(Eff.fail[IOError](Closed)).attempt
      defect <- run(boom).attempt
    yield
      assert(typed.isRight) // IO succeeds carrying Left
      assertEquals(typed.toOption.get, Left(Closed))
      assert(defect.isLeft) // IO fails
      assert(defect.left.exists(_.getMessage == "defect"))

  // Concurrency, cancellation, resources

  test("bracket releases on a typed use failure and skips release when acquire fails"):
    for
      relUse <- IO.ref(false)
      useR <- run((Eff.succeed(42): Eff[IOError, Int]).bracket(_ => Eff.fail[IOError](Closed))(_ => relUse.set(true)))
      usedReleased <- relUse.get
      relAcq <- IO.ref(false)
      acqR <- run((Eff.fail[IOError](Failed(1)): Eff[IOError, Int]).bracket(a => Eff.succeed(a))(_ => relAcq.set(true)))
      acqReleased <- relAcq.get
    yield
      assertEquals(useR, Left(Closed))
      assert(usedReleased)
      assertEquals(acqR, Left(Failed(1)))
      assert(!acqReleased)

  test("bracketCase surfaces Succeeded for a value and Errored for a typed use failure"):
    for
      okOc <- IO.ref("")
      r <- run((Eff.succeed(42): Eff[IOError, Int]).bracketCase(a => Eff.succeed(a)) { (_, oc) =>
             oc match
               case Outcome.Succeeded(_) => okOc.set("succeeded")
               case Outcome.Errored(_)   => okOc.set("errored")
               case Outcome.Canceled()   => okOc.set("canceled")
           })
      okSeen <- okOc.get
      errOc <- IO.ref("")
      e <- run((Eff.succeed(42): Eff[IOError, Int]).bracketCase(_ => Eff.fail[IOError](Closed)) { (_, oc) =>
             oc match
               case Outcome.Succeeded(_) => errOc.set("succeeded")
               case Outcome.Errored(_)   => errOc.set("errored")
               case Outcome.Canceled()   => errOc.set("canceled")
           })
      errSeen <- errOc.get
    yield
      assertEquals(r, Right(42))
      assertEquals(okSeen, "succeeded")
      assertEquals(e, Left(Closed))
      assertEquals(errSeen, "errored")

  test("race returns the winner, both returns the pair, and both fails fast on a typed error"):
    val slowGood: Eff[IOError, Int] = IO.sleep(1.second).flatMap(_ => IO.pure(1))
    for
      raced <- run((Eff.succeed(1): Eff[IOError, Int]).race(Eff.never: Eff[IOError, Int]))
      paired <- run((Eff.succeed(1): Eff[IOError, Int]).both(Eff.succeed(2)))
      failFast <- run(slowGood.both(Eff.fail[IOError](Closed)))
    yield
      assertEquals(raced, Right(Left(1)))
      assertEquals(paired, Right((1, 2)))
      assertEquals(failFast, Left(Closed))

  test("start: a successful join is Succeeded, a typed-failure join is Errored"):
    def label(eff: Eff[IOError, Int]): Eff[IOError, String] =
      for
        fiber <- eff.start
        outcome <- fiber.join
      yield outcome match
        case Outcome.Succeeded(_)              => "succeeded"
        case Outcome.Errored(e) if e eq Closed => "errored(Closed)"
        case Outcome.Errored(_)                => "errored(other)"
        case Outcome.Canceled()                => "canceled"
    for
      ok <- run(label(Eff.succeed(42)))
      ko <- run(label(Eff.fail(Closed)))
    yield
      assertEquals(ok, Right("succeeded"))
      assertEquals(ko, Right("errored(Closed)"))

  test("background spawns a supervised fibre that completes Succeeded"):
    (Eff.succeed(42): Eff[IOError, Int]).background
      .use(join => IO.sleep(10.millis).flatMap(_ => join))
      .map {
        case Outcome.Succeeded(_) => ()
        case other                => fail(s"expected Succeeded, got $other")
      }

  test("timeout fails with the supplied typed error when too slow and passes a fast value"):
    val slow: Eff[AppError, Int] = IO.sleep(1.second).flatMap(_ => IO.pure(1))
    for
      fast <- run((Eff.succeed(42): Eff[AppError, Int]).timeout(1.second, Timeout))
      slowR <- run(slow.timeout(50.millis, Timeout))
    yield
      assertEquals(fast, Right(42))
      assertEquals(slowR, Left(Timeout))

  test("timeoutTo returns the fallback on timeout and the value within duration"):
    val slow: Eff[IOError, Int] = IO.sleep(1.second).flatMap(_ => IO.pure(1))
    for
      fb <- run(slow.timeoutTo(50.millis, Eff.succeed(42)))
      within <- run((Eff.succeed(42): Eff[IOError, Int]).timeoutTo(1.second, Eff.succeed(0)))
    yield
      assertEquals(fb, Right(42))
      assertEquals(within, Right(42))

  test("delayBy delays execution and andWait waits after it"):
    for
      start <- IO.monotonic
      r1 <- run((Eff.succeed(42): Eff[IOError, Int]).delayBy(10.millis))
      mid <- IO.monotonic
      r2 <- run((Eff.succeed(42): Eff[IOError, Int]).andWait(10.millis))
      end <- IO.monotonic
    yield
      assertEquals(r1, Right(42))
      assertEquals(r2, Right(42))
      assert(clue(mid - start) >= 9.millis) // 1ms tolerance for JS timer imprecision
      assert(clue(end - mid) >= 9.millis)

  test("timed returns the result paired with a non-negative duration"):
    run((Eff.succeed(42): Eff[IOError, Int]).timed).map {
      case Right((dur, value)) =>
        assertEquals(value, 42)
        assert(dur >= 0.nanos)
      case Left(e) => fail(s"unexpected error: $e")
    }

  test("&> and <& run in parallel, discarding the appropriate side, and short-circuit on error"):
    val a: Eff[IOError, Int] = Eff.succeed(1)
    val b: Eff[IOError, String] = Eff.succeed("two")
    for
      r <- run(a &> b)
      l <- run(a <& b)
      shortR <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]) &> b)
    yield
      assertEquals(r, Right("two"))
      assertEquals(l, Right(1))
      assertEquals(shortR, Left(Closed))

  test("onCancel runs its finaliser only on cancellation; guarantee runs on success and error"):
    for
      onCancelRan <- IO.ref(false)
      canceledOc <- Eff.canceled.onCancel(onCancelRan.set(true)).absolve.start.flatMap(_.join)
      onCancelSeen <- onCancelRan.get
      onSuccessRan <- IO.ref(false)
      okR <- run((Eff.succeed(42): Eff[IOError, Int]).onCancel(onSuccessRan.set(true)))
      onSuccessSeen <- onSuccessRan.get
      guaranteeRan <- IO.ref(0)
      gOk <- run((Eff.succeed(42): Eff[IOError, Int]).guarantee(guaranteeRan.update(_ + 1)))
      gErr <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).guarantee(guaranteeRan.update(_ + 1)))
      guaranteeCount <- guaranteeRan.get
    yield
      assert(canceledOc.isCanceled)
      assert(onCancelSeen)
      assertEquals(okR, Right(42))
      assert(!onSuccessSeen)
      assertEquals(gOk, Right(42))
      assertEquals(gErr, Left(Closed))
      assertEquals(guaranteeCount, 2)

  test("guaranteeCase observes Succeeded for a value and Errored for a typed failure"):
    for
      onOk <- IO.ref("")
      onErr <- IO.ref("")
      _ <- (Eff.succeed(1): Eff[IOError, Int])
             .guaranteeCase {
               case Outcome.Succeeded(_) => onOk.set("succeeded")
               case Outcome.Errored(_)   => onOk.set("errored")
               case Outcome.Canceled()   => onOk.set("canceled")
             }
             .absolve
             .attempt
      _ <- (Eff.fail[IOError](Closed): Eff[IOError, Int])
             .guaranteeCase {
               case Outcome.Succeeded(_) => onErr.set("succeeded")
               case Outcome.Errored(_)   => onErr.set("errored")
               case Outcome.Canceled()   => onErr.set("canceled")
             }
             .absolve
             .attempt
      okSeen <- onOk.get
      errSeen <- onErr.get
    yield
      assertEquals(okSeen, "succeeded")
      assertEquals(errSeen, "errored")

  test("cede yields and never does not complete"):
    for
      ce <- run(Eff.cede)
      neverR <- Eff.never.absolve.timeout(50.millis).attempt
    yield
      assertEquals(ce, Right(()))
      assert(neverR.isLeft)

  test("sleep, monotonic, and realTime read the clock in the Eff context"):
    for
      start <- Eff.monotonic.absolve
      _ <- Eff.sleep(10.millis).absolve
      end <- Eff.monotonic.absolve
      wall <- Eff.realTime.absolve
    yield
      assert(clue(end - start) >= 9.millis) // 1ms tolerance for JS timer imprecision
      assert(wall.toMillis > 0)

  // Traversal

  test("traverse short-circuits on the first error and collects successes"):
    for
      ok <- run(Eff.traverse[IOError, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2)))
      visited <- IO.ref(0)
      ko <- run(Eff.traverse[IOError, Int, Int](List(1, 2, 3)) { n =>
              (visited.update(_ + 1): Eff[IOError, Unit]).flatMap(_ => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n))
            })
      seen <- visited.get
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(seen, 2) // stops after visiting 1 and 2

  test("traverse preserves order for a large collection without stack overflow or quadratic blowup"):
    val n = 5000
    run(Eff.traverse[IOError, Int, Int]((1 to n).toList)(Eff.succeed(_)))
      .map(r => assertEquals(r, Right((1 to n).toList)))

  test("sequence collects; traverse_ and sequence_ run for effect and discard"):
    for
      seq <- run(Eff.sequence[IOError, Int](List(Eff.succeed(1), Eff.succeed(2), Eff.succeed(3))))
      sum <- IO.ref(0)
      tvU <- run(Eff.traverse_[IOError, Int, Unit](List(1, 2, 3))(n => sum.update(_ + n)))
      total <- sum.get
      seqU <- run(Eff.sequence_[IOError, Int](List(Eff.succeed(1), Eff.succeed(2))))
    yield
      assertEquals(seq, Right(List(1, 2, 3)))
      assertEquals(tvU, Right(()))
      assertEquals(total, 6)
      assertEquals(seqU, Right(()))

  test("parTraverse runs in parallel and short-circuits; parSequence collects"):
    for
      ok <- run(Eff.parTraverse[IOError, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2)))
      ko <- run(Eff.parTraverse[IOError, Int, Int](List(1, 2, 3))(n => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n)))
      ps <- run(Eff.parSequence[IOError, Int](List(Eff.succeed(1), Eff.succeed(2))))
      psErr <- run(Eff.parSequence[IOError, Int](List(Eff.succeed(1), Eff.fail(Closed), Eff.succeed(3))))
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(ps, Right(List(1, 2)))
      assertEquals(psErr, Left(Closed))

  test("parTraverse_ and parSequence_ run all in parallel, discard, and propagate a typed error"):
    for
      ok <- run(Eff.parTraverse_[IOError, Int, Int](List(1, 2, 3))(Eff.succeed(_)))
      ko <- run(Eff.parTraverse_[IOError, Int, Int](List(1, 2, 3))(n => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n)))
      ps <- run(Eff.parSequence_[IOError, Int](List(Eff.succeed(1), Eff.succeed(2))))
    yield
      assertEquals(ok, Right(()))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(ps, Right(()))

  // Retry

  private def failingEff(counter: Ref[IO, Int], e: IOError): Eff[IOError, Int] =
    val bump: Eff[IOError, Unit] = counter.update(_ + 1)
    bump.flatMap(_ => Eff.fail(e))

  test("policy retry paces the exponential series exactly and stops at maxAttempts"):
    TestControl.executeEmbed {
      for
        counter <- IO.ref(0)
        policy = RetryPolicy.exponential(100.millis).withMaxAttempts(4)
        start <- IO.monotonic
        out <- run(Eff.retry(failingEff(counter, Closed), policy))
        end <- IO.monotonic
        n <- counter.get
      yield
        assertEquals(out, Left(Closed))
        assertEquals(n, 4)
        assertEquals(end - start, (100 + 200 + 400).millis)
    }

  test("policy retry caps each delay at maxDelay"):
    TestControl.executeEmbed {
      for
        counter <- IO.ref(0)
        policy = RetryPolicy.exponential(100.millis).withMaxAttempts(4).withMaxDelay(150.millis)
        start <- IO.monotonic
        _ <- run(Eff.retry(failingEff(counter, Closed), policy))
        end <- IO.monotonic
      yield assertEquals(end - start, (100 + 150 + 150).millis)
    }

  test("policy retry stops rather than sleep beyond maxCumulativeDelay"):
    TestControl.executeEmbed {
      for
        counter <- IO.ref(0)
        policy = RetryPolicy.constant(100.millis).withMaxCumulativeDelay(250.millis)
        start <- IO.monotonic
        out <- run(Eff.retry(failingEff(counter, Closed), policy))
        end <- IO.monotonic
        n <- counter.get
      yield
        assertEquals(out, Left(Closed))
        assertEquals(n, 3)
        assertEquals(end - start, 200.millis)
    }

  test("policy retry saturates the cumulative-delay accumulator instead of throwing"):
    // A constant delay near FiniteDuration's ceiling would overflow `FiniteDuration.+` within two
    // retries; the accumulator must saturate so the typed error still propagates as typed.
    TestControl.executeEmbed {
      for
        counter <- IO.ref(0)
        policy = RetryPolicy.constant((Long.MaxValue / 2).nanos).withMaxAttempts(3)
        out <- run(Eff.retry(failingEff(counter, Closed), policy))
        n <- counter.get
      yield
        assertEquals(out, Left(Closed))
        assertEquals(n, 3)
    }

  test("policy retry recovers once the effect succeeds"):
    TestControl.executeEmbed {
      for
        attempts <- IO.ref(0)
        eff: Eff[IOError, Int] =
          (attempts.updateAndGet(_ + 1): Eff[IOError, Int]).flatMap(n => if n < 3 then Eff.fail(Failed(n)) else Eff.succeed(n))
        out <- run(Eff.retry(eff, RetryPolicy.constant(10.millis).withMaxAttempts(5)))
        n <- attempts.get
      yield
        assertEquals(out, Right(3))
        assertEquals(n, 3)
    }

  test("policy retry honours the retryOn predicate per error"):
    TestControl.executeEmbed {
      val policy = RetryPolicy.constant(10.millis).withMaxAttempts(3)
      val retriable = (e: IOError) =>
        e match
          case _: Failed => true
          case Closed    => false
      for
        fCount <- IO.ref(0)
        cCount <- IO.ref(0)
        rF <- run(Eff.retry(failingEff(fCount, Failed(1)), policy, retriable))
        rC <- run(Eff.retry(failingEff(cCount, Closed), policy, retriable))
        nF <- fCount.get
        nC <- cCount.get
      yield
        assertEquals(rF, Left(Failed(1)))
        assertEquals(rC, Left(Closed))
        assertEquals(nF, 3)
        assertEquals(nC, 1)
      end for
    }

  test("policy retry full jitter draws every delay within [0, series]"):
    TestControl.executeEmbed {
      val policy = RetryPolicy.fullJitter(100.millis).withMaxAttempts(5)
      for
        counter <- IO.ref(0)
        delays <- IO.ref(List.empty[(Int, FiniteDuration)])
        hook = (attempt: Int, _: IOError, d: FiniteDuration) => delays.update((attempt, d) :: _)
        _ <- run(Eff.retry(failingEff(counter, Closed), policy, hook))
        ds <- delays.get
      yield
        assertEquals(ds.size, 4)
        ds.foreach { case (attempt, d) =>
          assert(d >= Duration.Zero)
          assert(d.toNanos.toDouble <= 100.millis.toNanos.toDouble * math.pow(2.0, (attempt - 1).toDouble), clue((attempt, d)))
        }
      end for
    }

  test("policy retry decorrelated jitter draws every delay within [base, prev * factor]"):
    TestControl.executeEmbed {
      val policy = RetryPolicy.decorrelated(50.millis).withMaxAttempts(6).withMaxDelay(2.seconds)
      for
        counter <- IO.ref(0)
        delays <- IO.ref(List.empty[FiniteDuration])
        hook = (_: Int, _: IOError, d: FiniteDuration) => delays.update(d :: _)
        _ <- run(Eff.retry(failingEff(counter, Closed), policy, hook))
        ds <- delays.get.map(_.reverse)
      yield
        assertEquals(ds.size, 5)
        val _ = ds.foldLeft(50.millis) { (prev, d) =>
          val lo = 50.millis.toNanos.toDouble
          val hi = math.max(lo, prev.toNanos.toDouble * 3.0)
          assert(d.toNanos.toDouble >= math.min(lo, hi) && d.toNanos.toDouble <= hi, clue((prev, d)))
          d
        }
      end for
    }

  test("policy retry hook observes (attempt, error, delay) only when a retry will happen"):
    TestControl.executeEmbed {
      for
        counter <- IO.ref(0)
        seen <- IO.ref(List.empty[(Int, IOError, FiniteDuration)])
        policy = RetryPolicy.constant(10.millis).withMaxAttempts(3)
        hook = (n: Int, e: IOError, d: FiniteDuration) => seen.update((n, e, d) :: _)
        _ <- run(Eff.retry(failingEff(counter, Closed), policy, hook))
        entries <- seen.get.map(_.reverse)
      yield
        val expected: List[(Int, IOError, FiniteDuration)] = List((1, Closed, 10.millis), (2, Closed, 10.millis))
        assertEquals(entries, expected)
    }

  // async and Future

  test("async completes with a typed success or failure via the callback"):
    val ok = Eff.async[AppError, Int] { cb => cb(Right(7)); IO.pure(None) }
    val ko = Eff.async[AppError, Int] { cb => cb(Left(NotFound("x"))); IO.pure(None) }
    for
      o <- run(ok)
      k <- run(ko)
    yield
      assertEquals(o, Right(7))
      assertEquals(k, Left(NotFound("x")))

  test("asyncAttempt folds a raised defect but preserves a typed callback error"):
    val folded = Eff.asyncAttempt[AppError, Int](t => Invalid(t.getMessage))(_ => IO.raiseError(RuntimeException("boom")))
    val typed = Eff.asyncAttempt[AppError, Int](_ => Timeout) { cb => cb(Left(NotFound("y"))); IO.pure(None) }
    for
      f <- run(folded)
      t <- run(typed)
    yield
      assertEquals(f, Left(Invalid("boom")))
      assertEquals(t, Left(NotFound("y")))

  test("fromFuture converts a successful future and translates a failed one"):
    val ok = Eff.fromFuture(IO(Future.successful(42)), t => Invalid(t.getMessage))
    val ko = Eff.fromFuture(IO(Future.failed[Int](RuntimeException("boom"))), t => Invalid(t.getMessage))
    for
      o <- run(ok)
      k <- run(ko)
    yield
      assertEquals(o, Right(42))
      assertEquals(k, Left(Invalid("boom")))

  // Summoned instances

  test("the summoned MonadError instance handles the typed error channel"):
    val F = summon[cats.MonadError[Eff.Of[IOError], IOError]]
    run(F.handleError(F.raiseError[Int](Closed))(_.getMessage.length))
      .map(r => assertEquals(r, Right(6)))

  test("the summoned GenConcurrent instance runs a concurrent program"):
    val F = summon[cats.effect.kernel.GenConcurrent[Eff.Of[IOError], Throwable]]
    val program =
      for
        ref <- F.ref(0)
        _ <- ref.update(_ + 1)
        v <- ref.get
      yield v
    run(program).map(r => assertEquals(r, Right(1)))

  test("the summoned GenTemporal instance raises TimeoutException on the defect channel"):
    val T = summon[cats.effect.kernel.GenTemporal[Eff.Of[IOError], Throwable]]
    val slow = T.flatMap(T.sleep(1.second))(_ => T.pure(42))
    // A timeout is a defect (TimeoutException in IO's channel), not the typed IOError channel.
    T.timeout(slow, 10.millis).absolve.attempt.map {
      case Left(_: java.util.concurrent.TimeoutException) => ()
      case Right(_)                                       => fail("should have timed out")
      case Left(e)                                        => fail(s"wrong error type: ${e.getClass.getName}")
    }

  test("the Parallel instance enables parMapN and short-circuits on error"):
    for
      ok <- run(((Eff.succeed(1): Eff[IOError, Int]), (Eff.succeed(2): Eff[IOError, Int])).parMapN(_ + _))
      ko <- run(((Eff.succeed(1): Eff[IOError, Int]), (Eff.fail[IOError](Closed): Eff[IOError, Int])).parMapN(_ + _))
    yield
      assertEquals(ok, Right(3))
      assertEquals(ko, Left(Closed))

  // Channel precision

  test("every binary combinator infers the precise union of the two channels"):
    val _ = summon[Channel.sequenced.type <:< Eff[IOError | AppError, String]]
    val _ = summon[Channel.discarded.type <:< Eff[IOError | AppError, String]]
    val _ = summon[Channel.paired.type <:< Eff[IOError | AppError, (Int, String)]]
    val _ = summon[Channel.concurrent.type <:< Eff[IOError | AppError, (Int, String)]]
    val _ = summon[Channel.raced.type <:< Eff[IOError | AppError, Either[Int, String]]]
    val _ = summon[Channel.tapped.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.guarded.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.cancelled.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.timedOut.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.fellBack.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.subflat.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.someCaught.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.partlyMapped.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.bracketed.type <:< Eff[IOError | AppError, String]]
    val _ = summon[Channel.tappedError.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.attemptTapped.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.collectedSome.type <:< Eff[IOError | AppError, Int]]
    val _ = summon[Channel.collectedRight.type <:< Eff[IOError | AppError, Int]]
    run(Channel.paired).map(r => assertEquals(r, Right((1, "x"))))

  test("a union channel cannot be claimed narrower than it is"):
    assert(
      !scala.compiletime.testing.typeChecks(
        "val bad: boilerplate.effect.Eff[boilerplate.effect.IOError, String] = boilerplate.effect.Channel.sequenced"
      )
    )
    assert(
      !scala.compiletime.testing.typeChecks(
        "val bad: boilerplate.effect.Eff[boilerplate.effect.AppError, (Int, String)] = boilerplate.effect.Channel.paired"
      )
    )

  test("a raw IO argument contributes Nothing, and an infallible receiver contributes nothing either"):
    val _ = summon[Channel.rawArgument.type <:< Eff[IOError, String]]
    val _ = summon[Channel.infallibleReceiver.type <:< Eff[AppError, String]]
    assert(
      !scala.compiletime.testing.typeChecks(
        "val bad: boilerplate.effect.UEff[String] = boilerplate.effect.Channel.rawArgument"
      )
    )
    run(Channel.rawArgument).map(r => assertEquals(r, Right("x")))

  test("the reified union matches exhaustively, so the case set says what happened"):
    def describe(e: Either[IOError | AppError, String]): String = e match
      case Left(io: IOError) =>
        io match
          case Failed(code) => s"failed:$code"
          case Closed       => "closed"
      case Left(app: AppError) =>
        app match
          case NotFound(id) => s"missing:$id"
          case Invalid(r)   => s"invalid:$r"
          case Timeout      => "timeout"
      case Right(v) => v
    Channel.sequenced.either.map(e => assertEquals(describe(e), "x"))

  test("generic code observing an abstract channel without evidence is refused, naming the remedy"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "def f[E <: Throwable, A](e: boilerplate.effect.Eff[E, A]) = e.either"
    )
    assert(errors.exists(_.message.contains("using ErrorTest[E]")), errors.map(_.message).mkString("\n"))

  test("LIMIT: a branch-derived continuation infers the branches' join, not their union"):
    // Scala widens an inferred union to the least product of class and trait types above it, so a
    // continuation whose channel comes from an `if`/`match` loses precision. Documented on `Eff`;
    // the remedy is to ascribe the lambda's result or its branches, as `Channel.ascribed` does.
    assert(
      !scala.compiletime.testing.typeChecks(
        "val precise: boilerplate.effect.Eff[boilerplate.effect.IOError.Closed.type | boilerplate.effect.AppError.Timeout.type, Nothing] = boilerplate.effect.Channel.branched"
      )
    )
    val _ = summon[Channel.ascribed.type <:< Eff[Closed.type | Timeout.type, Nothing]]

  test("LIMIT: the join of unrelated roots is an intersection, and observing it is refused"):
    // The safety pay-off of the limit above: the widened channel is `Exception & NoStackTrace`,
    // whose test would capture unrelated failures as typed. `ErrorTest` refuses to derive for it.
    val errors = scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Channel.branched.either")
    assert(errors.exists(_.message.contains("name the precise union")), errors.map(_.message).mkString("\n"))
    // The ascribed form observes correctly.
    Channel.ascribed.either.map(r => assertEquals(r, Left(Closed)))

  test("LIMIT: an enum's simple case widens to the enum type"):
    // `case object` arms keep their singleton; an enum simple case is a `val` of the enum type, and
    // generic instantiation widens it. Documented on `Eff`; the remedy is an explicit type argument.
    val _ = summon[Channel.enumArm.type <:< Eff[Refused, Nothing]]
    assert(
      !scala.compiletime.testing.typeChecks(
        "val precise: boilerplate.effect.Eff[boilerplate.effect.Refused.Malformed.type, Nothing] = boilerplate.effect.Channel.enumArm"
      )
    )
    val pinned: Eff[Refused.Malformed.type, Nothing] = Eff.fail[Refused.Malformed.type](Refused.Malformed)
    // A `case object` arm is unaffected - the control the limit is measured against.
    val objectArm = Eff.fail(Closed)
    val _ = summon[objectArm.type <:< Eff[Closed.type, Nothing]]
    pinned.either.map(r => assertEquals(r, Left(Refused.Malformed)))

  // Removals: each superseded member is gone, not deprecated.

  test("the superseded members are absent from the surface"):
    def named(errors: List[scala.compiletime.testing.Error], member: String): Unit =
      assert(errors.exists(_.message.contains(member)), s"$member: ${errors.map(_.message).mkString("\n")}")

    named(scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Eff.succeed(1).assumeError[Nothing]"), "assumeError")
    named(scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Eff.succeed(1: Any).assume[Int]"), "assume")
    named(
      scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Eff.succeed(1).semiflatMap(n => cats.effect.IO.pure(n))"),
      "semiflatMap"
    )
    named(scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Eff.succeed(1).tap(n => cats.effect.IO.unit)"), "tap")
    named(
      scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Eff.succeed(1).productR(boilerplate.effect.Eff.succeed(2))"),
      "productR"
    )
    named(
      scala.compiletime.testing.typeCheckErrors("boilerplate.effect.Eff.succeed(1).productL(boilerplate.effect.Eff.succeed(2))"),
      "productL"
    )
    named(
      scala.compiletime.testing.typeCheckErrors(
        "boilerplate.effect.Eff.retryWithBackoff(boilerplate.effect.Eff.succeed(1), 3, scala.concurrent.duration.Duration.Zero, None)"
      ),
      "retryWithBackoff"
    )

  test("the counted retry overload is gone; only the policy overloads remain"):
    assert(!scala.compiletime.testing.typeChecks("boilerplate.effect.Eff.retry(boilerplate.effect.Eff.succeed(1), 3)"))
    assert(
      scala.compiletime.testing.typeChecks(
        "boilerplate.effect.Eff.retry(boilerplate.effect.Eff.succeed(1), boilerplate.effect.RetryPolicy.constant(scala.concurrent.duration.Duration.Zero))"
      )
    )

  test("the Show, Eq and PartialOrder givens are gone - they delegated to instances only a test kit defines"):
    assert(!scala.compiletime.testing.typeChecks("summon[cats.Show[boilerplate.effect.UEff[Int]]]"))
    assert(!scala.compiletime.testing.typeChecks("summon[cats.Eq[boilerplate.effect.UEff[Int]]]"))
    assert(!scala.compiletime.testing.typeChecks("summon[cats.kernel.PartialOrder[boilerplate.effect.UEff[Int]]]"))

  test("the top-level TypeTest for the empty channel is gone"):
    assert(!scala.compiletime.testing.typeChecks("summon[scala.reflect.TypeTest[Throwable, Nothing]]"))

  test("evalOn preserves both channels across the executor shift"):
    for
      ec <- IO.executionContext
      ok <- run((Eff.succeed(1): Eff[IOError, Int]).evalOn(ec))
      ko <- run((Eff.fail[IOError](Closed): Eff[IOError, Int]).evalOn(ec))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Closed))
end EffSuite
