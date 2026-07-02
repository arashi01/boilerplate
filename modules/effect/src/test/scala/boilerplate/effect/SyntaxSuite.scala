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

import scala.util.Failure
import scala.util.Try

import cats.*
import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite

class SyntaxSuite extends CatsEffectSuite:
  import boilerplate.effect.*

  private def runEff[E, A](eff: Eff[IO, E, A]): IO[Either[E, A]] = eff.either

  test("Either.eff mirrors Eff.from"):
    val either: Either[String, Int] = Right(42)
    runEff(either.eff[IO]).map(result => assertEquals(result, Right(42)))

  test("F[Either].eff preserves structure"):
    val fea = IO.pure[Either[String, Int]](Right(7))
    runEff(fea.eff).map(result => assertEquals(result, Right(7)))

  test("F[A].eff lifts an infallible effect as a success"):
    val lifted: UEff[IO, Int] = IO.pure(42).eff
    runEff(lifted).map(result => assertEquals(result, Right(42)))

  test("Eff.from with Id is unambiguous"):
    val value: Eff[Id, String, Int] = Eff.from[Id, String, Int](Right(9))
    val failure: Eff[Id, String, Int] = Eff.from[Id, String, Int](Left("err"))
    assertEquals(value.either, Right(9))
    assertEquals(failure.either, Left("err"))

  test("Eff.from(Option) with Id lifts missing values"):
    val none: Option[Int] = None
    val some: Option[Int] = Some(3)
    val missing: Eff[Id, String, Int] = Eff.from[Id, String, Int](none, "missing")
    val present: Eff[Id, String, Int] = Eff.from[Id, String, Int](some, "missing")
    assertEquals(missing.either, Left("missing"))
    assertEquals(present.either, Right(3))

  test("Try.eff translates failure"):
    val boom = new RuntimeException("boom")
    runEff(Try(throw boom).eff[IO, String](_.getMessage)).map(result => assertEquals(result, Left("boom"))) // scalafix:ok DisableSyntax.throw

  test("Try.eff converts success"):
    runEff(Try(42).eff[IO, String](_.getMessage)).map(result => assertEquals(result, Right(42)))

  test("F[A].eff captures throwable failures"):
    val failing = IO.raiseError[Int](new RuntimeException("boom"))
    runEff(failing.eff[String](_.getMessage)).map(result => assertEquals(result, Left("boom")))

  test("F[A].eff passes through success"):
    val success = IO.pure(42)
    runEff(success.eff[String](_.getMessage)).map(result => assertEquals(result, Right(42)))

  test("Option.eff converts present values"):
    val some: Option[Int] = Some(42)
    runEff(some.eff[IO, String]("missing")).map(result => assertEquals(result, Right(42)))

  test("F[Option].eff converts present values"):
    val fo = IO.pure(Some(42))
    runEff(fo.eff[String]("missing")).map(result => assertEquals(result, Right(42)))

  test("F[Option].eff converts missing values to error"):
    val fo = IO.pure(Option.empty[Int])
    runEff(fo.eff[String]("missing")).map(result => assertEquals(result, Left("missing")))

  test("Fiber.joinNever returns value on success"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinNever.either
    yield assertEquals(result, Right(42))

  test("Fiber.joinNever propagates typed error"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinNever.either
    yield assertEquals(result, Left("boom"))

  test("Fiber.joinOrFail returns value on success"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinOrFail("canceled").either
    yield assertEquals(result, Right(42))

  test("Fiber.joinOrFail propagates typed error"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinOrFail("canceled").either
    yield assertEquals(result, Left("boom"))

  test("Fiber.joinOrFail returns error on cancellation"):
    for
      deferred <- Deferred[IO, Unit]
      eff: Eff[IO, String, Int] = Eff.liftF(deferred.get *> IO.pure(42))
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      _ <- fiber.cancel
      result <- liftedFiber.joinOrFail("was canceled").either
    yield assertEquals(result, Left("was canceled"))

  test("IO.effIO captures throwable failures into the typed error channel"):
    for
      ok <- IO.pure(1).effIO(_.getMessage).either
      ko <- IO.raiseError[Int](RuntimeException("boom")).effIO(_.getMessage).either
    yield
      assertEquals(ok, Right(1))
      assertEquals(ko, Left("boom"))

  test("IO.effIO lifts an infallible IO"):
    IO.pure(42).effIO.either.map(result => assertEquals(result, Right(42)))

  test("IO[Either].effIO mirrors EffIO.lift"):
    IO.pure(Right(7): Either[String, Int])
      .effIO
      .either
      .map(result => assertEquals(result, Right(7)))

  test("Either.effIO mirrors EffIO.from"):
    (Left("boom"): Either[String, Int]).effIO.either
      .map(result => assertEquals(result, Left("boom")))

  test("Option.effIO injects the supplied error when empty"):
    for
      some <- Some(1).effIO("none").either
      none <- (None: Option[Int]).effIO("none").either
    yield
      assertEquals(some, Right(1))
      assertEquals(none, Left("none"))

  test("IO[Option].effIO injects the supplied error when empty"):
    IO.pure(None: Option[Int])
      .effIO("none")
      .either
      .map(result => assertEquals(result, Left("none")))

  test("Try.effIO translates failures into the typed error channel"):
    Failure[Int](RuntimeException("boom"))
      .effIO(_.getMessage)
      .either
      .map(result => assertEquals(result, Left("boom")))

  test("Resource.effIO operates the resource in the EffIO context"):
    Resource
      .pure[IO, Int](5)
      .effIO[String]
      .use(n => EffIO.succeed(n))
      .either
      .map(result => assertEquals(result, Right(5)))

  test("Ref, Deferred, and Queue effIO extensions operate in the EffIO context"):
    for
      ref <- IO.ref(0)
      _ <- ref.effIO[String].set(9).either
      refValue <- ref.get
      deferred <- Deferred[IO, Int]
      _ <- deferred.effIO[String].complete(1).either
      deferredValue <- deferred.get
      queue <- cats.effect.std.Queue.unbounded[IO, Int]
      _ <- queue.effIO[String].offer(2).either
      queueValue <- queue.take
    yield
      assertEquals(refValue, 9)
      assertEquals(deferredValue, 1)
      assertEquals(queueValue, 2)

  test("Semaphore and AtomicCell effIO extensions operate in the EffIO context"):
    for
      semaphore <- cats.effect.std.Semaphore[IO](1)
      available <- semaphore.effIO[String].available.either
      cell <- cats.effect.std.AtomicCell[IO].of(3)
      cellValue <- cell.effIO[String].get.either
    yield
      assertEquals(available, Right(1L))
      assertEquals(cellValue, Right(3))

  test("CountDownLatch, CyclicBarrier, and Supervisor effIO extensions operate in the EffIO context"):
    cats.effect.std.Supervisor[IO](await = true).use { supervisor =>
      for
        latch <- cats.effect.std.CountDownLatch[IO](1)
        _ <- latch.effIO[String].release.either
        latchAwait <- latch.effIO[String].await.either
        barrier <- cats.effect.std.CyclicBarrier[IO](1)
        barrierAwait <- barrier.effIO[String].await.either
        supervised <- supervisor.effIO[String].supervise(EffIO.succeed(7)).either
      yield
        assertEquals(latchAwait, Right(()))
        assertEquals(barrierAwait, Right(()))
        assert(supervised.isRight)
    }
end SyntaxSuite
