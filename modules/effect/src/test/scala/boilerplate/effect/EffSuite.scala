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
import scala.reflect.TypeTest
import scala.util.Failure
import scala.util.Try

import cats.data.EitherT
import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.syntax.parallel.*
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IoError.*

class EffSuite extends CatsEffectSuite:
  private def run[E <: Throwable, A](eff: Eff[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] = eff.either

  // Constructors

  test("succeed lands in the success channel and fail in IO's Throwable channel"):
    for
      s <- run(Eff.succeed(1))
      f <- run(Eff.fail[IoError](Closed))
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
      l <- run(Eff.lift(IO.pure(Left(Closed): Either[IoError, Int])))
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
    val ok = Eff.delay[IoError, Int] { executed = true; Right(42) }
    val ko = Eff.delay[IoError, Int](Left(Closed))
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
      r <- run(Eff.blocking[IoError, Int](Right(7)))
      l <- run(Eff.blocking[IoError, Int](Left(Closed)))
      s <- run(Eff.suspendBlocking(6 * 7))
    yield
      assertEquals(r, Right(7))
      assertEquals(l, Left(Closed))
      assertEquals(s, Right(42))

  test("defer delays evaluation until run"):
    var evaluated = false // scalafix:ok DisableSyntax.var
    val eff = Eff.defer[IoError, Int] { evaluated = true; Eff.succeed(42) }
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
      rw <- run(Eff.raiseWhen[IoError](true)(Closed))
      rwN <- run(Eff.raiseWhen[IoError](false)(Closed))
      ru <- run(Eff.raiseUnless[IoError](false)(Closed))
      ruN <- run(Eff.raiseUnless[IoError](true)(Closed))
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
    val base: Eff[IoError, Int] = Eff.succeed(10)
    for
      m <- run(base.map(_ + 1))
      fm <- run(base.flatMap(n => Eff.succeed(n * 2)))
      skip <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).map(_ => 0))
    yield
      assertEquals(m, Right(11))
      assertEquals(fm, Right(20))
      assertEquals(skip, Left(Closed))

  test("flatMap and subflatMap widen - never drop - the receiver's typed error"):
    // Both sequence, so the receiver's `E` must survive into the result. Without the `E2 >: E` lower
    // bound the error silently vanishes from the type and escapes as a defect.
    val viaFlatMap: Eff[IoError, Int] = Eff.fail(Closed).flatMap(_ => Eff.succeed(1))
    val viaSubflatMap: Eff[IoError, Int] = Eff.fail(Closed).subflatMap(_ => Right(1))

    // Narrowing the receiver's `IoError` away to an infallible channel must NOT typecheck.
    val flatMapDrop = scala.compiletime.testing.typeCheckErrors(
      "val bad: boilerplate.effect.UEff[Int] = boilerplate.effect.Eff.fail(boilerplate.effect.IoError.Closed).flatMap(_ => boilerplate.effect.Eff.succeed(1))"
    )
    val subflatMapDrop = scala.compiletime.testing.typeCheckErrors(
      "val bad: boilerplate.effect.UEff[Int] = boilerplate.effect.Eff.fail(boilerplate.effect.IoError.Closed).subflatMap(_ => Right(1))"
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

  test("semiflatMap applies an effectful function and short-circuits on failure"):
    for
      called <- IO.ref(false)
      ok <- run((Eff.succeed(2): Eff[AppError, Int]).semiflatMap(n => IO.pure(n * 10)))
      skip <- run((Eff.fail[AppError](Timeout): Eff[AppError, Int]).semiflatMap(_ => called.set(true).flatMap(_ => IO.pure(0))))
      wasCalled <- called.get
    yield
      assertEquals(ok, Right(20))
      assertEquals(skip, Left(Timeout))
      assert(!wasCalled)

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
    val boom: Eff[IoError, Int] = IO.raiseError(RuntimeException("boom"))
    for
      recovered <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).catchAll(e => Eff.succeed(e.getMessage.length)))
      changed <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).catchAll(_ => Eff.fail[AppError](Timeout)))
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
    // The `Eff[IoError, Int]` ascriptions are load-bearing: they assert the residual is narrowed.
    val onApp: Eff[IoError | AppError, Int] = Eff.fail(NotFound("u1"))
    val onIo: Eff[IoError | AppError, Int] = Eff.fail(Failed(500))
    val recovered: Eff[IoError, Int] = onApp.catchOnly((_: AppError) => Eff.succeed(-1))
    val residual: Eff[IoError, Int] = onIo.catchOnly((_: AppError) => Eff.succeed(-1))
    for
      r <- run(recovered)
      s <- run(residual)
    yield
      assertEquals(r, Right(-1))
      assertEquals(s, Left(Failed(500)))

  test("catchOnly lets the handler re-fail into the residual channel"):
    val onApp: Eff[IoError | AppError, Int] = Eff.fail(Invalid("bad"))
    // Re-failing into the residual: ascribe to the residual root so it is not inferred too narrowly.
    val narrowed: Eff[IoError, Int] = onApp.catchOnly((_: AppError) => Eff.fail[IoError](Closed))
    run(narrowed).map(r => assertEquals(r, Left(Closed)))

  test("catchOnly with an infallible handler infers the narrow residual unascribed"):
    val onApp: Eff[IoError | AppError, Int] = Eff.fail(NotFound("u2"))
    // No ascription: the infallible-handler twin subtracts the handled arm. The witness is the
    // compile-time lock - it fails if the residual ever widens again.
    val handled = onApp.catchOnly((_: AppError) => Eff.succeed(-1))
    val _ = summon[handled.type <:< Eff[IoError, Int]]
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
    val rogue: Eff[IoError | AppError, Int] = IO.raiseError(RuntimeException("DEFECT"))
    val recovered = rogue.catchOnly((_: AppError) => Eff.succeed(-1))
    recovered.catchAll(_ => Eff.succeed(0)).absolve.attempt.map(r => assert(r.left.exists(_.getMessage == "DEFECT")))

  test("mapError transforms the typed channel and leaves a defect untouched"):
    val boom: Eff[IoError, Int] = IO.raiseError(RuntimeException("boom"))
    for
      mapped <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).mapError(e => Invalid(e.getMessage)))
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
    val boom: Eff[IoError, Int] = Eff.fail(Closed)
    for
      fromErr <- run(boom.redeemAll(_ => Eff.succeed(-1), a => Eff.succeed(a)))
      fromOk <- run((Eff.succeed(5): Eff[IoError, Int]).redeemAll(_ => Eff.succeed(-1), a => Eff.succeed(a)))
      changed <- run(boom.redeemAll(e => Eff.fail[AppError](Invalid(e.getMessage)), a => Eff.succeed(a)))
    yield
      assertEquals(fromErr, Right(-1))
      assertEquals(fromOk, Right(5))
      assertEquals(changed, Left(Invalid("closed")))

  test("fold and foldF collapse both channels to the base IO"):
    val boom: Eff[IoError, Int] = Eff.fail(Closed)
    for
      e <- boom.fold(_.getMessage.length, _ => 0)
      a <- (Eff.succeed(7): Eff[IoError, Int]).fold(_ => -1, identity)
      ef <- boom.foldF(err => IO.pure(err.getMessage.length), v => IO.pure(v))
    yield
      assertEquals(e, 6)
      assertEquals(a, 7)
      assertEquals(ef, 6)

  test("orElseSucceed, orElseFail, valueOr, and alt"):
    val boom: Eff[IoError, Int] = Eff.fail(Closed)
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
    val boom: Eff[IoError, Int] = Eff.fail(Closed)
    for
      tapObs <- IO.ref(Option.empty[String])
      tapR <- run(boom.tapError(e => tapObs.set(Some(e.getMessage))))
      tapSeen <- tapObs.get
      ftObs <- IO.ref(Option.empty[String])
      ftR <- run(boom.flatTapError(e => ftObs.set(Some(e.getMessage))))
      ftSeen <- ftObs.get
      replaced <- run(boom.flatTapError(_ => Eff.fail[IoError](Failed(1))))
    yield
      assertEquals(tapR, Left(Closed))
      assertEquals(tapSeen, Some("closed"))
      assertEquals(ftR, Left(Closed))
      assertEquals(ftSeen, Some("closed"))
      assertEquals(replaced, Left(Failed(1)))
    end for

  test("attemptTap observes the reified result and propagates a failing side effect"):
    for
      seenErr <- IO.ref(Option.empty[Either[IoError, Int]])
      errR <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).attemptTap(ea => seenErr.set(Some(ea))))
      errObs <- seenErr.get
      seenOk <- IO.ref(Option.empty[Either[IoError, Int]])
      okR <- run((Eff.succeed(42): Eff[IoError, Int]).attemptTap(ea => seenOk.set(Some(ea))))
      okObs <- seenOk.get
      prop <- run((Eff.succeed(42): Eff[IoError, Int]).attemptTap(_ => Eff.fail[IoError](Failed(9))))
    yield
      assertEquals(errR, Left(Closed))
      assertEquals(errObs, Some(Left(Closed)))
      assertEquals(okR, Right(42))
      assertEquals(okObs, Some(Right(42)))
      assertEquals(prop, Left(Failed(9)))

  test("option, collectSome, and collectRight"):
    for
      optS <- run((Eff.succeed(42): Eff[IoError, Int]).option)
      optE <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).option)
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
      e <- (Eff.succeed(42): Eff[IoError, Int]).either
      et <- (Eff.fail[IoError](Closed): Eff[IoError, Int]).eitherT.value
    yield
      assertEquals(e, Right(42))
      assertEquals(et, Left(Closed))

  test("absolve raises the typed error into IO's channel; success passes through"):
    for
      ok <- (Eff.succeed(1): Eff[IoError, Int]).absolve
      ko <- (Eff.fail[IoError](Closed): Eff[IoError, Int]).absolve.attempt
    yield
      assertEquals(ok, 1)
      assertEquals(ko.left.toOption, Some(Closed))

  test("a typed error reifies to Left; a defect stays on the IO channel"):
    val boom: Eff[IoError, Int] = IO.raiseError(RuntimeException("defect"))
    for
      typed <- run(Eff.fail[IoError](Closed)).attempt
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
      useR <- run((Eff.succeed(42): Eff[IoError, Int]).bracket(_ => Eff.fail[IoError](Closed))(_ => relUse.set(true)))
      usedReleased <- relUse.get
      relAcq <- IO.ref(false)
      acqR <- run((Eff.fail[IoError](Failed(1)): Eff[IoError, Int]).bracket(a => Eff.succeed(a))(_ => relAcq.set(true)))
      acqReleased <- relAcq.get
    yield
      assertEquals(useR, Left(Closed))
      assert(usedReleased)
      assertEquals(acqR, Left(Failed(1)))
      assert(!acqReleased)

  test("bracketCase surfaces Succeeded for a value and Errored for a typed use failure"):
    for
      okOc <- IO.ref("")
      r <- run((Eff.succeed(42): Eff[IoError, Int]).bracketCase(a => Eff.succeed(a)) { (_, oc) =>
             oc match
               case Outcome.Succeeded(_) => okOc.set("succeeded")
               case Outcome.Errored(_)   => okOc.set("errored")
               case Outcome.Canceled()   => okOc.set("canceled")
           })
      okSeen <- okOc.get
      errOc <- IO.ref("")
      e <- run((Eff.succeed(42): Eff[IoError, Int]).bracketCase(_ => Eff.fail[IoError](Closed)) { (_, oc) =>
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
    val slowGood: Eff[IoError, Int] = IO.sleep(1.second).flatMap(_ => IO.pure(1))
    for
      raced <- run((Eff.succeed(1): Eff[IoError, Int]).race(Eff.never: Eff[IoError, Int]))
      paired <- run((Eff.succeed(1): Eff[IoError, Int]).both(Eff.succeed(2)))
      failFast <- run(slowGood.both(Eff.fail[IoError](Closed)))
    yield
      assertEquals(raced, Right(Left(1)))
      assertEquals(paired, Right((1, 2)))
      assertEquals(failFast, Left(Closed))

  test("start: a successful join is Succeeded, a typed-failure join is Errored"):
    def label(eff: Eff[IoError, Int]): Eff[IoError, String] =
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
    (Eff.succeed(42): Eff[IoError, Int]).background
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
    val slow: Eff[IoError, Int] = IO.sleep(1.second).flatMap(_ => IO.pure(1))
    for
      fb <- run(slow.timeoutTo(50.millis, Eff.succeed(42)))
      within <- run((Eff.succeed(42): Eff[IoError, Int]).timeoutTo(1.second, Eff.succeed(0)))
    yield
      assertEquals(fb, Right(42))
      assertEquals(within, Right(42))

  test("delayBy delays execution and andWait waits after it"):
    for
      start <- IO.monotonic
      r1 <- run((Eff.succeed(42): Eff[IoError, Int]).delayBy(10.millis))
      mid <- IO.monotonic
      r2 <- run((Eff.succeed(42): Eff[IoError, Int]).andWait(10.millis))
      end <- IO.monotonic
    yield
      assertEquals(r1, Right(42))
      assertEquals(r2, Right(42))
      assert(clue(mid - start) >= 9.millis) // 1ms tolerance for JS timer imprecision
      assert(clue(end - mid) >= 9.millis)

  test("timed returns the result paired with a non-negative duration"):
    run((Eff.succeed(42): Eff[IoError, Int]).timed).map {
      case Right((dur, value)) =>
        assertEquals(value, 42)
        assert(dur >= 0.nanos)
      case Left(e) => fail(s"unexpected error: $e")
    }

  test("&> and <& run in parallel, discarding the appropriate side, and short-circuit on error"):
    val a: Eff[IoError, Int] = Eff.succeed(1)
    val b: Eff[IoError, String] = Eff.succeed("two")
    for
      r <- run(a &> b)
      l <- run(a <& b)
      shortR <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]) &> b)
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
      okR <- run((Eff.succeed(42): Eff[IoError, Int]).onCancel(onSuccessRan.set(true)))
      onSuccessSeen <- onSuccessRan.get
      guaranteeRan <- IO.ref(0)
      gOk <- run((Eff.succeed(42): Eff[IoError, Int]).guarantee(guaranteeRan.update(_ + 1)))
      gErr <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).guarantee(guaranteeRan.update(_ + 1)))
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
      _ <- (Eff.succeed(1): Eff[IoError, Int])
             .guaranteeCase {
               case Outcome.Succeeded(_) => onOk.set("succeeded")
               case Outcome.Errored(_)   => onOk.set("errored")
               case Outcome.Canceled()   => onOk.set("canceled")
             }
             .absolve
             .attempt
      _ <- (Eff.fail[IoError](Closed): Eff[IoError, Int])
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
      ok <- run(Eff.traverse[IoError, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2)))
      visited <- IO.ref(0)
      ko <- run(Eff.traverse[IoError, Int, Int](List(1, 2, 3)) { n =>
              (visited.update(_ + 1): Eff[IoError, Unit]).flatMap(_ => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n))
            })
      seen <- visited.get
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(seen, 2) // stops after visiting 1 and 2

  test("traverse preserves order for a large collection without stack overflow or quadratic blowup"):
    val n = 5000
    run(Eff.traverse[IoError, Int, Int]((1 to n).toList)(Eff.succeed(_)))
      .map(r => assertEquals(r, Right((1 to n).toList)))

  test("sequence collects; traverse_ and sequence_ run for effect and discard"):
    for
      seq <- run(Eff.sequence[IoError, Int](List(Eff.succeed(1), Eff.succeed(2), Eff.succeed(3))))
      sum <- IO.ref(0)
      tvU <- run(Eff.traverse_[IoError, Int, Unit](List(1, 2, 3))(n => sum.update(_ + n)))
      total <- sum.get
      seqU <- run(Eff.sequence_[IoError, Int](List(Eff.succeed(1), Eff.succeed(2))))
    yield
      assertEquals(seq, Right(List(1, 2, 3)))
      assertEquals(tvU, Right(()))
      assertEquals(total, 6)
      assertEquals(seqU, Right(()))

  test("parTraverse runs in parallel and short-circuits; parSequence collects"):
    for
      ok <- run(Eff.parTraverse[IoError, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2)))
      ko <- run(Eff.parTraverse[IoError, Int, Int](List(1, 2, 3))(n => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n)))
      ps <- run(Eff.parSequence[IoError, Int](List(Eff.succeed(1), Eff.succeed(2))))
      psErr <- run(Eff.parSequence[IoError, Int](List(Eff.succeed(1), Eff.fail(Closed), Eff.succeed(3))))
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(ps, Right(List(1, 2)))
      assertEquals(psErr, Left(Closed))

  test("parTraverse_ and parSequence_ run all in parallel, discard, and propagate a typed error"):
    for
      ok <- run(Eff.parTraverse_[IoError, Int, Int](List(1, 2, 3))(Eff.succeed(_)))
      ko <- run(Eff.parTraverse_[IoError, Int, Int](List(1, 2, 3))(n => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n)))
      ps <- run(Eff.parSequence_[IoError, Int](List(Eff.succeed(1), Eff.succeed(2))))
    yield
      assertEquals(ok, Right(()))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(ps, Right(()))

  // Retry

  private def failingEff(counter: Ref[IO, Int], e: IoError): Eff[IoError, Int] =
    val bump: Eff[IoError, Unit] = counter.update(_ + 1)
    bump.flatMap(_ => Eff.fail(e))

  test("retry re-runs a failing effect up to the limit, then propagates the final error"):
    for
      attempts <- IO.ref(0)
      eff: Eff[IoError, Int] =
        (attempts.updateAndGet(_ + 1): Eff[IoError, Int]).flatMap(n => if n < 3 then Eff.fail(Failed(n)) else Eff.succeed(n))
      r <- run(Eff.retry(eff, 5))
      count <- attempts.get
      exhausted <- IO.ref(0)
      r2 <- run(Eff.retry(failingEff(exhausted, Closed), 3))
      count2 <- exhausted.get
    yield
      assertEquals(r, Right(3))
      assertEquals(count, 3)
      assertEquals(r2, Left(Closed))
      assertEquals(count2, 4) // 1 initial + 3 retries

  test("retryWithBackoff succeeds after transient failures"):
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val step: Eff[IoError, Int] =
      Eff.suspend(attempts += 1).flatMap(_ => if attempts < 3 then Eff.fail(Failed(attempts)) else Eff.succeed(42))
    run(Eff.retryWithBackoff(step, 5, 1.millis, Some(10.millis))).map { r =>
      assertEquals(r, Right(42))
      assertEquals(attempts, 3)
    }

  test("retryWithBackoff caps the delay at maxDelay"):
    // Cap at 1ms with a 10ms initial delay: uncapped the delays would be 10+20+40 = 70ms; capped
    // they are exactly 1+1+1. Virtual time makes that an equality rather than a wall-clock bound,
    // which under a loaded four-platform matrix is not a bound at all.
    TestControl.executeEmbed {
      var attempts = 0 // scalafix:ok DisableSyntax.var
      val step: Eff[IoError, Int] = Eff.suspend(attempts += 1).flatMap(_ => Eff.fail(Closed))
      for
        start <- IO.monotonic
        result <- run(Eff.retryWithBackoff(step, 3, 10.millis, Some(1.millis)))
        end <- IO.monotonic
      yield
        assertEquals(result, Left(Closed))
        assertEquals(attempts, 4) // 1 initial + 3 retries
        assertEquals(end - start, 3.millis)
    }

  test("retryWithBackoff survives a doubling progression that would overflow FiniteDuration"):
    // 1s doubled 40 times exceeds FiniteDuration's range; the progression must hold steady
    // instead of throwing mid-retry. Virtual time keeps the capped 1ms sleeps instant.
    TestControl.executeEmbed {
      for
        counter <- IO.ref(0)
        out <- run(Eff.retryWithBackoff(failingEff(counter, Closed), 40, 1.second, Some(1.milli)))
        n <- counter.get
      yield
        assertEquals(out, Left(Closed))
        assertEquals(n, 41)
    }

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
        eff: Eff[IoError, Int] =
          (attempts.updateAndGet(_ + 1): Eff[IoError, Int]).flatMap(n => if n < 3 then Eff.fail(Failed(n)) else Eff.succeed(n))
        out <- run(Eff.retry(eff, RetryPolicy.constant(10.millis).withMaxAttempts(5)))
        n <- attempts.get
      yield
        assertEquals(out, Right(3))
        assertEquals(n, 3)
    }

  test("policy retry honours the retryOn predicate per error"):
    TestControl.executeEmbed {
      val policy = RetryPolicy.constant(10.millis).withMaxAttempts(3)
      val retriable = (e: IoError) =>
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
        hook = (attempt: Int, _: IoError, d: FiniteDuration) => delays.update((attempt, d) :: _)
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
        hook = (_: Int, _: IoError, d: FiniteDuration) => delays.update(d :: _)
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
        seen <- IO.ref(List.empty[(Int, IoError, FiniteDuration)])
        policy = RetryPolicy.constant(10.millis).withMaxAttempts(3)
        hook = (n: Int, e: IoError, d: FiniteDuration) => seen.update((n, e, d) :: _)
        _ <- run(Eff.retry(failingEff(counter, Closed), policy, hook))
        entries <- seen.get.map(_.reverse)
      yield
        val expected: List[(Int, IoError, FiniteDuration)] = List((1, Closed, 10.millis), (2, Closed, 10.millis))
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
    val F = summon[cats.MonadError[Eff.Of[IoError], IoError]]
    run(F.handleError(F.raiseError[Int](Closed))(_.getMessage.length))
      .map(r => assertEquals(r, Right(6)))

  test("the summoned GenConcurrent instance runs a concurrent program"):
    val F = summon[cats.effect.kernel.GenConcurrent[Eff.Of[IoError], Throwable]]
    val program =
      for
        ref <- F.ref(0)
        _ <- ref.update(_ + 1)
        v <- ref.get
      yield v
    run(program).map(r => assertEquals(r, Right(1)))

  test("the summoned GenTemporal instance raises TimeoutException on the defect channel"):
    val T = summon[cats.effect.kernel.GenTemporal[Eff.Of[IoError], Throwable]]
    val slow = T.flatMap(T.sleep(1.second))(_ => T.pure(42))
    // A timeout is a defect (TimeoutException in IO's channel), not the typed IoError channel.
    T.timeout(slow, 10.millis).absolve.attempt.map {
      case Left(_: java.util.concurrent.TimeoutException) => ()
      case Right(_)                                       => fail("should have timed out")
      case Left(e)                                        => fail(s"wrong error type: ${e.getClass.getName}")
    }

  test("the Parallel instance enables parMapN and short-circuits on error"):
    for
      ok <- run(((Eff.succeed(1): Eff[IoError, Int]), (Eff.succeed(2): Eff[IoError, Int])).parMapN(_ + _))
      ko <- run(((Eff.succeed(1): Eff[IoError, Int]), (Eff.fail[IoError](Closed): Eff[IoError, Int])).parMapN(_ + _))
    yield
      assertEquals(ok, Right(3))
      assertEquals(ko, Left(Closed))

  test("evalOn preserves both channels across the executor shift"):
    for
      ec <- IO.executionContext
      ok <- run((Eff.succeed(1): Eff[IoError, Int]).evalOn(ec))
      ko <- run((Eff.fail[IoError](Closed): Eff[IoError, Int]).evalOn(ec))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Closed))
end EffSuite
