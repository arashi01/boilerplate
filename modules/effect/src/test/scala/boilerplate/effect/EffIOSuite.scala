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
import scala.util.Failure
import scala.util.Success

import cats.effect.*
import cats.effect.kernel.Outcome
import cats.effect.std.Supervisor
import munit.CatsEffectSuite

/** Test suite for `EffIO` - the covariant, `IO`-specialised typed error effect.
  *
  * Typeclass-instance lawfulness is verified separately by the discipline-law suite. These tests
  * cover constructors, combinators, conversions, the natural transformations, primitive lifts, and
  *   - above all - the covariant error widening that distinguishes `EffIO` from `Eff`.
  */
class EffIOSuite extends CatsEffectSuite:
  private def run[E, A](eff: EffIO[E, A]): IO[Either[E, A]] = eff.either

  // Error ADT exercising a genuine subtype lattice for the covariance tests.
  sealed trait AppError derives CanEqual
  final case class NotFound(id: String) extends AppError
  final case class Invalid(reason: String) extends AppError

  // ===========================================================================
  // Type aliases
  // ===========================================================================

  test("UEffIO is the infallible effect"):
    val eff: UEffIO[Int] = EffIO.succeed(42)
    run(eff).map(r => assertEquals(r, Right(42)))

  test("TEffIO is the Throwable-errored effect"):
    val boom = RuntimeException("boom")
    val eff: TEffIO[Int] = EffIO.fail(boom)
    run(eff).map(r => assertEquals(r, Left(boom)))

  // ===========================================================================
  // Covariance - the defining feature
  // ===========================================================================

  test("a narrow error widens to a broad error with no call-site method"):
    val narrow: EffIO[NotFound, Int] = EffIO.fail(NotFound("u1"))
    val wide: EffIO[AppError, Int] = narrow
    run(wide).map(r => assertEquals(r, Left(NotFound("u1"))))

  test("a narrow success widens to a broad success with no call-site method"):
    val narrow: EffIO[String, NotFound] = EffIO.succeed(NotFound("u1"))
    val wide: EffIO[String, AppError] = narrow
    run(wide).map(r => assertEquals(r, Right(NotFound("u1"))))

  test("for-comprehension unifies distinct error types without widenError"):
    def find(id: String): EffIO[NotFound, Int] =
      if id == "1" then EffIO.succeed(1) else EffIO.fail(NotFound(id))
    def validate(n: Int): EffIO[Invalid, Int] =
      if n > 0 then EffIO.succeed(n) else EffIO.fail(Invalid("non-positive"))

    val workflow: EffIO[AppError, Int] =
      for
        n <- find("1")
        v <- validate(n)
      yield v
    run(workflow).map(r => assertEquals(r, Right(1)))

  test("infallible and fallible EffIO compose in one for-comprehension"):
    def find(id: String): EffIO[NotFound, Int] =
      if id == "1" then EffIO.succeed(1) else EffIO.fail(NotFound(id))
    val workflow: EffIO[NotFound, Int] =
      for
        base <- EffIO.succeed(100) // UEffIO[Int], infallible
        found <- find("1") // EffIO[NotFound, Int], fallible
      yield base + found
    run(workflow).map(r => assertEquals(r, Right(101)))

  // ===========================================================================
  // Conversions to and from Eff
  // ===========================================================================

  test("toEff then fromEff round-trips to an equal value"):
    val eff = EffIO.succeed(42)
    run(EffIO.fromEff(eff.toEff)).map(r => assertEquals(r, Right(42)))

  test("fromEff reinterprets an Eff[IO, E, A] without recomputation"):
    val source: Eff[IO, String, Int] = Eff.succeed[IO, String, Int](7)
    run(EffIO.fromEff(source)).map(r => assertEquals(r, Right(7)))

  // ===========================================================================
  // Constructors
  // ===========================================================================

  test("succeed and fail"):
    for
      s <- run(EffIO.succeed(1))
      f <- run(EffIO.fail("boom"))
    yield
      assertEquals(s, Right(1))
      assertEquals(f, Left("boom"))

  test("from Either, Option, Try, EitherT"):
    for
      e <- run(EffIO.from(Right(1): Either[String, Int]))
      oS <- run(EffIO.from(Some(2), "none"))
      oN <- run(EffIO.from(None: Option[Int], "none"))
      tS <- run(EffIO.from(Success(3), _.getMessage))
      tF <- run(EffIO.from(Failure(RuntimeException("t")), _.getMessage))
    yield
      assertEquals(e, Right(1))
      assertEquals(oS, Right(2))
      assertEquals(oN, Left("none"))
      assertEquals(tS, Right(3))
      assertEquals(tF, Left("t"))

  test("lift, liftF, and lift of IO[Option]"):
    for
      l <- run(EffIO.lift(IO.pure(Right(1): Either[String, Int])))
      lf <- run(EffIO.liftF(IO.pure(2)))
      lo <- run(EffIO.lift(IO.pure(None: Option[Int]), "none"))
    yield
      assertEquals(l, Right(1))
      assertEquals(lf, Right(2))
      assertEquals(lo, Left("none"))

  test("unit is the canonical successful Unit"):
    run(EffIO.unit).map(r => assertEquals(r, Right(())))

  test("attempt captures a thrown Throwable as a typed error"):
    for
      ok <- run(EffIO.attempt(IO.pure(1), _.getMessage))
      ko <- run(EffIO.attempt(IO.raiseError(RuntimeException("x")), _.getMessage))
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left("x"))

  test("suspend defers evaluation of a side effect"):
    var count = 0 // scalafix:ok DisableSyntax.var
    val eff = EffIO.suspend(count += 1)
    val before = count
    run(eff).map { _ =>
      assertEquals(before, 0)
      assertEquals(count, 1)
    }

  test("defer suspends an EffIO thunk until it is run"):
    var count = 0 // scalafix:ok DisableSyntax.var
    val eff = EffIO.defer { count += 1; EffIO.succeed(count) }
    val before = count
    run(eff).map { r =>
      assertEquals(before, 0)
      assertEquals(r, Right(1))
      assertEquals(count, 1)
    }

  test("conditional constructors when/unless/raiseWhen/raiseUnless"):
    for
      w <- run(EffIO.when(true)(EffIO.fail("ran")))
      u <- run(EffIO.unless(true)(EffIO.fail("ran")))
      rw <- run(EffIO.raiseWhen(true)("raised"))
      ru <- run(EffIO.raiseUnless(false)("raised"))
    yield
      assertEquals(w, Left("ran"))
      assertEquals(u, Right(()))
      assertEquals(rw, Left("raised"))
      assertEquals(ru, Left("raised"))

  test("traverse short-circuits on the first error"):
    for
      ok <- run(EffIO.traverse(List(1, 2, 3))(n => EffIO.succeed(n * 2)))
      ko <- run(EffIO.traverse(List(1, 2, 3))(n => if n == 2 then EffIO.fail(s"at $n") else EffIO.succeed(n)))
    yield
      assertEquals(ok, Right(List(2, 4, 6)))
      assertEquals(ko, Left("at 2"))

  test("sequence and parSequence collect successes"):
    val effs = List(EffIO.succeed(1), EffIO.succeed(2), EffIO.succeed(3))
    for
      s <- run(EffIO.sequence(effs))
      p <- run(EffIO.parSequence(effs))
    yield
      assertEquals(s, Right(List(1, 2, 3)))
      assertEquals(p, Right(List(1, 2, 3)))

  test("retry re-runs a failing effect up to the limit"):
    for
      attempts <- IO.ref(0)
      eff = EffIO.liftF(attempts.updateAndGet(_ + 1)).flatMap(n => if n < 3 then EffIO.fail("retry") else EffIO.succeed(n))
      r <- run(EffIO.retry(eff, 5))
      count <- attempts.get
    yield
      assertEquals(r, Right(3))
      assertEquals(count, 3)

  // ===========================================================================
  // Combinators
  // ===========================================================================

  test("map and flatMap operate on the success channel"):
    for
      m <- run(EffIO.succeed(10).map(_ + 1))
      fm <- run(EffIO.succeed(10).flatMap(n => EffIO.succeed(n * 2)))
      skip <- run(EffIO.fail[String]("boom").map(_ => 0))
    yield
      assertEquals(m, Right(11))
      assertEquals(fm, Right(20))
      assertEquals(skip, Left("boom"))

  test("catchAll recovers from a typed error"):
    for r <- run(EffIO.fail[String]("boom").catchAll(e => EffIO.succeed(e.length)))
    yield assertEquals(r, Right(4))

  test("mapError transforms the error channel"):
    run(EffIO.fail[String]("boom").mapError(_.toUpperCase)).map(r => assertEquals(r, Left("BOOM")))

  test("mapErrorPartial transforms matched errors and passes others through"):
    val matched = EffIO.fail[String]("known").mapErrorPartial { case "known" => "KNOWN" }
    val unmatched = EffIO.fail[String]("other").mapErrorPartial { case "known" => "KNOWN" }
    for
      m <- run(matched)
      u <- run(unmatched)
    yield
      assertEquals(m, Left("KNOWN"))
      assertEquals(u, Left("other"))

  test("redeemAll handles both channels with a new error type"):
    for
      fromErr <- run(EffIO.fail[String]("e").redeemAll(_ => EffIO.succeed(-1), a => EffIO.succeed(a)))
      fromOk <- run(EffIO.succeed(5).redeemAll(_ => EffIO.succeed(-1), a => EffIO.succeed(a)))
    yield
      assertEquals(fromErr, Right(-1))
      assertEquals(fromOk, Right(5))

  test("fold collapses both channels to a base IO"):
    for
      e <- EffIO.fail[String]("boom").fold(_.length, _ => 0)
      a <- EffIO.succeed(7).fold(_ => -1, identity)
    yield
      assertEquals(e, 4)
      assertEquals(a, 7)

  test("orElseSucceed, orElseFail, valueOr, alt"):
    for
      os <- run(EffIO.fail[String]("x").orElseSucceed(0))
      of <- run(EffIO.fail[String]("x").orElseFail(99))
      vo <- run(EffIO.fail[String]("boom").valueOr(_.length))
      al <- run(EffIO.fail[String]("x").alt(EffIO.succeed(1)))
    yield
      assertEquals(os, Right(0))
      assertEquals(of, Left(99))
      assertEquals(vo, Right(4))
      assertEquals(al, Right(1))

  test("tapError observes failures without altering them"):
    for
      observed <- IO.ref(Option.empty[String])
      r <- run(EffIO.fail[String]("boom").tapError(e => observed.set(Some(e))))
      seen <- observed.get
    yield
      assertEquals(r, Left("boom"))
      assertEquals(seen, Some("boom"))

  test("semiflatMap, subflatMap, transform"):
    for
      sf <- run(EffIO.succeed(2).semiflatMap(n => IO.pure(n * 10)))
      sub <- run(EffIO.succeed(2).subflatMap(n => Right(n + 1): Either[String, Int]))
      tr <- run(EffIO.succeed(2).transform(_ => Left("forced"): Either[String, Int]))
    yield
      assertEquals(sf, Right(20))
      assertEquals(sub, Right(3))
      assertEquals(tr, Left("forced"))

  test("absolve raises the typed error into IO when E is a Throwable"):
    val boom = RuntimeException("absolved")
    for
      ok <- (EffIO.succeed(1): EffIO[Throwable, Int]).absolve
      ko <- EffIO.fail(boom).absolve.attempt
    yield
      assertEquals(ok, 1)
      assertEquals(ko, Left(boom))

  test("option, collectSome, collectRight"):
    for
      opt <- run(EffIO.fail[String]("x").option)
      cs <- run(EffIO.succeed(Some(5)).collectSome("none"))
      csN <- run(EffIO.succeed(None: Option[Int]).collectSome("none"))
      cr <- run(EffIO.succeed(Right(9): Either[Int, Int]).collectRight(_.toString))
    yield
      assertEquals(opt, Right(None))
      assertEquals(cs, Right(5))
      assertEquals(csN, Left("none"))
      assertEquals(cr, Right(9))

  test("composition operators *>, <*, void, as"):
    for
      pr <- run(EffIO.succeed(1) *> EffIO.succeed(2))
      pl <- run(EffIO.succeed(1) <* EffIO.succeed(2))
      v <- run(EffIO.succeed(1).void)
      a <- run(EffIO.succeed(1).as("x"))
    yield
      assertEquals(pr, Right(2))
      assertEquals(pl, Right(1))
      assertEquals(v, Right(()))
      assertEquals(a, Right("x"))

  test("bracket releases the resource even on typed failure"):
    val source: EffIO[String, Int] = EffIO.succeed(42)
    for
      released <- IO.ref(false)
      r <- run(source.bracket(_ => EffIO.fail("use failed"))(_ => released.set(true)))
      wasReleased <- released.get
    yield
      assertEquals(r, Left("use failed"))
      assert(wasReleased)

  test("race returns the winner; both returns the pair"):
    for
      raced <- run(EffIO.succeed(1).race(EffIO.never[Int]))
      paired <- run(EffIO.succeed(1).both(EffIO.succeed(2)))
    yield
      assertEquals(raced, Right(Left(1)))
      assertEquals(paired, Right((1, 2)))

  test("timeout fails with the supplied error when the effect is too slow"):
    run(EffIO.never[Int].timeout(50.millis, "timed out"))
      .map(r => assertEquals(r, Left("timed out")))

  test("guaranteeCase observes the completion outcome"):
    val source: EffIO[String, Int] = EffIO.succeed(1)
    for
      outcome <- IO.ref(Option.empty[String])
      _ <- run(source.guaranteeCase {
             case Outcome.Succeeded(_) => outcome.set(Some("succeeded"))
             case Outcome.Errored(_)   => outcome.set(Some("errored"))
             case Outcome.Canceled()   => outcome.set(Some("canceled"))
           })
      seen <- outcome.get
    yield assertEquals(seen, Some("succeeded"))

  test("start yields a joinable fibre"):
    val source: EffIO[String, Int] = EffIO.succeed(99)
    val eff =
      for
        fiber <- source.start
        outcome <- fiber.join
        value <- outcome match
                   case Outcome.Succeeded(fa) => fa
                   case _                     => EffIO.succeed(-1)
      yield value
    run(eff).map(r => assertEquals(r, Right(99)))

  // ===========================================================================
  // Variance escape hatches
  // ===========================================================================

  test("assumeError narrows the error type for trusted casts"):
    val wide: EffIO[AppError, Int] = EffIO.succeed(1)
    run(wide.assumeError[NotFound]).map(r => assertEquals(r, Right(1)))

  // ===========================================================================
  // Natural transformations
  // ===========================================================================

  test("liftK lifts plain IO into the infallible EffIO context"):
    run(EffIO.liftK(IO.pure(7))).map(r => assertEquals(r, Right(7)))

  test("widenK is the identity error-widening transformation"):
    val k = EffIO.widenK[NotFound, AppError]
    run(k(EffIO.fail(NotFound("u1")))).map(r => assertEquals(r, Left(NotFound("u1"))))

  // ===========================================================================
  // Cats-effect primitive lifts
  // ===========================================================================

  test("liftRef operates a Ref in the EffIO context"):
    val eff =
      for
        ref <- EffIO.liftF(IO.ref(0)).map(EffIO.liftRef[String, Int])
        _ <- ref.set(5)
        v <- ref.get
      yield v
    run(eff).map(r => assertEquals(r, Right(5)))

  test("liftResource runs acquisition and release in the EffIO context"):
    for
      released <- IO.ref(false)
      resource = Resource.make(IO.pure(21))(_ => released.set(true))
      r <- run(EffIO.liftResource[String, Int](resource).use(n => EffIO.succeed(n * 2)))
      wasReleased <- released.get
    yield
      assertEquals(r, Right(42))
      assert(wasReleased)

  test("liftQueue operates a Queue in the EffIO context"):
    val eff =
      for
        queue <- EffIO
                   .liftF(cats.effect.std.Queue.unbounded[IO, Int])
                   .map(EffIO.liftQueue[String, Int])
        _ <- queue.offer(1)
        v <- queue.take
      yield v
    run(eff).map(r => assertEquals(r, Right(1)))

  test("liftSupervisor supervises an EffIO fibre"):
    Supervisor[IO](await = true).use { sup =>
      val lifted = EffIO.liftSupervisor[String](sup)
      val eff =
        for
          fiber <- lifted.supervise(EffIO.succeed(123))
          outcome <- fiber.join
          value <- outcome match
                     case Outcome.Succeeded(fa) => fa
                     case _                     => EffIO.succeed(-1)
        yield value
      run(eff).map(r => assertEquals(r, Right(123)))
    }

  test("liftCell operates an AtomicCell in the EffIO context"):
    val eff =
      for
        cell <- EffIO
                  .liftF(cats.effect.std.AtomicCell[IO].of(1))
                  .map(EffIO.liftCell[String, Int])
        _ <- cell.set(8)
        v <- cell.get
      yield v
    run(eff).map(r => assertEquals(r, Right(8)))

  // ===========================================================================
  // Typeclass instances - behavioural smoke tests (lawfulness covered separately)
  // ===========================================================================

  test("the Async instance is summonable and runs a concurrent program"):
    val F = summon[cats.effect.kernel.GenConcurrent[EffIO.Of[String], Throwable]]
    val program =
      for
        ref <- F.ref(0)
        _ <- ref.update(_ + 1)
        v <- ref.get
      yield v
    run(program).map(r => assertEquals(r, Right(1)))

  test("the MonadError instance handles the typed error channel"):
    val F = summon[cats.MonadError[EffIO.Of[String], String]]
    run(F.handleError(F.raiseError[Int]("boom"))(_.length))
      .map(r => assertEquals(r, Right(4)))
end EffIOSuite
