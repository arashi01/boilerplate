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

import scala.reflect.TypeTest

import cats.effect.*
import cats.effect.kernel.Outcome
import cats.effect.std.*
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IoError.*

class EffInteropSuite extends CatsEffectSuite:
  private def runEff[E <: Throwable, A](eff: Eff[IO, E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] =
    eff.either

  private def runEffIO[E <: Throwable, A](eff: EffIO[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] =
    eff.either

  // functionK

  test("functionK builds a valid natural transformation into the Eff channel"):
    val fk = Eff.functionK[IO, AppError]
    val lifted: Eff[IO, AppError, Int] = fk(IO.pure(42))
    runEff(lifted).map(r => assertEquals(r, Right(42)))

  test("functionK is lazy: it neither runs the effect early nor duplicates it"):
    Ref.of[IO, Int](0).flatMap { runs =>
      val fk = Eff.functionK[IO, AppError]
      val lifted = fk(runs.update(_ + 1))
      for
        before <- runs.get
        _ <- runEff(lifted)
        after <- runs.get
      yield
        assertEquals(before, 0)
        assertEquals(after, 1)
    }

  // Generic Eff (F = IO) lifts + `.eff` syntax

  test("Eff.liftResource acquires, uses, and releases, returning the body result"):
    for
      acquired <- Ref.of[IO, Boolean](false)
      released <- Ref.of[IO, Boolean](false)
      resource = Resource.make(acquired.set(true).as(42))(_ => released.set(true))
      lifted = Eff.liftResource[IO, IoError, Int](resource)
      result <- runEff(lifted.use(n => Eff.succeed[IO, IoError, Int](n * 2)))
      wasAcquired <- acquired.get
      wasReleased <- released.get
    yield
      assert(wasAcquired, "resource should have been acquired")
      assert(wasReleased, "resource should have been released")
      assertEquals(result, Right(84))

  test("Eff.liftResource releases the resource even when the body fails with a typed error"):
    Ref.of[IO, Boolean](false).flatMap { released =>
      val resource: Resource[IO, Int] = Resource.make(IO.pure(42))(_ => released.set(true))
      val lifted = Eff.liftResource[IO, IoError, Int](resource)
      for
        result <- runEff(lifted.use(_ => Eff.fail[IO, IoError, Int](Closed)))
        wasReleased <- released.get
      yield
        assert(wasReleased, "resource should have been released on a typed error")
        assertEquals(result, Left(Closed))
    }

  test("Resource.eff delegates to Eff.liftResource"):
    Ref.of[IO, Boolean](false).flatMap { released =>
      val resource: Resource[IO, Int] = Resource.make(IO.pure(42))(_ => released.set(true))
      val lifted: Resource[Eff.Of[IO, IoError], Int] = resource.eff[IoError]
      for
        result <- runEff(lifted.use(n => Eff.succeed[IO, IoError, Int](n)))
        wasReleased <- released.get
      yield
        assert(wasReleased)
        assertEquals(result, Right(42))
    }

  test("Eff.liftRef preserves get/set/update semantics in the Eff context"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, IoError, Int](ref)
      val eff: Eff[IO, IoError, Int] = for
        _ <- liftedRef.set(42)
        _ <- liftedRef.update(_ + 3)
        result <- liftedRef.get
      yield result
      runEff(eff).map(r => assertEquals(r, Right(45)))
    }

  test("Ref.eff delegates to Eff.liftRef"):
    Ref.of[IO, Int](100).flatMap { ref =>
      val liftedRef: Ref[Eff.Of[IO, IoError], Int] = ref.eff[IoError]
      runEff(liftedRef.get).map(r => assertEquals(r, Right(100)))
    }

  test("Eff.liftDeferred preserves complete/get and writes through to the underlying Deferred"):
    Deferred[IO, Int].flatMap { deferred =>
      val liftedDeferred = Eff.liftDeferred[IO, IoError, Int](deferred)
      val eff: Eff[IO, IoError, Int] = for
        _ <- liftedDeferred.complete(42)
        result <- liftedDeferred.get
      yield result
      for
        r <- runEff(eff)
        original <- deferred.get
      yield
        assertEquals(r, Right(42))
        assertEquals(original, 42)
    }

  test("Deferred.eff delegates to Eff.liftDeferred"):
    Deferred[IO, String].flatMap { deferred =>
      val liftedDeferred: Deferred[Eff.Of[IO, IoError], String] = deferred.eff[IoError]
      val eff: Eff[IO, IoError, String] = for
        _ <- liftedDeferred.complete("hello")
        result <- liftedDeferred.get
      yield result
      runEff(eff).map(r => assertEquals(r, Right("hello")))
    }

  test("Eff.liftQueue preserves FIFO offer/take semantics"):
    Queue.unbounded[IO, Int].flatMap { queue =>
      val liftedQueue = Eff.liftQueue[IO, IoError, Int](queue)
      val eff: Eff[IO, IoError, (Int, Int)] = for
        _ <- liftedQueue.offer(1)
        _ <- liftedQueue.offer(2)
        first <- liftedQueue.take
        second <- liftedQueue.take
      yield (first, second)
      runEff(eff).map(r => assertEquals(r, Right((1, 2))))
    }

  test("Queue.eff delegates to Eff.liftQueue"):
    Queue.unbounded[IO, String].flatMap { queue =>
      val liftedQueue: Queue[Eff.Of[IO, IoError], String] = queue.eff[IoError]
      val eff: Eff[IO, IoError, String] = for
        _ <- liftedQueue.offer("test")
        result <- liftedQueue.take
      yield result
      runEff(eff).map(r => assertEquals(r, Right("test")))
    }

  test("Eff.liftSemaphore preserves acquire/available/release semantics"):
    Semaphore[IO](1).flatMap { sem =>
      val liftedSem = Eff.liftSemaphore[IO, IoError](sem)
      val eff: Eff[IO, IoError, (Long, Long)] = for
        _ <- liftedSem.acquire
        available <- liftedSem.available
        _ <- liftedSem.release
        availableAfter <- liftedSem.available
      yield (available, availableAfter)
      runEff(eff).map(r => assertEquals(r, Right((0L, 1L))))
    }

  test("Eff.liftSemaphore permit guards an Eff operation"):
    for
      sem <- Semaphore[IO](1)
      ref <- Ref.of[IO, Int](0)
      liftedSem = Eff.liftSemaphore[IO, IoError](sem)
      liftedRef = Eff.liftRef[IO, IoError, Int](ref)
      eff = liftedSem.permit.use { _ =>
              for
                current <- liftedRef.get
                _ <- liftedRef.set(current + 1)
                updated <- liftedRef.get
              yield updated
            }
      result <- runEff(eff)
      finalValue <- ref.get
    yield
      assertEquals(result, Right(1))
      assertEquals(finalValue, 1)

  test("Semaphore.eff delegates to Eff.liftSemaphore"):
    Semaphore[IO](2).flatMap { sem =>
      val liftedSem: Semaphore[Eff.Of[IO, IoError]] = sem.eff[IoError]
      runEff(liftedSem.available).map(r => assertEquals(r, Right(2L)))
    }

  test("CountDownLatch.eff releases awaiters once the count reaches zero"):
    CountDownLatch[IO](1).flatMap { latch =>
      val liftedLatch = latch.eff[IoError]
      val eff: Eff[IO, IoError, Unit] = for
        _ <- liftedLatch.release
        _ <- liftedLatch.await
      yield ()
      runEff(eff).map(r => assertEquals(r, Right(())))
    }

  test("CyclicBarrier.eff releases when the party count is reached"):
    CyclicBarrier[IO](1).flatMap { barrier =>
      val liftedBarrier = barrier.eff[IoError]
      runEff(liftedBarrier.await).map(r => assertEquals(r, Right(())))
    }

  test("AtomicCell.eff writes through to the underlying cell"):
    AtomicCell[IO].of(10).flatMap { cell =>
      val liftedCell = cell.eff[IoError]
      for
        _ <- runEff(liftedCell.set(42))
        value <- cell.get
      yield assertEquals(value, 42)
    }

  test("lifted Ref and Queue compose in a single Eff workflow"):
    for
      ref <- Ref.of[IO, List[String]](Nil)
      queue <- Queue.unbounded[IO, Int]
      liftedRef = Eff.liftRef[IO, IoError, List[String]](ref)
      liftedQueue = Eff.liftQueue[IO, IoError, Int](queue)
      workflow = (for
                   _ <- liftedQueue.offer(1)
                   _ <- liftedQueue.offer(2)
                   n1 <- liftedQueue.take
                   _ <- liftedRef.update(_ :+ s"got $n1")
                   n2 <- liftedQueue.take
                   _ <- liftedRef.update(_ :+ s"got $n2")
                   log <- liftedRef.get
                 yield log): Eff[IO, IoError, List[String]]
      r <- runEff(workflow)
    yield assertEquals(r, Right(List("got 1", "got 2")))

  test("a lifted Resource whose acquire/release run in the Eff context keeps state consistent"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, IoError, Int](ref)
      val resource = Resource.make(liftedRef.updateAndGet(_ + 1))(_ => liftedRef.update(_ + 10))
      for
        result <- runEff(resource.use(n => Eff.succeed[IO, IoError, Int](n * 2)))
        finalValue <- ref.get
      yield
        assertEquals(result, Right(2)) // acquired 1, doubled
        assertEquals(finalValue, 11) // 1 acquire + 10 release
    }

  test("a typed failure short-circuits the workflow and preserves prior lifted-Ref writes"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, IoError, Int](ref)
      val workflow: Eff[IO, IoError, Int] = for
        _ <- liftedRef.set(42)
        _ <- Eff.fail[IO, IoError, Unit](Closed)
        _ <- liftedRef.set(99) // must not run
      yield 0
      for
        result <- runEff(workflow)
        finalValue <- ref.get
      yield
        assertEquals(result, Left(Closed))
        assertEquals(finalValue, 42)
    }

  // EffIO lifts + `.effIO` syntax

  test("Ref.effIO preserves get/set/update semantics in the EffIO context"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = ref.effIO[IoError]
      val eff: EffIO[IoError, Int] = for
        _ <- liftedRef.set(7)
        _ <- liftedRef.update(_ + 5)
        v <- liftedRef.get
      yield v
      runEffIO(eff).map(r => assertEquals(r, Right(12)))
    }

  test("Deferred.effIO preserves complete/get semantics"):
    Deferred[IO, Int].flatMap { deferred =>
      val liftedDeferred = deferred.effIO[IoError]
      val eff: EffIO[IoError, Int] = for
        _ <- liftedDeferred.complete(42)
        v <- liftedDeferred.get
      yield v
      runEffIO(eff).map(r => assertEquals(r, Right(42)))
    }

  test("Queue.effIO preserves FIFO offer/take semantics"):
    Queue.unbounded[IO, Int].flatMap { queue =>
      val liftedQueue = queue.effIO[IoError]
      val eff: EffIO[IoError, List[Int]] = for
        _ <- liftedQueue.offer(1)
        _ <- liftedQueue.offer(2)
        a <- liftedQueue.take
        b <- liftedQueue.take
      yield List(a, b)
      runEffIO(eff).map(r => assertEquals(r, Right(List(1, 2))))
    }

  test("Semaphore.effIO permit guards an EffIO operation"):
    for
      sem <- Semaphore[IO](1)
      ref <- Ref.of[IO, Int](0)
      liftedSem = sem.effIO[IoError]
      liftedRef = ref.effIO[IoError]
      eff = liftedSem.permit.use { _ =>
              for
                current <- liftedRef.get
                _ <- liftedRef.set(current + 1)
                updated <- liftedRef.get
              yield updated
            }
      result <- runEffIO(eff)
      finalValue <- ref.get
    yield
      assertEquals(result, Right(1))
      assertEquals(finalValue, 1)

  test("CountDownLatch.effIO releases awaiters once the count reaches zero"):
    CountDownLatch[IO](1).flatMap { latch =>
      val liftedLatch = latch.effIO[IoError]
      val eff: EffIO[IoError, Unit] = for
        _ <- liftedLatch.release
        _ <- liftedLatch.await
      yield ()
      runEffIO(eff).map(r => assertEquals(r, Right(())))
    }

  test("CyclicBarrier.effIO releases when the party count is reached"):
    CyclicBarrier[IO](1).flatMap { barrier =>
      val liftedBarrier = barrier.effIO[IoError]
      runEffIO(liftedBarrier.await).map(r => assertEquals(r, Right(())))
    }

  test("AtomicCell.effIO writes through to the underlying cell"):
    AtomicCell[IO].of(10).flatMap { cell =>
      val liftedCell = cell.effIO[IoError]
      for
        _ <- runEffIO(liftedCell.set(42))
        value <- cell.get
      yield assertEquals(value, 42)
    }

  // Fibre joins in the Eff context

  test("Eff Fiber.joinNever returns the value from a successful fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[AppError]
      for
        fiber <- liftedSup.supervise(Eff.succeed[IO, AppError, Int](42)).either
        result <- fiber match
                    case Right(f) => f.joinNever.either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Right(42))
    }

  test("Eff Fiber.joinNever propagates a typed error from the fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[AppError]
      for
        fiber <- liftedSup.supervise(Eff.fail[IO, AppError, Int](Invalid("boom"))).either
        result <- fiber match
                    case Right(f) => f.joinNever.either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Left(Invalid("boom")))
    }

  test("Eff Fiber.joinOrFail returns the value from a successful fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[AppError]
      for
        fiber <- liftedSup.supervise(Eff.succeed[IO, AppError, Int](42)).either
        result <- fiber match
                    case Right(f) => f.joinOrFail(Timeout).either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Right(42))
    }

  test("Eff Fiber.joinOrFail propagates a typed error from the fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[AppError]
      for
        fiber <- liftedSup.supervise(Eff.fail[IO, AppError, Int](Invalid("boom"))).either
        result <- fiber match
                    case Right(f) => f.joinOrFail(Timeout).either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Left(Invalid("boom")))
    }

  test("Eff Fiber.joinOrFail fails with onCanceled when the fibre is cancelled"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[AppError]
      for
        fiber <- liftedSup.supervise(Eff.liftF[IO, AppError, Int](IO.never)).either
        result <- fiber match
                    case Right(f) =>
                      for
                        _ <- f.cancel.either
                        r <- f.joinOrFail(Timeout).either
                      yield r
                    case Left(e) => IO.pure(Left(e))
      yield assertEquals(result, Left(Timeout))
    }

  test("an Eff fibre failing with a typed error joins as Outcome.Errored, not Succeeded"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[AppError]
      val boom = Invalid("boom")
      val eff: Eff[IO, AppError, Int] =
        for
          fiber <- liftedSup.supervise(Eff.fail[IO, AppError, Int](boom))
          outcome <- fiber.join
        yield outcome match
          case Outcome.Errored(e) if e eq boom => 1
          case _                               => 0
      runEff(eff).map(r => assertEquals(r, Right(1)))
    }

  // Fibre joins in the EffIO context

  test("EffIO Fiber.joinNever returns the value from a successful fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.effIO[IoError]
      for
        fiber <- liftedSup.supervise(EffIO.succeed(42): EffIO[IoError, Int]).either
        result <- fiber match
                    case Right(f) => f.joinNever.either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Right(42))
    }

  test("EffIO Fiber.joinNever propagates a typed error from the fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.effIO[IoError]
      for
        fiber <- liftedSup.supervise(EffIO.fail(Closed): EffIO[IoError, Int]).either
        result <- fiber match
                    case Right(f) => f.joinNever.either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Left(Closed))
    }

  test("EffIO Fiber.joinOrFail propagates a typed error from the fibre"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.effIO[IoError]
      for
        fiber <- liftedSup.supervise(EffIO.fail(Failed(500)): EffIO[IoError, Int]).either
        result <- fiber match
                    case Right(f) => f.joinOrFail(Closed).either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Left(Failed(500)))
    }

  test("EffIO Fiber.joinOrFail fails with onCanceled when the fibre is cancelled"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.effIO[IoError]
      for
        fiber <- liftedSup.supervise(EffIO.liftF(IO.never[Int]): EffIO[IoError, Int]).either
        result <- fiber match
                    case Right(f) =>
                      for
                        _ <- f.cancel.either
                        r <- f.joinOrFail(Closed).either
                      yield r
                    case Left(e) => IO.pure(Left(e))
      yield assertEquals(result, Left(Closed))
    }

  test("an EffIO fibre failing with a typed error joins as Outcome.Errored, not Succeeded"):
    Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.effIO[IoError]
      val boom = Failed(1)
      val eff: EffIO[IoError, Int] =
        for
          fiber <- liftedSup.supervise(EffIO.fail(boom): EffIO[IoError, Int])
          outcome <- fiber.join
        yield outcome match
          case Outcome.Errored(e) if e eq boom => 1
          case _                               => 0
      runEffIO(eff).map(r => assertEquals(r, Right(1)))
    }

  // Resource.useEff / useEffIO

  test("Resource.useEffIO runs the body and always releases the resource"):
    for
      released <- Ref.of[IO, Int](0)
      res = Resource.make(IO.pure(42))(_ => released.update(_ + 1))
      ok <- runEffIO(res.useEffIO(a => EffIO.succeed(a + 1)))
      afterOk <- released.get
      ko <- runEffIO(res.useEffIO[IoError, Int](_ => EffIO.fail(Closed)))
      afterKo <- released.get
    yield
      assertEquals(ok, Right(43))
      assertEquals(ko, Left(Closed))
      assertEquals(afterOk, 1) // released after success
      assertEquals(afterKo, 2) // released after a typed error too

  test("Resource.useEff (generic F) releases the resource on a typed error"):
    Ref.of[IO, Boolean](false).flatMap { released =>
      val res = Resource.make(IO.pure(1))(_ => released.set(true))
      for
        r <- runEff(res.useEff(_ => Eff.fail[IO, IoError, Int](Closed)))
        wasReleased <- released.get
      yield
        assertEquals(r, Left(Closed))
        assert(wasReleased)
    }

end EffInteropSuite
