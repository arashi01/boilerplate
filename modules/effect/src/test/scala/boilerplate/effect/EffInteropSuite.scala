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

import cats.*
import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import munit.CatsEffectSuite

/** Tests for cats-effect primitive interop utilities.
  *
  * These tests verify our transformation logic produces correctly-typed results that compose with
  * Eff operations. We test that lifted primitives:
  *   1. Preserve the original primitive's semantics
  *   2. Operate in the Eff context with proper error channel typing
  *   3. Compose correctly with other Eff operations
  */
class EffInteropSuite extends CatsEffectSuite:
  private def runEff[E, A](eff: Eff[IO, E, A]): IO[Either[E, A]] = eff.either

  // --- Eff.functionK tests ---

  test("functionK creates valid natural transformation"):
    val fk = Eff.functionK[IO, String]
    val result: Eff[IO, String, Int] = fk(IO.pure(42))
    runEff(result).map(r => assertEquals(r, Right(42)))

  test("functionK preserves effect semantics"):
    Ref.of[IO, Boolean](false).flatMap { executed =>
      val fk = Eff.functionK[IO, String]
      val io = IO.defer(executed.set(true).as(42))
      val eff = fk(io)
      for
        before <- executed.get
        result <- runEff(eff)
        after <- executed.get
      yield
        assert(!before, "effect should not have executed before run")
        assert(after, "effect should have executed after run")
        assertEquals(result, Right(42))
    }

  // --- Eff.liftResource tests ---

  test("Eff.liftResource produces Resource in Eff context"):
    (Ref.of[IO, Boolean](false), Ref.of[IO, Boolean](false)).tupled.flatMap { (acquired, released) =>
      val resource: Resource[IO, Int] = Resource.make(
        acquired.set(true).as(42)
      )(_ => released.set(true))

      val liftedResource: Resource[Eff.Of[IO, String], Int] = Eff.liftResource(resource)

      for
        result <- liftedResource.use(n => Eff.succeed[IO, String, Int](n * 2)).either
        wasAcquired <- acquired.get
        wasReleased <- released.get
      yield
        assert(wasAcquired, "resource should have been acquired")
        assert(wasReleased, "resource should have been released")
        assertEquals(result, Right(84))
    }

  test("Eff.liftResource release runs even when use fails with typed error"):
    Ref.of[IO, Boolean](false).flatMap { released =>
      val resource: Resource[IO, Int] = Resource.make(IO.pure(42))(_ => released.set(true))
      val liftedResource = Eff.liftResource[IO, String, Int](resource)

      for
        result <- liftedResource.use(_ => Eff.fail[IO, String, Int]("boom")).either
        wasReleased <- released.get
      yield
        assert(wasReleased, "resource should have been released on typed error")
        assertEquals(result, Left("boom"))
    }

  test("Resource.eff[E] extension delegates to Eff.liftResource"):
    Ref.of[IO, Boolean](false).flatMap { released =>
      val resource: Resource[IO, Int] = Resource.make(IO.pure(42))(_ => released.set(true))
      val liftedResource: Resource[Eff.Of[IO, String], Int] = resource.eff[String]

      for
        result <- liftedResource.use(n => Eff.succeed[IO, String, Int](n)).either
        wasReleased <- released.get
      yield
        assert(wasReleased)
        assertEquals(result, Right(42))
    }

  // --- Eff.liftRef tests ---

  test("Eff.liftRef preserves get/set semantics in Eff context"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, String, Int](ref)
      val eff: Eff[IO, String, Int] = for
        _ <- liftedRef.set(42)
        result <- liftedRef.get
      yield result
      runEff(eff).map(r => assertEquals(r, Right(42)))
    }

  test("Eff.liftRef modifications compose with Eff operations"):
    Ref.of[IO, Int](10).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, String, Int](ref)
      val eff: Eff[IO, String, Int] = for
        current <- liftedRef.get
        _ <- liftedRef.set(current + 5)
        updated <- liftedRef.get
      yield updated
      runEff(eff).map(r => assertEquals(r, Right(15)))
    }

  test("Ref.eff[E] extension delegates to Eff.liftRef"):
    Ref.of[IO, Int](100).flatMap { ref =>
      val liftedRef: Ref[Eff.Of[IO, String], Int] = ref.eff[String]
      runEff(liftedRef.get).map(r => assertEquals(r, Right(100)))
    }

  // --- Eff.liftDeferred tests ---

  test("Eff.liftDeferred preserves complete/get semantics"):
    Deferred[IO, Int].flatMap { deferred =>
      val liftedDeferred = Eff.liftDeferred[IO, String, Int](deferred)
      val eff: Eff[IO, String, Int] = for
        _ <- liftedDeferred.complete(42)
        result <- liftedDeferred.get
      yield result
      runEff(eff).map(r => assertEquals(r, Right(42)))
    }

  test("Eff.liftDeferred can be completed from Eff context"):
    Deferred[IO, Int].flatMap { deferred =>
      val liftedDeferred = Eff.liftDeferred[IO, String, Int](deferred)
      val eff: Eff[IO, String, Boolean] = liftedDeferred.complete(99)
      for
        completed <- runEff(eff)
        value <- deferred.get // Read from original to verify
      yield
        assertEquals(completed, Right(true))
        assertEquals(value, 99)
    }

  test("Deferred.eff[E] extension delegates to Eff.liftDeferred"):
    Deferred[IO, String].flatMap { deferred =>
      val liftedDeferred: Deferred[Eff.Of[IO, Int], String] = deferred.eff[Int]
      val eff: Eff[IO, Int, String] = for
        _ <- liftedDeferred.complete("hello")
        result <- liftedDeferred.get
      yield result
      runEff(eff).map(r => assertEquals(r, Right("hello")))
    }

  // --- Eff.liftQueue tests ---

  test("Eff.liftQueue preserves offer/take semantics"):
    Queue.unbounded[IO, Int].flatMap { queue =>
      val liftedQueue = Eff.liftQueue[IO, String, Int](queue)
      val eff: Eff[IO, String, (Int, Int)] = for
        _ <- liftedQueue.offer(1)
        _ <- liftedQueue.offer(2)
        first <- liftedQueue.take
        second <- liftedQueue.take
      yield (first, second)
      runEff(eff).map(r => assertEquals(r, Right((1, 2))))
    }

  test("Eff.liftQueue composes with Eff for-comprehensions"):
    Queue.unbounded[IO, Int].flatMap { queue =>
      val liftedQueue = Eff.liftQueue[IO, String, Int](queue)
      val eff: Eff[IO, String, List[Int]] = for
        _ <- liftedQueue.offer(10)
        _ <- liftedQueue.offer(20)
        a <- liftedQueue.take
        b <- liftedQueue.take
      yield List(a, b)
      runEff(eff).map(r => assertEquals(r, Right(List(10, 20))))
    }

  test("Queue.eff[E] extension delegates to Eff.liftQueue"):
    Queue.unbounded[IO, String].flatMap { queue =>
      val liftedQueue: Queue[Eff.Of[IO, Int], String] = queue.eff[Int]
      val eff: Eff[IO, Int, String] = for
        _ <- liftedQueue.offer("test")
        result <- liftedQueue.take
      yield result
      runEff(eff).map(r => assertEquals(r, Right("test")))
    }

  // --- Eff.liftSemaphore tests ---

  test("Eff.liftSemaphore preserves permit semantics"):
    Semaphore[IO](1).flatMap { sem =>
      val liftedSem = Eff.liftSemaphore[IO, String](sem)
      val eff: Eff[IO, String, (Long, Long)] = for
        _ <- liftedSem.acquire
        available <- liftedSem.available
        _ <- liftedSem.release
        availableAfter <- liftedSem.available
      yield (available, availableAfter)
      runEff(eff).map { r =>
        assertEquals(r, Right((0L, 1L)))
      }
    }

  test("Eff.liftSemaphore permit guards Eff operations"):
    (Semaphore[IO](1), Ref.of[IO, Int](0)).tupled.flatMap { (sem, ref) =>
      val liftedSem = Eff.liftSemaphore[IO, String](sem)
      val liftedRef = Eff.liftRef[IO, String, Int](ref)

      val eff: Eff[IO, String, Int] = liftedSem.permit.use { _ =>
        for
          current <- liftedRef.get
          _ <- liftedRef.set(current + 1)
          updated <- liftedRef.get
        yield updated
      }
      for
        result <- runEff(eff)
        finalValue <- ref.get
      yield
        assertEquals(result, Right(1))
        assertEquals(finalValue, 1)
    }

  test("Semaphore.eff[E] extension delegates to Eff.liftSemaphore"):
    Semaphore[IO](2).flatMap { sem =>
      val liftedSem: Semaphore[Eff.Of[IO, String]] = sem.eff[String]
      runEff(liftedSem.available).map(r => assertEquals(r, Right(2L)))
    }

  // --- Composition tests: verifying lifted primitives work together ---

  test("lifted primitives compose in complex Eff workflows"):
    (Ref.of[IO, List[String]](Nil), Queue.unbounded[IO, Int]).tupled.flatMap { (ref, queue) =>
      val liftedRef = Eff.liftRef[IO, String, List[String]](ref)
      val liftedQueue = Eff.liftQueue[IO, String, Int](queue)

      val workflow: Eff[IO, String, List[String]] = for
        _ <- liftedQueue.offer(1)
        _ <- liftedQueue.offer(2)
        n1 <- liftedQueue.take
        _ <- liftedRef.update(_ :+ s"got $n1")
        n2 <- liftedQueue.take
        _ <- liftedRef.update(_ :+ s"got $n2")
        log <- liftedRef.get
      yield log

      runEff(workflow).map(r => assertEquals(r, Right(List("got 1", "got 2"))))
    }

  test("lifted Resource with lifted Ref maintains state correctly"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, String, Int](ref)
      val resource = Resource.make(
        liftedRef.updateAndGet(_ + 1)
      )(_ => liftedRef.update(_ + 10))

      for
        result <- resource.use(n => Eff.succeed[IO, String, Int](n * 2)).either
        finalValue <- ref.get
      yield
        assertEquals(result, Right(2)) // 1 * 2
        assertEquals(finalValue, 11) // 1 + 10 from release
    }

  // --- Error channel preservation tests ---

  test("lifted primitives preserve typed error channel in failure scenarios"):
    Ref.of[IO, Int](0).flatMap { ref =>
      val liftedRef = Eff.liftRef[IO, String, Int](ref)

      val workflow: Eff[IO, String, Int] = for
        _ <- liftedRef.set(42)
        _ <- Eff.fail[IO, String, Unit]("intentional failure")
        _ <- liftedRef.set(99) // Should not execute
      yield 0

      for
        result <- runEff(workflow)
        finalValue <- ref.get
      yield
        assertEquals(result, Left("intentional failure"))
        assertEquals(finalValue, 42) // Set before failure, not 99
    }

  // --- CountDownLatch, CyclicBarrier, AtomicCell, Supervisor extensions ---

  test("CountDownLatch.eff[E] extension delegates to Eff.liftLatch"):
    cats.effect.std.CountDownLatch[IO](1).flatMap { latch =>
      val liftedLatch: cats.effect.std.CountDownLatch[Eff.Of[IO, String]] = latch.eff[String]
      for
        _ <- latch.release
        _ <- runEff(liftedLatch.await)
      yield ()
    }

  test("CyclicBarrier.eff[E] extension delegates to Eff.liftBarrier"):
    cats.effect.std.CyclicBarrier[IO](1).flatMap { barrier =>
      val liftedBarrier: cats.effect.std.CyclicBarrier[Eff.Of[IO, String]] = barrier.eff[String]
      runEff(liftedBarrier.await).map(r => assert(r.isRight))
    }

  test("AtomicCell.eff[E] extension delegates to Eff.liftCell"):
    cats.effect.std.AtomicCell[IO].of(10).flatMap { cell =>
      val liftedCell: cats.effect.std.AtomicCell[Eff.Of[IO, String], Int] = cell.eff[String]
      for
        _ <- runEff(liftedCell.set(42))
        value <- cell.get
      yield assertEquals(value, 42)
    }

  test("Supervisor.eff[E] extension delegates to Eff.liftSupervisor"):
    cats.effect.std.Supervisor[IO](await = true).use { sup =>
      val liftedSup: cats.effect.std.Supervisor[Eff.Of[IO, String]] = sup.eff[String]
      // Verify the extension produces the correct type
      val supervised: Eff[IO, String, cats.effect.kernel.Fiber[Eff.Of[IO, String], Throwable, Int]] =
        liftedSup.supervise(Eff.succeed[IO, String, Int](42))
      // Just verify it compiles and runs without error
      runEff(supervised).map(r => assert(r.isRight))
    }

  // --- Fiber join extension tests ---

  test("Fiber.joinNever returns value from successful fiber"):
    cats.effect.std.Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[String]
      for
        fiber <- liftedSup.supervise(Eff.succeed[IO, String, Int](42)).either
        result <- fiber match
                    case Right(f) => f.joinNever.either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Right(42))
    }

  test("Fiber.joinNever propagates typed error from fiber"):
    cats.effect.std.Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[String]
      for
        fiber <- liftedSup.supervise(Eff.fail[IO, String, Int]("boom")).either
        result <- fiber match
                    case Right(f) => f.joinNever.either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Left("boom"))
    }

  test("Fiber.joinOrFail returns value from successful fiber"):
    cats.effect.std.Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[String]
      for
        fiber <- liftedSup.supervise(Eff.succeed[IO, String, Int](42)).either
        result <- fiber match
                    case Right(f) => f.joinOrFail("was cancelled").either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Right(42))
    }

  test("Fiber.joinOrFail propagates typed error from fiber"):
    cats.effect.std.Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[String]
      for
        fiber <- liftedSup.supervise(Eff.fail[IO, String, Int]("boom")).either
        result <- fiber match
                    case Right(f) => f.joinOrFail("was cancelled").either
                    case Left(e)  => IO.pure(Left(e))
      yield assertEquals(result, Left("boom"))
    }

  test("Fiber.joinOrFail returns typed error when fiber is cancelled"):
    cats.effect.std.Supervisor[IO](await = true).use { sup =>
      val liftedSup = sup.eff[String]
      for
        fiber <- liftedSup.supervise(Eff.liftF[IO, String, Int](IO.never)).either
        result <- fiber match
                    case Right(f) =>
                      for
                        _ <- f.cancel.either
                        r <- f.joinOrFail("was cancelled").either
                      yield r
                    case Left(e) => IO.pure(Left(e))
      yield assertEquals(result, Left("was cancelled"))
    }

end EffInteropSuite
