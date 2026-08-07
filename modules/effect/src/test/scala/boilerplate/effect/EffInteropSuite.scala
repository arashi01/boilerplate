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

import boilerplate.effect.IoError.*

// `IO[A]` is a subtype of every `Eff[E, A]`, so the whole `cats.effect` primitive vocabulary is
// already `Eff` vocabulary: a `Ref`, `Queue`, `Semaphore` or `Resource` built over `IO` drops into
// an `Eff` workflow with no lift, no `mapK`, and no import. Every row below states that for one
// primitive.
class EffInteropSuite extends CatsEffectSuite:
  private def run[E <: Throwable, A](eff: Eff[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] = eff.either.absolve

  test("a raw Ref composes directly in an Eff workflow, preserving get/set/update"):
    IO.ref(0).flatMap { ref =>
      val eff: Eff[IoError, Int] =
        for
          _ <- ref.set(42): Eff[IoError, Unit]
          _ <- ref.update(_ + 3)
          v <- ref.get
        yield v
      run(eff).map(r => assertEquals(r, Right(45)))
    }

  test("a raw Deferred composes directly in an Eff workflow, preserving complete/get"):
    Deferred[IO, Int].flatMap { deferred =>
      val eff: Eff[IoError, (Boolean, Int)] =
        for
          completed <- deferred.complete(42): Eff[IoError, Boolean]
          v <- deferred.get
        yield (completed, v)
      for
        r <- run(eff)
        underlying <- deferred.get
      yield
        assertEquals(r, Right((true, 42)))
        assertEquals(underlying, 42)
    }

  test("a raw Queue composes directly in an Eff workflow, preserving FIFO offer/take"):
    Queue.unbounded[IO, Int].flatMap { queue =>
      val eff: Eff[IoError, (Int, Int)] =
        for
          _ <- queue.offer(1): Eff[IoError, Unit]
          _ <- queue.offer(2)
          first <- queue.take
          second <- queue.take
        yield (first, second)
      run(eff).map(r => assertEquals(r, Right((1, 2))))
    }

  test("a raw Semaphore permit guards an Eff operation"):
    for
      sem <- Semaphore[IO](1)
      ref <- IO.ref(0)
      guarded: Eff[IoError, Int] = (sem.permit: EffResource[Nothing, Unit]).use { _ =>
                                     for
                                       current <- ref.get: Eff[IoError, Int]
                                       _ <- ref.set(current + 1)
                                       updated <- ref.get
                                     yield updated
                                   }
      result <- run(guarded)
      finalValue <- ref.get
    yield
      assertEquals(result, Right(1))
      assertEquals(finalValue, 1)

  test("a raw CountDownLatch composes directly in an Eff workflow, releasing awaiters at zero"):
    CountDownLatch[IO](1).flatMap { latch =>
      val eff: Eff[IoError, Unit] =
        for
          _ <- latch.release: Eff[IoError, Unit]
          _ <- latch.await
        yield ()
      run(eff).map(r => assertEquals(r, Right(())))
    }

  test("a raw CyclicBarrier composes directly in an Eff workflow, releasing at the party count"):
    CyclicBarrier[IO](1).flatMap { barrier =>
      val eff: Eff[IoError, Unit] = barrier.await
      run(eff).map(r => assertEquals(r, Right(())))
    }

  test("a raw AtomicCell composes directly in an Eff workflow and writes through"):
    AtomicCell[IO].of(10).flatMap { cell =>
      val eff: Eff[IoError, Int] =
        for
          _ <- cell.set(42): Eff[IoError, Unit]
          v <- cell.get
        yield v
      for
        r <- run(eff)
        underlying <- cell.get
      yield
        assertEquals(r, Right(42))
        assertEquals(underlying, 42)
    }

  test("a raw Supervisor supervises an Eff fibre whose typed failure joins as Outcome.Errored"):
    Supervisor[IO](await = true).use { sup =>
      val boom = Failed(1)
      val eff: Eff[IoError, Int] =
        for
          fiber <- sup.supervise((Eff.fail(boom): Eff[IoError, Int]).absolve): Eff[IoError, Fiber[IO, Throwable, Int]]
          outcome <- fiber.join
        yield outcome match
          case Outcome.Errored(e) if e eq boom => 1
          case _                               => 0
      run(eff).map(r => assertEquals(r, Right(1)))
    }

  test("a raw Ref and Queue compose in a single Eff workflow"):
    for
      ref <- IO.ref(List.empty[String])
      queue <- Queue.unbounded[IO, Int]
      workflow: Eff[IoError, List[String]] =
        for
          _ <- queue.offer(1): Eff[IoError, Unit]
          _ <- queue.offer(2)
          n1 <- queue.take
          _ <- ref.update(_ :+ s"got $n1")
          n2 <- queue.take
          _ <- ref.update(_ :+ s"got $n2")
          log <- ref.get
        yield log
      r <- run(workflow)
    yield assertEquals(r, Right(List("got 1", "got 2")))

  test("a typed failure short-circuits the workflow and preserves prior Ref writes"):
    IO.ref(0).flatMap { ref =>
      val workflow: Eff[IoError, Int] =
        for
          _ <- ref.set(42): Eff[IoError, Unit]
          _ <- Eff.fail[IoError](Closed)
          _ <- ref.set(99): Eff[IoError, Unit] // must not run
        yield 0
      for
        result <- run(workflow)
        finalValue <- ref.get
      yield
        assertEquals(result, Left(Closed))
        assertEquals(finalValue, 42)
    }

  test("a raw Resource consumed as an EffResource releases after a success and after a typed failure"):
    for
      released <- IO.ref(0)
      res: EffResource[Nothing, Int] = Resource.make(IO.pure(42))(_ => released.update(_ + 1))
      succeeding: Eff[Nothing, Int] = res.use(a => Eff.succeed(a + 1))
      failing: Eff[IoError, Int] = res.use(_ => Eff.fail(Closed))
      ok <- run(succeeding)
      afterOk <- released.get
      ko <- run(failing)
      afterKo <- released.get
    yield
      assertEquals(ok, Right(43))
      assertEquals(ko, Left(Closed))
      assertEquals(afterOk, 1) // released after success
      assertEquals(afterKo, 2) // released after a typed error too
end EffInteropSuite
