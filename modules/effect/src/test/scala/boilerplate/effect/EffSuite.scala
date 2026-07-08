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

import cats.MonadThrow
import cats.data.EitherT
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.kernel.Outcome
import cats.effect.std.AtomicCell
import cats.syntax.parallel.*
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IoError.*

/** Behavioural tests for the generic `Eff[F, E, A]` - the phantom typed-error effect over an
  * arbitrary base `F`'s own `Throwable` channel - exercised primarily with `F = IO`. Typeclass
  * lawfulness is verified separately by the discipline suite; these cover `Eff`'s own logic paths
  * and the phantom-specific contracts: `E <: Throwable`, a typed failure IS an `F` failure
  * (`Outcome.Errored`), observation needs `MonadThrow[F]` + a `TypeTest` and never swallows a
  * defect, covariant `E` widening / union inference, and every capability transferring from `F` by
  * representation.
  */
class EffSuite extends CatsEffectSuite:
  private def runEff[F[_], E <: Throwable, A](eff: Eff[F, E, A])(using MonadThrow[F], TypeTest[Throwable, E]): F[Either[E, A]] =
    eff.either

  // --- Constructors -----------------------------------------------------------------------------

  test("the Eff[F] partially-applied constructors fix F and resolve its channels"):
    for
      s <- runEff(Eff[IO].succeed(42))
      f <- runEff(Eff[IO].fail(Closed))
      fr <- runEff(Eff[IO].from(Left(Timeout): Either[AppError, Int]))
    yield
      assertEquals(s, Right(42))
      assertEquals(f, Left(Closed))
      assertEquals(fr, Left(Timeout))

  test("from lifts Either, Option, Try, and EitherT into the effect"):
    for
      e <- runEff(Eff.from[IO, AppError, Int](Right(1): Either[AppError, Int]))
      oS <- runEff(Eff.from[IO, AppError, Int](Some(2), Timeout))
      oN <- runEff(Eff.from[IO, AppError, Int](None: Option[Int], Timeout))
      tS <- runEff(Eff.from[IO, AppError, Int](Try(3), t => Invalid(t.getMessage)))
      tF <- runEff(Eff.from[IO, AppError, Int](Failure(RuntimeException("t")), t => Invalid(t.getMessage)))
      et <- runEff(Eff.from(EitherT.fromEither[IO](Left(Timeout): Either[AppError, Int])))
    yield
      assertEquals(e, Right(1))
      assertEquals(oS, Right(2))
      assertEquals(oN, Left(Timeout))
      assertEquals(tS, Right(3))
      assertEquals(tF, Left(Invalid("t")))
      assertEquals(et, Left(Timeout))

  test("lift absorbs an F[Either] Left and supplies ifNone for an empty F[Option]"):
    for
      l <- runEff(Eff.lift(IO.pure(Left(Closed): Either[IoError, Int])))
      lo <- runEff(Eff.lift(IO.pure(None: Option[Int]), Closed))
      ls <- runEff(Eff.lift(IO.pure(Some(5): Option[Int]), Closed))
    yield
      assertEquals(l, Left(Closed))
      assertEquals(lo, Left(Closed))
      assertEquals(ls, Right(5))

  test("attempt(io, ifFailure) translates any raised throwable to a typed error"):
    for
      ok <- runEff(Eff.attempt(IO.pure(1), t => Invalid(t.getMessage)))
      ko <- runEff(Eff.attempt(IO.raiseError[Int](RuntimeException("x")), t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Invalid("x")))

  test("attempt(pf) catches matching throwables and lets unmatched propagate as a defect"):
    val matched = Eff.attempt(IO.raiseError[Int](IllegalArgumentException("bad"))):
      case _: IllegalArgumentException => Invalid("bad")
    val unmatched = Eff.attempt(IO.raiseError[Int](RuntimeException("untouched"))):
      case _: IllegalArgumentException => Invalid("bad")
    for
      m <- runEff(matched)
      u <- unmatched.absolve.attempt
    yield
      assertEquals(m, Left(Invalid("bad")))
      assert(u.left.exists(_.getMessage == "untouched"))

  test("suspend defers a side effect until run (static and partially-applied)"):
    var count = 0 // scalafix:ok DisableSyntax.var
    val s1 = Eff.suspend[IO, IoError, Int] { count += 1; 42 }
    val s2 = Eff[IO].suspend { count += 1; 7 }
    assertEquals(count, 0)
    for
      a <- runEff(s1)
      b <- s2.absolve
    yield
      assertEquals(a, Right(42))
      assertEquals(b, 7)
      assertEquals(count, 2)

  test("delay suspends an Either-producing side effect until run, capturing Left and Right"):
    var executed = false // scalafix:ok DisableSyntax.var
    val ok = Eff.delay[IO, IoError, Int] { executed = true; Right(42) }
    val ko = Eff.delay[IO, IoError, Int](Left(Closed))
    assert(!executed)
    for
      r <- runEff(ok)
      l <- runEff(ko)
    yield
      assert(executed)
      assertEquals(r, Right(42))
      assertEquals(l, Left(Closed))

  test("blocking and suspendBlocking run on the blocking pool"):
    for
      r <- runEff(Eff.blocking[IO, IoError, Int](Right(7)))
      l <- runEff(Eff.blocking[IO, IoError, Int](Left(Closed)))
      s <- runEff(Eff.suspendBlocking[IO, IoError, Int](6 * 7))
    yield
      assertEquals(r, Right(7))
      assertEquals(l, Left(Closed))
      assertEquals(s, Right(42))

  test("defer delays evaluation until run"):
    var evaluated = false // scalafix:ok DisableSyntax.var
    val eff = Eff.defer[IO, IoError, Int] { evaluated = true; Eff.succeed(42) }
    assert(!evaluated)
    runEff(eff).map { r =>
      assert(evaluated)
      assertEquals(r, Right(42))
    }

  test("when/unless run the effect only on the intended condition"):
    for
      exec <- IO.ref(0)
      _ <- runEff(Eff.when[IO, IoError](true)(Eff.liftF(exec.update(_ + 1))))
      _ <- runEff(Eff.when[IO, IoError](false)(Eff.liftF(exec.update(_ + 1))))
      _ <- runEff(Eff.unless[IO, IoError](false)(Eff.liftF(exec.update(_ + 1))))
      _ <- runEff(Eff.unless[IO, IoError](true)(Eff.liftF(exec.update(_ + 1))))
      count <- exec.get
    yield assertEquals(count, 2) // when(true) + unless(false)

  test("raiseWhen/raiseUnless raise only on the intended condition"):
    for
      rw <- runEff(Eff.raiseWhen[IO, IoError](true)(Closed))
      rwN <- runEff(Eff.raiseWhen[IO, IoError](false)(Closed))
      ru <- runEff(Eff.raiseUnless[IO, IoError](false)(Closed))
      ruN <- runEff(Eff.raiseUnless[IO, IoError](true)(Closed))
    yield
      assertEquals(rw, Left(Closed))
      assertEquals(rwN, Right(()))
      assertEquals(ru, Left(Closed))
      assertEquals(ruN, Right(()))

  test("cond lifts a predicate, evaluating only the selected branch"):
    var trueSide = 0 // scalafix:ok DisableSyntax.var
    var falseSide = 0 // scalafix:ok DisableSyntax.var
    val ok = Eff.cond[IO, AppError, Int](true, { trueSide += 1; 42 }, { falseSide += 1; Timeout })
    val ko = Eff.cond[IO, AppError, Int](false, { trueSide += 1; 42 }, { falseSide += 1; Timeout })
    for
      okR <- runEff(ok)
      koR <- runEff(ko)
    yield
      assertEquals(okR, Right(42))
      assertEquals(koR, Left(Timeout))
      assertEquals(trueSide, 1)
      assertEquals(falseSide, 1)

  test("success-only ops compose over a non-IO Monad without MonadThrow"):
    // map/flatMap/succeed/liftF need only Monad/Functor - no MonadThrow, no TypeTest - and absolve is
    // O(0) identity returning the base effect, here Option.
    val eff: Eff[Option, Nothing, Int] =
      for
        a <- Eff.succeed[Option, Nothing, Int](20)
        b <- Eff.liftF[Option, Nothing, Int](Some(22))
      yield a + b
    assertEquals(eff.absolve, Some(42))

  // --- Covariance in E and union inference -------------------------------------------------------

  test("a narrow error widens to a broad error with no call-site method"):
    val narrow: Eff[IO, NotFound, Int] = Eff.fail(NotFound("u1"))
    val wide: Eff[IO, AppError, Int] = narrow
    runEff(wide).map(r => assertEquals(r, Left(NotFound("u1"))))

  test("a for-comprehension over distinct Throwable error types infers their union"):
    def find(id: String): Eff[IO, NotFound, Int] =
      if id == "1" then Eff.succeed(1) else Eff.fail(NotFound(id))
    def validate(n: Int): Eff[IO, Invalid, Int] =
      if n > 0 then Eff.succeed(n) else Eff.fail(Invalid("non-positive"))
    val workflow: Eff[IO, NotFound | Invalid, Int] =
      for
        n <- find("1")
        v <- validate(n)
      yield v
    val failing: Eff[IO, NotFound | Invalid, Int] =
      for
        n <- find("2")
        v <- validate(n)
      yield v
    for
      ok <- runEff(workflow)
      ko <- runEff(failing)
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(NotFound("2")))

  // --- Mapping ----------------------------------------------------------------------------------

  test("map and flatMap act on success and short-circuit on a typed failure"):
    for
      m <- runEff(Eff.succeed[IO, IoError, Int](10).map(_ + 1))
      fm <- runEff(Eff.succeed[IO, IoError, Int](10).flatMap(n => Eff.succeed(n * 2)))
      skip <- runEff(Eff.fail[IO, IoError, Int](Closed).map(_ => 0))
    yield
      assertEquals(m, Right(11))
      assertEquals(fm, Right(20))
      assertEquals(skip, Left(Closed))

  test("semiflatMap applies an effectful function and short-circuits on failure"):
    for
      called <- IO.ref(false)
      ok <- runEff(Eff.succeed[IO, AppError, Int](2).semiflatMap(n => IO.pure(n * 10)))
      skip <- runEff(Eff.fail[IO, AppError, Int](Timeout).semiflatMap(_ => called.set(true).flatMap(_ => IO.pure(0))))
      wasCalled <- called.get
    yield
      assertEquals(ok, Right(20))
      assertEquals(skip, Left(Timeout))
      assert(!wasCalled)

  test("subflatMap and transform reshape through a pure Either"):
    for
      sub <- runEff(Eff.succeed[IO, AppError, Int](6).subflatMap(n => if n > 5 then Right(n * 2) else Left(Invalid("small"))))
      subL <- runEff(Eff.succeed[IO, AppError, Int](3).subflatMap(n => if n > 5 then Right(n * 2) else Left(Invalid("small"))))
      tr <- runEff(Eff.succeed[IO, AppError, Int](21).transform(_.map(_ * 2)))
      trErr <- runEff(Eff.fail[IO, AppError, Int](Timeout).transform(_ => Right(0): Either[AppError, Int]))
    yield
      assertEquals(sub, Right(12))
      assertEquals(subL, Left(Invalid("small")))
      assertEquals(tr, Right(42))
      assertEquals(trErr, Right(0))

  // --- Recovery ---------------------------------------------------------------------------------

  test("catchAll recovers a typed error, allows an error-type change, and never swallows a defect"):
    for
      recovered <- runEff(Eff.fail[IO, IoError, Int](Closed).catchAll(e => Eff.succeed(e.getMessage.length)))
      changed <- runEff(Eff.fail[IO, IoError, Int](Closed).catchAll(_ => Eff.fail[IO, AppError, Int](Timeout)))
      defect <- Eff.liftF[IO, IoError, Int](IO.raiseError(RuntimeException("boom"))).catchAll(_ => Eff.succeed(0)).absolve.attempt
    yield
      assertEquals(recovered, Right(6))
      assertEquals(changed, Left(Timeout))
      assert(defect.left.exists(_.getMessage == "boom"))

  test("catchSome recovers matched errors and passes unmatched through"):
    val f: Eff[IO, AppError, Int] => Eff[IO, AppError, Int] =
      _.catchSome { case _: NotFound => Eff.succeed(1) }
    for
      m <- runEff(f(Eff.fail(NotFound("x"))))
      u <- runEff(f(Eff.fail(Invalid("y"))))
    yield
      assertEquals(m, Right(1))
      assertEquals(u, Left(Invalid("y")))

  test("catchOnly handles one union arm and narrows the residual at compile time"):
    // The `Eff[IO, IoError, Int]` ascriptions are load-bearing: they assert the residual is narrowed.
    val onApp: Eff[IO, IoError | AppError, Int] = Eff.fail(NotFound("u1"))
    val onIo: Eff[IO, IoError | AppError, Int] = Eff.fail(Failed(500))
    val recovered: Eff[IO, IoError, Int] = onApp.catchOnly((_: AppError) => Eff.succeed(-1))
    val residual: Eff[IO, IoError, Int] = onIo.catchOnly((_: AppError) => Eff.succeed(-1))
    for
      r <- runEff(recovered)
      s <- runEff(residual)
    yield
      assertEquals(r, Right(-1))
      assertEquals(s, Left(Failed(500)))

  test("catchOnly lets the handler re-fail into the residual channel"):
    val onApp: Eff[IO, IoError | AppError, Int] = Eff.fail(Invalid("bad"))
    val narrowed: Eff[IO, IoError, Int] = onApp.catchOnly((_: AppError) => Eff.fail[IO, IoError, Int](Closed))
    runEff(narrowed).map(r => assertEquals(r, Left(Closed)))

  test("mapError transforms the typed channel and leaves a defect untouched"):
    for
      mapped <- runEff(Eff.fail[IO, IoError, Int](Closed).mapError(e => Invalid(e.getMessage)))
      defect <-
        Eff
          .liftF[IO, IoError, Int](IO.raiseError(RuntimeException("boom")))
          .mapError(e => Invalid(e.getMessage))
          .absolve
          .attempt
    yield
      assertEquals(mapped, Left(Invalid("closed")))
      assert(defect.left.exists(_.getMessage == "boom"))

  test("mapErrorPartial transforms matched errors and passes others through"):
    val f: Eff[IO, AppError, Int] => Eff[IO, AppError, Int] =
      _.mapErrorPartial { case _: NotFound => Timeout }
    for
      m <- runEff(f(Eff.fail(NotFound("x"))))
      u <- runEff(f(Eff.fail(Invalid("y"))))
    yield
      assertEquals(m, Left(Timeout))
      assertEquals(u, Left(Invalid("y")))

  test("redeemAll handles both channels and can change the error type"):
    for
      fromErr <- runEff(Eff.fail[IO, IoError, Int](Closed).redeemAll(_ => Eff.succeed(-1), a => Eff.succeed(a)))
      fromOk <- runEff(Eff.succeed[IO, IoError, Int](5).redeemAll(_ => Eff.succeed(-1), a => Eff.succeed(a)))
      changed <-
        runEff(Eff.fail[IO, IoError, Int](Closed).redeemAll(e => Eff.fail[IO, AppError, Int](Invalid(e.getMessage)), a => Eff.succeed(a)))
    yield
      assertEquals(fromErr, Right(-1))
      assertEquals(fromOk, Right(5))
      assertEquals(changed, Left(Invalid("closed")))

  test("fold and foldF collapse both channels to the base effect"):
    for
      e <- Eff.fail[IO, IoError, Int](Closed).fold(_.getMessage.length, _ => 0)
      a <- Eff.succeed[IO, IoError, Int](7).fold(_ => -1, identity)
      ef <- Eff.fail[IO, IoError, Int](Closed).foldF(err => IO.pure(err.getMessage.length), v => IO.pure(v))
    yield
      assertEquals(e, 6)
      assertEquals(a, 7)
      assertEquals(ef, 6)

  test("orElseSucceed, orElseFail, valueOr, and alt"):
    val boom: Eff[IO, IoError, Int] = Eff.fail(Closed)
    for
      os <- runEff(boom.orElseSucceed(0))
      of <- runEff(boom.orElseFail(Timeout))
      vo <- runEff(boom.valueOr(_.getMessage.length))
      al <- runEff(boom.alt(Eff.succeed(1)))
      alChange <- runEff(boom.alt(Eff.fail[IO, AppError, Int](Timeout)))
    yield
      assertEquals(os, Right(0))
      assertEquals(of, Left(Timeout))
      assertEquals(vo, Right(6))
      assertEquals(al, Right(1))
      assertEquals(alChange, Left(Timeout))
    end for

  test("tapError and flatTapError observe typed failures; a failing flatTapError replaces the error"):
    for
      tapObs <- IO.ref(Option.empty[String])
      tapR <- runEff(Eff.fail[IO, IoError, Int](Closed).tapError(e => tapObs.set(Some(e.getMessage))))
      tapSeen <- tapObs.get
      ftObs <- IO.ref(Option.empty[String])
      ftR <- runEff(Eff.fail[IO, IoError, Int](Closed).flatTapError(e => Eff.liftF(ftObs.set(Some(e.getMessage)))))
      ftSeen <- ftObs.get
      replaced <- runEff(Eff.fail[IO, IoError, Int](Closed).flatTapError(_ => Eff.fail[IO, IoError, Unit](Failed(1))))
    yield
      assertEquals(tapR, Left(Closed))
      assertEquals(tapSeen, Some("closed"))
      assertEquals(ftR, Left(Closed))
      assertEquals(ftSeen, Some("closed"))
      assertEquals(replaced, Left(Failed(1)))

  test("attemptTap observes the reified result and propagates a failing side effect"):
    for
      seenErr <- IO.ref(Option.empty[Either[IoError, Int]])
      errR <- runEff(Eff.fail[IO, IoError, Int](Closed).attemptTap(ea => Eff.liftF(seenErr.set(Some(ea)))))
      errObs <- seenErr.get
      seenOk <- IO.ref(Option.empty[Either[IoError, Int]])
      okR <- runEff(Eff.succeed[IO, IoError, Int](42).attemptTap(ea => Eff.liftF(seenOk.set(Some(ea)))))
      okObs <- seenOk.get
      prop <- runEff(Eff.succeed[IO, IoError, Int](42).attemptTap(_ => Eff.fail[IO, IoError, Unit](Failed(9))))
    yield
      assertEquals(errR, Left(Closed))
      assertEquals(errObs, Some(Left(Closed)))
      assertEquals(okR, Right(42))
      assertEquals(okObs, Some(Right(42)))
      assertEquals(prop, Left(Failed(9)))

  test("option, collectSome, and collectRight"):
    for
      optS <- runEff(Eff.succeed[IO, IoError, Int](42).option)
      optE <- runEff(Eff.fail[IO, IoError, Int](Closed).option)
      cs <- runEff(Eff.succeed[IO, AppError, Option[Int]](Some(5)).collectSome(Timeout))
      csN <- runEff(Eff.succeed[IO, AppError, Option[Int]](None).collectSome(Timeout))
      cr <- runEff(Eff.succeed[IO, AppError, Either[Int, Int]](Right(9)).collectRight(n => Invalid(n.toString)))
      crL <- runEff(Eff.succeed[IO, AppError, Either[Int, Int]](Left(404)).collectRight(n => Invalid(n.toString)))
    yield
      assertEquals(optS, Right(Some(42)))
      assertEquals(optE, Right(None))
      assertEquals(cs, Right(5))
      assertEquals(csN, Left(Timeout))
      assertEquals(cr, Right(9))
      assertEquals(crL, Left(Invalid("404")))

  test("either reifies the typed channel and eitherT wraps it as EitherT"):
    for
      e <- Eff.succeed[IO, IoError, Int](42).either
      et <- Eff.fail[IO, IoError, Int](Closed).eitherT.value
    yield
      assertEquals(e, Right(42))
      assertEquals(et, Left(Closed))

  test("absolve raises the typed error into F's channel; success passes through"):
    for
      ok <- Eff.succeed[IO, IoError, Int](1).absolve
      ko <- Eff.fail[IO, IoError, Int](Closed).absolve.attempt
    yield
      assertEquals(ok, 1)
      assertEquals(ko.left.toOption, Some(Closed))

  test("a typed error reifies to Left; a defect stays on the F channel"):
    for
      typed <- runEff(Eff.fail[IO, IoError, Int](Closed)).attempt
      defect <- runEff(Eff.liftF[IO, IoError, Int](IO.raiseError(RuntimeException("defect")))).attempt
    yield
      assert(typed.isRight) // IO succeeds carrying Left
      assertEquals(typed.toOption.get, Left(Closed))
      assert(defect.isLeft) // IO fails
      assert(defect.left.exists(_.getMessage == "defect"))

  // --- Concurrency, cancellation, resources -----------------------------------------------------

  test("bracket releases on a typed use failure and skips release when acquire fails"):
    for
      relUse <- IO.ref(false)
      useR <- runEff(Eff.succeed[IO, IoError, Int](42).bracket(_ => Eff.fail[IO, IoError, Int](Closed))(_ => relUse.set(true)))
      usedReleased <- relUse.get
      relAcq <- IO.ref(false)
      acqR <- runEff(Eff.fail[IO, IoError, Int](Failed(1)).bracket(a => Eff.succeed(a))(_ => relAcq.set(true)))
      acqReleased <- relAcq.get
    yield
      assertEquals(useR, Left(Closed))
      assert(usedReleased)
      assertEquals(acqR, Left(Failed(1)))
      assert(!acqReleased)

  test("bracketCase surfaces Succeeded for a value and Errored for a typed use failure"):
    for
      okOc <- IO.ref("")
      r <- runEff(Eff.succeed[IO, IoError, Int](42).bracketCase(a => Eff.succeed(a)) { (_, oc) =>
             oc match
               case Outcome.Succeeded(_) => okOc.set("succeeded")
               case Outcome.Errored(_)   => okOc.set("errored")
               case Outcome.Canceled()   => okOc.set("canceled")
           })
      okSeen <- okOc.get
      errOc <- IO.ref("")
      e <- runEff(Eff.succeed[IO, IoError, Int](42).bracketCase(_ => Eff.fail[IO, IoError, Int](Closed)) { (_, oc) =>
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
    val slowGood = Eff.liftF[IO, IoError, Int](IO.sleep(1.second).flatMap(_ => IO.pure(1)))
    for
      raced <- runEff(Eff.succeed[IO, IoError, Int](1).race(Eff.never[IO, IoError, Int]))
      paired <- runEff(Eff.succeed[IO, IoError, Int](1).both(Eff.succeed(2)))
      failFast <- runEff(slowGood.both(Eff.fail[IO, IoError, Int](Closed)))
    yield
      assertEquals(raced, Right(Left(1)))
      assertEquals(paired, Right((1, 2)))
      assertEquals(failFast, Left(Closed))

  test("start: a successful join is Succeeded, a typed-failure join is Errored"):
    def label(eff: Eff[IO, IoError, Int]): Eff[IO, IoError, String] =
      for
        fiber <- eff.start
        outcome <- fiber.join
      yield outcome match
        case Outcome.Succeeded(_)              => "succeeded"
        case Outcome.Errored(e) if e eq Closed => "errored(Closed)"
        case Outcome.Errored(_)                => "errored(other)"
        case Outcome.Canceled()                => "canceled"
    for
      ok <- runEff(label(Eff.succeed(42)))
      ko <- runEff(label(Eff.fail(Closed)))
    yield
      assertEquals(ok, Right("succeeded"))
      assertEquals(ko, Right("errored(Closed)"))

  test("background spawns a supervised fibre that completes Succeeded"):
    Eff
      .succeed[IO, IoError, Int](42)
      .background
      .use(join => IO.sleep(10.millis).flatMap(_ => join))
      .map {
        case Outcome.Succeeded(_) => ()
        case other                => fail(s"expected Succeeded, got $other")
      }

  test("timeout fails with the supplied typed error when too slow and passes a fast value"):
    val slow = Eff.liftF[IO, AppError, Int](IO.sleep(1.second).flatMap(_ => IO.pure(1)))
    for
      fast <- runEff(Eff.succeed[IO, AppError, Int](42).timeout(1.second, Timeout))
      slowR <- runEff(slow.timeout(50.millis, Timeout))
    yield
      assertEquals(fast, Right(42))
      assertEquals(slowR, Left(Timeout))

  test("timeoutTo returns the fallback on timeout and the value within duration"):
    val slow = Eff.liftF[IO, IoError, Int](IO.sleep(1.second).flatMap(_ => IO.pure(1)))
    for
      fb <- runEff(slow.timeoutTo(50.millis, Eff.succeed[IO, IoError, Int](42)))
      within <- runEff(Eff.succeed[IO, IoError, Int](42).timeoutTo(1.second, Eff.succeed(0)))
    yield
      assertEquals(fb, Right(42))
      assertEquals(within, Right(42))

  test("delayBy delays execution and andWait waits after it"):
    for
      start <- IO.monotonic
      r1 <- runEff(Eff.succeed[IO, IoError, Int](42).delayBy(10.millis))
      mid <- IO.monotonic
      r2 <- runEff(Eff.succeed[IO, IoError, Int](42).andWait(10.millis))
      end <- IO.monotonic
    yield
      assertEquals(r1, Right(42))
      assertEquals(r2, Right(42))
      assert(clue(mid - start) >= 9.millis) // 1ms tolerance for JS timer imprecision
      assert(clue(end - mid) >= 9.millis)

  test("timed returns the result paired with a non-negative duration"):
    runEff(Eff.succeed[IO, IoError, Int](42).timed).map {
      case Right((dur, value)) =>
        assertEquals(value, 42)
        assert(dur >= 0.nanos)
      case Left(e) => fail(s"unexpected error: $e")
    }

  test("&> and <& run in parallel, discarding the appropriate side, and short-circuit on error"):
    val a = Eff.succeed[IO, IoError, Int](1)
    val b = Eff.succeed[IO, IoError, String]("two")
    for
      r <- runEff(a &> b)
      l <- runEff(a <& b)
      shortR <- runEff(Eff.fail[IO, IoError, Int](Closed) &> b)
    yield
      assertEquals(r, Right("two"))
      assertEquals(l, Right(1))
      assertEquals(shortR, Left(Closed))

  test("onCancel runs its finaliser only on cancellation; guarantee runs on success and error"):
    for
      onCancelRan <- IO.ref(false)
      canceledOc <- Eff.canceled[IO, IoError].onCancel(Eff.liftF(onCancelRan.set(true))).absolve.start.flatMap(_.join)
      onCancelSeen <- onCancelRan.get
      onSuccessRan <- IO.ref(false)
      okR <- runEff(Eff.succeed[IO, IoError, Int](42).onCancel(Eff.liftF(onSuccessRan.set(true))))
      onSuccessSeen <- onSuccessRan.get
      guaranteeRan <- IO.ref(0)
      gOk <- runEff(Eff.succeed[IO, IoError, Int](42).guarantee(Eff.liftF(guaranteeRan.update(_ + 1))))
      gErr <- runEff(Eff.fail[IO, IoError, Int](Closed).guarantee(Eff.liftF(guaranteeRan.update(_ + 1))))
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
      _ <- Eff
             .succeed[IO, IoError, Int](1)
             .guaranteeCase {
               case Outcome.Succeeded(_) => Eff.liftF(onOk.set("succeeded"))
               case Outcome.Errored(_)   => Eff.liftF(onOk.set("errored"))
               case Outcome.Canceled()   => Eff.liftF(onOk.set("canceled"))
             }
             .absolve
             .attempt
      _ <- Eff
             .fail[IO, IoError, Int](Closed)
             .guaranteeCase {
               case Outcome.Succeeded(_) => Eff.liftF(onErr.set("succeeded"))
               case Outcome.Errored(_)   => Eff.liftF(onErr.set("errored"))
               case Outcome.Canceled()   => Eff.liftF(onErr.set("canceled"))
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
      ce <- runEff(Eff.cede[IO, IoError])
      neverR <- Eff.never[IO, IoError, Int].absolve.timeout(50.millis).attempt
    yield
      assertEquals(ce, Right(()))
      assert(neverR.isLeft)

  test("Eff.sleep, monotonic, and realTime read the clock in the Eff context"):
    for
      start <- runEff(Eff.monotonic[IO, IoError])
      _ <- runEff(Eff.sleep[IO, IoError](10.millis))
      end <- runEff(Eff.monotonic[IO, IoError])
      wall <- runEff(Eff.realTime[IO, IoError])
    yield (start, end, wall) match
      case (Right(s), Right(e), Right(w)) =>
        assert(clue(e - s) >= 9.millis) // 1ms tolerance for JS timer imprecision
        assert(w.toMillis > 0)
      case _ => fail("clock reads should all succeed")

  test("Eff.ref and Eff.deferred create concurrency primitives operating in the Eff context"):
    val prog: Eff[IO, IoError, Int] =
      for
        ref <- Eff.ref[IO, IoError, Int](0)
        _ <- ref.update(_ + 10)
        d <- Eff.deferred[IO, IoError, Int]
        _ <- d.complete(5).void
        a <- ref.get
        b <- d.get
      yield a + b
    runEff(prog).map(r => assertEquals(r, Right(15)))

  // --- Traversal --------------------------------------------------------------------------------

  test("traverse short-circuits on the first error and collects successes"):
    for
      ok <- runEff(Eff.traverse[IO, IoError, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2)))
      visited <- IO.ref(0)
      ko <- runEff(Eff.traverse[IO, IoError, Int, Int](List(1, 2, 3)) { n =>
              Eff.liftF[IO, IoError, Unit](visited.update(_ + 1)).flatMap(_ => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n))
            })
      seen <- visited.get
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(seen, 2) // stops after visiting 1 and 2

  test("traverse preserves order for a large collection without stack overflow or quadratic blowup"):
    val n = 5000
    runEff(Eff.traverse[IO, IoError, Int, Int]((1 to n).toList)(Eff.succeed(_)))
      .map(r => assertEquals(r, Right((1 to n).toList)))

  test("sequence collects; traverse_ and sequence_ run for effect and discard"):
    for
      seq <- runEff(Eff.sequence(List(Eff.succeed[IO, IoError, Int](1), Eff.succeed[IO, IoError, Int](2), Eff.succeed[IO, IoError, Int](3))))
      sum <- IO.ref(0)
      tvU <- runEff(Eff.traverse_[IO, IoError, Int, Unit](List(1, 2, 3))(n => Eff.liftF(sum.update(_ + n))))
      total <- sum.get
      seqU <- runEff(Eff.sequence_(List(Eff.succeed[IO, IoError, Int](1), Eff.succeed[IO, IoError, Int](2))))
    yield
      assertEquals(seq, Right(List(1, 2, 3)))
      assertEquals(tvU, Right(()))
      assertEquals(total, 6)
      assertEquals(seqU, Right(()))

  test("parTraverse runs in parallel and short-circuits; parSequence collects"):
    for
      ok <- runEff(Eff.parTraverse[IO, IoError, Int, Int](List(1, 2, 3))(n => Eff.succeed(n * 2)))
      ko <- runEff(Eff.parTraverse[IO, IoError, Int, Int](List(1, 2, 3))(n => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n)))
      ps <- runEff(Eff.parSequence(List(Eff.succeed[IO, IoError, Int](1), Eff.succeed[IO, IoError, Int](2))))
      psErr <-
        runEff(Eff.parSequence(List(Eff.succeed[IO, IoError, Int](1), Eff.fail[IO, IoError, Int](Closed), Eff.succeed[IO, IoError, Int](3))))
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(ps, Right(List(1, 2)))
      assertEquals(psErr, Left(Closed))

  test("parTraverse_ and parSequence_ run all in parallel, discard, and propagate a typed error"):
    for
      ok <- runEff(Eff.parTraverse_[IO, IoError, Int, Int](List(1, 2, 3))(Eff.succeed(_)))
      ko <- runEff(Eff.parTraverse_[IO, IoError, Int, Int](List(1, 2, 3))(n => if n == 2 then Eff.fail(Failed(n)) else Eff.succeed(n)))
      ps <- runEff(Eff.parSequence_(List(Eff.succeed[IO, IoError, Int](1), Eff.succeed[IO, IoError, Int](2))))
    yield
      assertEquals(ok, Right(()))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(ps, Right(()))

  test("retry re-runs a failing effect up to the limit, then propagates the final error"):
    for
      attempts <- IO.ref(0)
      eff = Eff.liftF[IO, IoError, Int](attempts.updateAndGet(_ + 1)).flatMap(n => if n < 3 then Eff.fail(Failed(n)) else Eff.succeed(n))
      r <- runEff(Eff.retry(eff, 5))
      count <- attempts.get
      exhausted <- IO.ref(0)
      eff2 = Eff.liftF[IO, IoError, Int](exhausted.updateAndGet(_ + 1)).flatMap(_ => Eff.fail[IO, IoError, Int](Closed))
      r2 <- runEff(Eff.retry(eff2, 3))
      count2 <- exhausted.get
    yield
      assertEquals(r, Right(3))
      assertEquals(count, 3)
      assertEquals(r2, Left(Closed))
      assertEquals(count2, 4) // 1 initial + 3 retries

  test("retryWithBackoff succeeds after transient failures"):
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.retryWithBackoff(
      Eff.liftF[IO, IoError, Unit](IO(attempts += 1)).flatMap(_ => if attempts < 3 then Eff.fail(Failed(attempts)) else Eff.succeed(42)),
      maxRetries = 5,
      initialDelay = 1.millis,
      maxDelay = Some(10.millis)
    )
    runEff(eff).map { r =>
      assertEquals(r, Right(42))
      assertEquals(attempts, 3)
    }

  test("retryWithBackoff caps the delay at maxDelay"):
    // Cap at 1ms with a 10ms initial delay: uncapped the delays would be 10+20+40 = 70ms; capped they
    // are 1+1+1 = 3ms. Assert the elapsed time stays well under the uncapped budget.
    var attempts = 0 // scalafix:ok DisableSyntax.var
    val eff = Eff.retryWithBackoff(
      Eff.liftF[IO, IoError, Unit](IO(attempts += 1)).flatMap(_ => Eff.fail[IO, IoError, Int](Closed)),
      maxRetries = 3,
      initialDelay = 10.millis,
      maxDelay = Some(1.millis)
    )
    for
      start <- IO.monotonic
      result <- runEff(eff)
      end <- IO.monotonic
    yield
      assertEquals(result, Left(Closed))
      assertEquals(attempts, 4) // 1 initial + 3 retries
      assert(clue(end - start) < 60.millis)

  // --- async ------------------------------------------------------------------------------------

  test("async completes with a typed success or failure via the callback"):
    val ok = Eff.async[IO, AppError, Int] { cb => cb(Right(7)); IO.pure(None) }
    val ko = Eff.async[IO, AppError, Int] { cb => cb(Left(NotFound("x"))); IO.pure(None) }
    for
      o <- runEff(ok)
      k <- runEff(ko)
    yield
      assertEquals(o, Right(7))
      assertEquals(k, Left(NotFound("x")))

  test("asyncAttempt folds a raised defect but preserves a typed callback error"):
    val folded = Eff.asyncAttempt[IO, AppError, Int](t => Invalid(t.getMessage))(_ => IO.raiseError(RuntimeException("boom")))
    val typed = Eff.asyncAttempt[IO, AppError, Int](_ => Timeout) { cb => cb(Left(NotFound("y"))); IO.pure(None) }
    for
      f <- runEff(folded)
      t <- runEff(typed)
    yield
      assertEquals(f, Left(Invalid("boom")))
      assertEquals(t, Left(NotFound("y")))

  test("fromFuture converts a successful future and translates a failed one"):
    val ok = Eff.fromFuture(IO(Future.successful(42)), t => Invalid(t.getMessage))
    val ko = Eff.fromFuture(IO(Future.failed[Int](RuntimeException("boom"))), t => Invalid(t.getMessage))
    for
      o <- runEff(ok)
      k <- runEff(ko)
    yield
      assertEquals(o, Right(42))
      assertEquals(k, Left(Invalid("boom")))

  // --- Instances (via cats combinators) and lifting ---------------------------------------------

  test("the summoned MonadError instance handles the typed error channel"):
    val F = summon[cats.MonadError[Eff.Of[IO, IoError], IoError]]
    runEff(F.handleError(F.raiseError[Int](Closed))(_.getMessage.length))
      .map(r => assertEquals(r, Right(6)))

  test("the summoned GenConcurrent instance runs a concurrent program"):
    val F = summon[cats.effect.kernel.GenConcurrent[Eff.Of[IO, IoError], Throwable]]
    val program =
      for
        ref <- F.ref(0)
        _ <- ref.update(_ + 1)
        v <- ref.get
      yield v
    runEff(program).map(r => assertEquals(r, Right(1)))

  test("the summoned GenTemporal instance raises TimeoutException on the defect channel"):
    val T = summon[cats.effect.kernel.GenTemporal[Eff.Of[IO, IoError], Throwable]]
    val slow = T.flatMap(T.sleep(1.second))(_ => T.pure(42))
    // A timeout is a defect (TimeoutException in F's channel), not the typed IoError channel.
    T.timeout(slow, 10.millis).absolve.attempt.map {
      case Left(_: java.util.concurrent.TimeoutException) => ()
      case Right(_)                                       => fail("should have timed out")
      case Left(e)                                        => fail(s"wrong error type: ${e.getClass.getName}")
    }

  test("the Parallel instance enables parMapN and short-circuits on error"):
    for
      ok <- runEff((Eff.succeed[IO, IoError, Int](1), Eff.succeed[IO, IoError, Int](2)).parMapN(_ + _))
      ko <- runEff((Eff.succeed[IO, IoError, Int](1), Eff.fail[IO, IoError, Int](Closed)).parMapN(_ + _))
    yield
      assertEquals(ok, Right(3))
      assertEquals(ko, Left(Closed))

  test("widenK widens the error type and functionK lifts a plain F value as a success"):
    for
      widened <- runEff(Eff.widenK[IO, NotFound, AppError](Eff.fail[IO, NotFound, Int](NotFound("u1"))))
      lifted <- runEff(Eff.functionK[IO, IoError](IO.pure(7)))
    yield
      assertEquals(widened, Left(NotFound("u1")))
      assertEquals(lifted, Right(7))

  test("liftRef and liftResource operate in the Eff context and the resource is released"):
    for
      released <- IO.ref(false)
      resource = Resource.make(IO.pure(21))(_ => released.set(true))
      r <- runEff {
             for
               ref <- Eff.liftF[IO, IoError, Ref[IO, Int]](IO.ref(0)).map(Eff.liftRef[IO, IoError, Int])
               _ <- ref.set(5)
               v <- ref.get
               used <- Eff.liftResource[IO, IoError, Int](resource).use(n => Eff.succeed(n * 2))
             yield v + used
           }
      wasReleased <- released.get
    yield
      assertEquals(r, Right(5 + 42))
      assert(wasReleased)

  test("liftCell.evalModify leaves the cell unchanged on a typed failure"):
    for
      cell <- AtomicCell[IO].of(0)
      lifted = Eff.liftCell[IO, IoError, Int](cell)
      result <- runEff(lifted.evalModify(_ => Eff.fail[IO, IoError, (Int, Int)](Closed)))
      value <- lifted.get.absolve
    yield
      assertEquals(result, Left(Closed))
      assertEquals(value, 0) // rolled back
end EffSuite
