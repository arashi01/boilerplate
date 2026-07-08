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
import scala.reflect.TypeTest
import scala.util.Failure
import scala.util.Success

import cats.effect.*
import cats.effect.kernel.Outcome
import cats.effect.std.Supervisor
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IoError.*

/** Behavioural tests for `EffIO` - the covariant, `IO`-specialised typed-error effect, a phantom
  * over `IO`'s own `Throwable` channel. Typeclass lawfulness is verified separately by the
  * discipline suite. These cover the phantom-specific contracts: a typed failure IS an `IO` failure
  * (`Outcome.Errored`), observation filters by `TypeTest` and never swallows a defect, `absolve` is
  * a no-op, and covariant `E` widening / union narrowing distinguish `EffIO` from a plain error
  * monad.
  */
class EffIOSuite extends CatsEffectSuite:
  private def run[E <: Throwable, A](eff: EffIO[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] = eff.either

  // --- Constructors -----------------------------------------------------------------------------

  test("succeed lands in the success channel, fail in IO's Throwable channel"):
    for
      s <- run(EffIO.succeed(1))
      f <- run(EffIO.fail(Closed))
    yield
      assertEquals(s, Right(1))
      assertEquals(f, Left(Closed))

  test("from Either, Option, Try, and EitherT"):
    for
      e <- run(EffIO.from(Right(1): Either[AppError, Int]))
      oS <- run(EffIO.from(Some(2), Timeout))
      oN <- run(EffIO.from(None: Option[Int], Timeout))
      tS <- run(EffIO.from(Success(3), t => Invalid(t.getMessage)))
      tF <- run(EffIO.from(Failure(RuntimeException("t")), t => Invalid(t.getMessage)))
    yield
      assertEquals(e, Right(1))
      assertEquals(oS, Right(2))
      assertEquals(oN, Left(Timeout))
      assertEquals(tS, Right(3))
      assertEquals(tF, Left(Invalid("t")))

  test("lift(IO[Either]) absorbs a Left into IO's channel; lift(IO[Option]) supplies ifNone"):
    for
      l <- run(EffIO.lift(IO.pure(Left(Closed): Either[IoError, Int])))
      lo <- run(EffIO.lift(IO.pure(None: Option[Int]), Closed))
    yield
      assertEquals(l, Left(Closed))
      assertEquals(lo, Left(Closed))

  test("attempt(io, ifFailure) translates any thrown throwable to a typed error"):
    for
      ok <- run(EffIO.attempt(IO.pure(1), t => Invalid(t.getMessage)))
      ko <- run(EffIO.attempt(IO.raiseError(RuntimeException("x")), t => Invalid(t.getMessage)))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left(Invalid("x")))

  test("attempt(pf) catches matching throwables and lets unmatched propagate as a defect"):
    val matched = EffIO.attempt(IO.raiseError[Int](IllegalArgumentException("bad"))):
      case _: IllegalArgumentException => Invalid("bad")
    val unmatched = EffIO.attempt(IO.raiseError[Int](RuntimeException("untouched"))):
      case _: IllegalArgumentException => Invalid("bad")
    for
      m <- run(matched)
      u <- unmatched.absolve.attempt
    yield
      assertEquals(m, Left(Invalid("bad")))
      assert(u.left.exists(_.getMessage == "untouched"))

  test("suspend defers a side effect until run"):
    var count = 0 // scalafix:ok DisableSyntax.var
    val eff = EffIO.suspend(count += 1)
    assertEquals(count, 0)
    run(eff).map(_ => assertEquals(count, 1))

  test("cond evaluates only the selected branch"):
    var trueSide = 0 // scalafix:ok DisableSyntax.var
    var falseSide = 0 // scalafix:ok DisableSyntax.var
    for
      okR <- run(EffIO.cond(true, { trueSide += 1; 42 }, { falseSide += 1; Timeout }))
      koR <- run(EffIO.cond(false, { trueSide += 1; 42 }, { falseSide += 1; Timeout }))
    yield
      assertEquals(okR, Right(42))
      assertEquals(koR, Left(Timeout))
      assertEquals(trueSide, 1)
      assertEquals(falseSide, 1)

  test("raiseWhen/raiseUnless raise only on the intended condition"):
    for
      rw <- run(EffIO.raiseWhen(true)(Timeout))
      rwN <- run(EffIO.raiseWhen(false)(Timeout))
      ru <- run(EffIO.raiseUnless(false)(Timeout))
    yield
      assertEquals(rw, Left(Timeout))
      assertEquals(rwN, Right(()))
      assertEquals(ru, Left(Timeout))

  // --- Covariance in E and A --------------------------------------------------------------------

  test("a narrow error widens to a broad error with no call-site method"):
    val narrow: EffIO[NotFound, Int] = EffIO.fail(NotFound("u1"))
    val wide: EffIO[AppError, Int] = narrow
    run(wide).map(r => assertEquals(r, Left(NotFound("u1"))))

  test("a narrow success widens to a broad success with no call-site method"):
    val narrow: EffIO[AppError, NotFound] = EffIO.succeed(NotFound("u1"))
    val wide: EffIO[AppError, AppError] = narrow
    run(wide).map(r => assertEquals(r, Right(NotFound("u1"))))

  test("a for-comprehension over distinct error types infers their union"):
    def find(id: String): EffIO[NotFound, Int] =
      if id == "1" then EffIO.succeed(1) else EffIO.fail(NotFound(id))
    def validate(n: Int): EffIO[Invalid, Int] =
      if n > 0 then EffIO.succeed(n) else EffIO.fail(Invalid("non-positive"))
    val workflow: EffIO[NotFound | Invalid, Int] =
      for
        n <- find("1")
        v <- validate(n)
      yield v
    run(workflow).map(r => assertEquals(r, Right(1)))

  test("infallible and fallible EffIO compose in one for-comprehension"):
    val workflow: EffIO[NotFound, Int] =
      for
        base <- EffIO.succeed(100) // UEffIO[Int]
        found <- EffIO.succeed(1): EffIO[NotFound, Int]
      yield base + found
    run(workflow).map(r => assertEquals(r, Right(101)))

  // --- Mapping ----------------------------------------------------------------------------------

  test("map and flatMap act on success and short-circuit on a typed failure"):
    for
      m <- run(EffIO.succeed(10).map(_ + 1))
      fm <- run(EffIO.succeed(10).flatMap(n => EffIO.succeed(n * 2)))
      skip <- run((EffIO.fail(Closed): EffIO[IoError, Int]).map(_ => 0))
    yield
      assertEquals(m, Right(11))
      assertEquals(fm, Right(20))
      assertEquals(skip, Left(Closed))

  test("semiflatMap, subflatMap, and transform"):
    for
      sf <- run(EffIO.succeed(2).semiflatMap(n => IO.pure(n * 10)))
      sub <- run(EffIO.succeed(2).subflatMap(n => Right(n + 1): Either[AppError, Int]))
      tr <- run(EffIO.succeed(2).transform(_ => Left(Timeout): Either[AppError, Int]))
    yield
      assertEquals(sf, Right(20))
      assertEquals(sub, Right(3))
      assertEquals(tr, Left(Timeout))

  // --- Recovery ---------------------------------------------------------------------------------

  test("catchAll recovers from a typed error"):
    run((EffIO.fail(Closed): EffIO[IoError, Int]).catchAll(e => EffIO.succeed(e.getMessage.length)))
      .map(r => assertEquals(r, Right(6)))

  test("catchAll does not swallow a defect - a non-E throwable re-raises unchanged"):
    val withDefect: EffIO[IoError, Int] = EffIO.liftF(IO.raiseError[Int](RuntimeException("boom")))
    withDefect.catchAll(_ => EffIO.succeed(0)).absolve.attempt.map { r =>
      assert(r.left.exists(_.getMessage == "boom"))
    }

  test("mapError transforms the typed channel and leaves a defect untouched"):
    for
      mapped <- run((EffIO.fail(Closed): EffIO[IoError, Int]).mapError(e => Invalid(e.getMessage)))
      defect <-
        (EffIO.liftF(IO.raiseError[Int](RuntimeException("boom"))): EffIO[IoError, Int])
          .mapError(e => Invalid(e.getMessage))
          .absolve
          .attempt
    yield
      assertEquals(mapped, Left(Invalid("closed")))
      assert(defect.left.exists(_.getMessage == "boom"))

  test("mapErrorPartial transforms matched errors and passes others through"):
    val f: EffIO[AppError, Int] => EffIO[AppError, Int] =
      _.mapErrorPartial { case _: NotFound => Timeout }
    for
      m <- run(f(EffIO.fail(NotFound("x"))))
      u <- run(f(EffIO.fail(Invalid("y"))))
    yield
      assertEquals(m, Left(Timeout))
      assertEquals(u, Left(Invalid("y")))

  test("catchSome recovers matched errors and passes unmatched through"):
    val f: EffIO[AppError, Int] => EffIO[AppError, Int] =
      _.catchSome { case _: NotFound => EffIO.succeed(1) }
    for
      m <- run(f(EffIO.fail(NotFound("x"))))
      u <- run(f(EffIO.fail(Invalid("y"))))
    yield
      assertEquals(m, Right(1))
      assertEquals(u, Left(Invalid("y")))

  test("catchOnly handles one union arm and narrows the residual at compile time"):
    // The `EffIO[IoError, Int]` ascriptions are load-bearing: they assert the residual is narrowed.
    val onApp: EffIO[IoError | AppError, Int] = EffIO.fail(NotFound("u1"))
    val onIo: EffIO[IoError | AppError, Int] = EffIO.fail(Failed(500))
    val recovered: EffIO[IoError, Int] = onApp.catchOnly((_: AppError) => EffIO.succeed(-1))
    val residual: EffIO[IoError, Int] = onIo.catchOnly((_: AppError) => EffIO.succeed(-1))
    for
      r <- run(recovered)
      s <- run(residual)
    yield
      assertEquals(r, Right(-1))
      assertEquals(s, Left(Failed(500)))

  test("catchOnly lets the handler re-fail into the residual channel"):
    val onApp: EffIO[IoError | AppError, Int] = EffIO.fail(Invalid("bad"))
    // Re-failing into the residual: ascribe to the residual root so it is not inferred too narrowly.
    val narrowed: EffIO[IoError, Int] = onApp.catchOnly((_: AppError) => EffIO.fail[IoError](Closed))
    run(narrowed).map(r => assertEquals(r, Left(Closed)))

  test("redeemAll handles both channels with a new error type"):
    for
      fromErr <- run((EffIO.fail(Closed): EffIO[IoError, Int]).redeemAll(_ => EffIO.succeed(-1), a => EffIO.succeed(a)))
      fromOk <- run(EffIO.succeed(5).redeemAll(_ => EffIO.succeed(-1), a => EffIO.succeed(a)))
    yield
      assertEquals(fromErr, Right(-1))
      assertEquals(fromOk, Right(5))

  test("fold collapses both channels to a base IO"):
    for
      e <- (EffIO.fail(Closed): EffIO[IoError, Int]).fold(_.getMessage.length, _ => 0)
      a <- EffIO.succeed(7).fold(_ => -1, identity)
    yield
      assertEquals(e, 6)
      assertEquals(a, 7)

  test("orElseSucceed, orElseFail, valueOr, and alt"):
    val boom: EffIO[IoError, Int] = EffIO.fail(Closed)
    for
      os <- run(boom.orElseSucceed(0))
      of <- run(boom.orElseFail(Timeout))
      vo <- run(boom.valueOr(_.getMessage.length))
      al <- run(boom.alt(EffIO.succeed(1)))
    yield
      assertEquals(os, Right(0))
      assertEquals(of, Left(Timeout))
      assertEquals(vo, Right(6))
      assertEquals(al, Right(1))

  test("tapError observes typed failures without altering them"):
    for
      observed <- IO.ref(Option.empty[String])
      r <- run((EffIO.fail(Closed): EffIO[IoError, Int]).tapError(e => observed.set(Some(e.getMessage))))
      seen <- observed.get
    yield
      assertEquals(r, Left(Closed))
      assertEquals(seen, Some("closed"))

  test("absolve raises the typed error into IO's channel; success passes through"):
    val boom = Failed(1)
    for
      ok <- (EffIO.succeed(1): EffIO[IoError, Int]).absolve
      ko <- (EffIO.fail(boom): EffIO[IoError, Int]).absolve.attempt
    yield
      assertEquals(ok, 1)
      assertEquals(ko.left.toOption, Some(boom))

  test("option, collectSome, and collectRight"):
    for
      opt <- run((EffIO.fail(Closed): EffIO[IoError, Int]).option)
      cs <- run(EffIO.succeed(Some(5)).collectSome(Timeout))
      csN <- run(EffIO.succeed(None: Option[Int]).collectSome(Timeout))
      cr <- run(EffIO.succeed(Right(9): Either[Int, Int]).collectRight(n => Invalid(n.toString)))
    yield
      assertEquals(opt, Right(None))
      assertEquals(cs, Right(5))
      assertEquals(csN, Left(Timeout))
      assertEquals(cr, Right(9))

  test("attemptTap observes the reified result without altering the outcome"):
    for
      seen <- IO.ref(Option.empty[Either[IoError, Int]])
      r <- run((EffIO.fail(Closed): EffIO[IoError, Int]).attemptTap(ea => EffIO.liftF(seen.set(Some(ea)))))
      observed <- seen.get
    yield
      assertEquals(r, Left(Closed))
      assertEquals(observed, Some(Left(Closed)))

  // --- Concurrency, cancellation, resources -----------------------------------------------------

  test("bracket releases the resource on a typed use failure"):
    for
      released <- IO.ref(false)
      r <- run((EffIO.succeed(42): EffIO[IoError, Int]).bracket(_ => EffIO.fail(Closed))(_ => released.set(true)))
      wasReleased <- released.get
    yield
      assertEquals(r, Left(Closed))
      assert(wasReleased)

  test("race returns the winner; both returns the pair"):
    for
      raced <- run(EffIO.succeed(1).race(EffIO.never))
      paired <- run(EffIO.succeed(1).both(EffIO.succeed(2)))
    yield
      assertEquals(raced, Right(Left(1)))
      assertEquals(paired, Right((1, 2)))

  test("timeout fails with the supplied typed error when the effect is too slow"):
    run(EffIO.never.timeout(50.millis, Timeout)).map(r => assertEquals(r, Left(Timeout)))

  test("a fibre completing with a typed error is Outcome.Errored, not Succeeded"):
    val eff: EffIO[IoError, Int] =
      for
        fiber <- (EffIO.fail(Closed): EffIO[IoError, Int]).start
        outcome <- fiber.join
      yield outcome match
        case Outcome.Errored(e) if e eq Closed => 1
        case _                                 => 0
    run(eff).map(r => assertEquals(r, Right(1)))

  test("guaranteeCase observes Succeeded for a value and Errored for a typed failure"):
    for
      onOk <- IO.ref("")
      onErr <- IO.ref("")
      _ <- (EffIO.succeed(1): EffIO[IoError, Int])
             .guaranteeCase {
               case Outcome.Succeeded(_) => onOk.set("succeeded")
               case Outcome.Errored(_)   => onOk.set("errored")
               case Outcome.Canceled()   => onOk.set("canceled")
             }
             .absolve
             .attempt
      _ <- (EffIO.fail(Closed): EffIO[IoError, Int])
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

  test("traverse short-circuits on the first error; traverse_ runs for effect and discards"):
    for
      ok <- run(EffIO.traverse(List(1, 2, 3))(n => EffIO.succeed(n * 2)))
      ko <- run(EffIO.traverse(List(1, 2, 3))(n => if n == 2 then EffIO.fail(Failed(n)) else EffIO.succeed(n)))
      counter <- IO.ref(0)
      t <- run(EffIO.traverse_(List(1, 2, 3))(n => EffIO.liftF(counter.update(_ + n))))
      total <- counter.get
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left(Failed(2)))
      assertEquals(t, Right(()))
      assertEquals(total, 6)

  test("parTraverse_ runs in parallel, discards results, and propagates a typed error"):
    for
      ok <- run(EffIO.parTraverse_(List(1, 2, 3))(EffIO.succeed(_)))
      ko <- run(EffIO.parTraverse_(List(1, 2, 3))(n => if n == 2 then EffIO.fail(Failed(n)) else EffIO.succeed(n)))
    yield
      assertEquals(ok, Right(()))
      assertEquals(ko, Left(Failed(2)))

  test("retry re-runs a failing effect up to the limit"):
    for
      attempts <- IO.ref(0)
      eff = EffIO.liftF(attempts.updateAndGet(_ + 1)).flatMap(n => if n < 3 then EffIO.fail(Failed(n)) else EffIO.succeed(n))
      r <- run(EffIO.retry(eff, 5))
      count <- attempts.get
    yield
      assertEquals(r, Right(3))
      assertEquals(count, 3)

  // --- async ------------------------------------------------------------------------------------

  test("async completes with a typed success or failure via the callback"):
    val ok = EffIO.async[AppError, Int] { cb => cb(Right(7)); IO.pure(None) }
    val ko = EffIO.async[AppError, Int] { cb => cb(Left(NotFound("x"))); IO.pure(None) }
    for
      o <- run(ok)
      k <- run(ko)
    yield
      assertEquals(o, Right(7))
      assertEquals(k, Left(NotFound("x")))

  test("asyncAttempt folds a raised defect but preserves a typed callback error"):
    val folded = EffIO.asyncAttempt[AppError, Int](t => Invalid(t.getMessage))(_ => IO.raiseError(RuntimeException("boom")))
    val typed = EffIO.asyncAttempt[AppError, Int](_ => Timeout) { cb => cb(Left(NotFound("y"))); IO.pure(None) }
    for
      f <- run(folded)
      t <- run(typed)
    yield
      assertEquals(f, Left(Invalid("boom")))
      assertEquals(t, Left(NotFound("y")))

  // --- Instances and lifting --------------------------------------------------------------------

  test("the summoned GenConcurrent instance runs a concurrent program"):
    val F = summon[cats.effect.kernel.GenConcurrent[EffIO.Of[AppError], Throwable]]
    val program =
      for
        ref <- F.ref(0)
        _ <- ref.update(_ + 1)
        v <- ref.get
      yield v
    run(program).map(r => assertEquals(r, Right(1)))

  test("the summoned MonadError instance handles the typed error channel"):
    val F = summon[cats.MonadError[EffIO.Of[IoError], IoError]]
    run(F.handleError(F.raiseError[Int](Closed))(_.getMessage.length))
      .map(r => assertEquals(r, Right(6)))

  test("lifted Ref, Queue, and Resource operate in the EffIO context"):
    for
      released <- IO.ref(false)
      resource = Resource.make(IO.pure(21))(_ => released.set(true))
      r <- run {
             for
               ref <- EffIO.liftF(IO.ref(0)).map(EffIO.liftRef[IoError, Int])
               _ <- ref.set(5)
               queue <- EffIO.liftF(cats.effect.std.Queue.unbounded[IO, Int]).map(EffIO.liftQueue[IoError, Int])
               _ <- queue.offer(ref.hashCode)
               v <- ref.get
               used <- EffIO.liftResource[IoError, Int](resource).use(n => EffIO.succeed(n * 2))
             yield v + used
           }
      wasReleased <- released.get
    yield
      assertEquals(r, Right(5 + 42))
      assert(wasReleased)

  test("liftSupervisor supervises an EffIO fibre"):
    Supervisor[IO](await = true).use { sup =>
      val lifted = EffIO.liftSupervisor[IoError](sup)
      val eff =
        for
          fiber <- lifted.supervise(EffIO.succeed(123): EffIO[IoError, Int])
          outcome <- fiber.join
        yield outcome match
          case Outcome.Succeeded(_) => 123
          case _                    => -1
      run(eff).map(r => assertEquals(r, Right(123)))
    }

  test("widenK is the identity error-widening transformation; liftK lifts plain IO"):
    val k = EffIO.widenK[NotFound, AppError]
    for
      widened <- run(k(EffIO.fail(NotFound("u1"))))
      lifted <- run(EffIO.liftK(IO.pure(7)))
    yield
      assertEquals(widened, Left(NotFound("u1")))
      assertEquals(lifted, Right(7))

  test("toEff then fromEff is a zero-cost identity round-trip"):
    val eff = EffIO.succeed(42)
    run(EffIO.fromEff(eff.toEff)).map(r => assertEquals(r, Right(42)))
end EffIOSuite
