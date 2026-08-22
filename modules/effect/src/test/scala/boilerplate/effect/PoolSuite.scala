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

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import boilerplate.TypedError
import boilerplate.effect.Pool.*

final case class ConnError(detail: String) extends TypedError(detail, None)

final class Conn(val id: Int)

// The shape the `A <: AnyRef` bound is written for: an opaque handle over a reference.
opaque type Handle <: AnyRef = Conn
object Handle:
  def apply(c: Conn): Handle = c

  extension (h: Handle) def id: Int = h.id

// Every row runs under `TestControl.executeEmbed`, so waiting, deadlines and idle expiry are virtual
// time and every assertion compares values rather than elapsed wall clock.
class PoolSuite extends CatsEffectSuite:

  // A factory that counts what it built and destroyed, and can be told to fail.
  final private class Factory(created: Ref[IO, Int], destroyed: Ref[IO, Int], val failing: Ref[IO, Boolean]):
    val create: EffResource[ConnError, Conn] =
      Resource.make(
        failing.get.flatMap(f => if f then IO.raiseError(ConnError("down")) else created.updateAndGet(_ + 1).map(new Conn(_)))
      )(_ => destroyed.update(_ + 1))

    def counts: IO[(Int, Int)] = created.get.flatMap(c => destroyed.get.map(d => (c, d)))

  private object Factory:
    def make: IO[Factory] = IO.ref(0).flatMap(c => IO.ref(0).flatMap(d => IO.ref(false).map(f => new Factory(c, d, f))))

  private def run[A](io: IO[A]): IO[A] = TestControl.executeEmbed(io)

  test("sequential leases share one entry"):
    run {
      for
        f <- Factory.make
        r <- Pool(f.create, Config(2))
               .use(p => p.lease.use(a => IO.pure(a.id)).absolve.flatMap(first => p.lease.use(a => IO.pure((first, a.id))).absolve))
               .absolve
        c <- f.counts
      yield
        assertEquals(r, (1, 1))
        assertEquals(c, (1, 1))
    }

  test("Fail posture: a lease beyond capacity is Exhausted, and entries are destroyed at pool close"):
    run {
      for
        f <- Factory.make
        outcome <- Pool(f.create, Config(2))
                     .use(p => p.lease.use(_ => p.lease.use(_ => p.lease.use(_ => IO.unit).either.absolve).absolve).absolve)
                     .absolve
        c <- f.counts
      yield
        assertEquals(outcome, Left(Exhausted(2)))
        assertEquals(c, (2, 2))
    }

  test("Wait posture: a waiter is served when an entry returns within the deadline"):
    run {
      for
        f <- Factory.make
        r <- Pool(f.create, Config(1).withExhaustion(Exhaustion.Wait(5.seconds))).use { p =>
               val holder = p.lease.use(_ => IO.sleep(2.seconds)).absolve
               val waiter = IO.sleep(100.millis) *> p.lease.use(a => IO.pure(a.id)).absolve
               IO.both(holder, waiter).map(_._2)
             }.absolve
        c <- f.counts
      yield
        assertEquals(r, 1)
        assertEquals(c, (1, 1))
    }

  test("Wait posture: a waiter past the deadline is Exhausted, and capacity is restored afterwards"):
    run {
      for
        f <- Factory.make
        r <- Pool(f.create, Config(1).withExhaustion(Exhaustion.Wait(1.second))).use { p =>
               val holder = p.lease.use(_ => IO.sleep(3.seconds)).absolve
               val waiter = IO.sleep(100.millis) *> p.lease.use(_ => IO.unit).either.absolve
               IO.both(holder, waiter).map(_._2).flatMap(w => p.lease.use(a => IO.pure((w, a.id))).absolve)
             }.absolve
      yield assertEquals(r, (Left(Exhausted(1)), 1))
    }

  test("a cancelled waiter leaks no permit"):
    run {
      for
        f <- Factory.make
        r <- Pool(f.create, Config(1).withExhaustion(Exhaustion.Wait(10.seconds))).use { p =>
               p.lease
                 .use { _ =>
                   for
                     fibre <- p.lease.use(_ => IO.unit).absolve.start
                     _ <- IO.sleep(100.millis)
                     _ <- fibre.cancel
                     s <- Pool.stats(p).absolve
                   yield s
                 }
                 .absolve
                 .flatMap(s => p.lease.use(a => IO.pure((s, a.id))).absolve)
             }.absolve
      yield assertEquals(r, (Stats(1, 0, 1, 0), 1))
    }

  test("two waiters under Wait are served in the order they arrived"):
    run {
      for
        f <- Factory.make
        order <- IO.ref(List.empty[Int])
        _ <- Pool(f.create, Config(1).withExhaustion(Exhaustion.Wait(30.seconds))).use { p =>
               def waiter(label: Int, after: FiniteDuration) =
                 IO.sleep(after) *> p.lease.use(_ => order.update(label :: _) *> IO.sleep(1.second)).absolve
               p.lease.use(_ => IO.sleep(2.seconds)).absolve.both(waiter(1, 100.millis).both(waiter(2, 200.millis))).void
             }.absolve
        seen <- order.get
      yield assertEquals(seen.reverse, List(1, 2))
    }

  test("an unhealthy idle entry is destroyed and replaced, and the observer is told why"):
    run {
      for
        f <- Factory.make
        events <- IO.ref(List.empty[Event])
        r <- Pool(f.create, Config(2).withObserver(e => events.update(e :: _)), c => Eff.succeed(c.id != 1))
               .use(p => p.lease.use(a => IO.pure(a.id)).absolve.flatMap(first => p.lease.use(a => IO.pure((first, a.id))).absolve))
               .absolve
        c <- f.counts
        seen <- events.get
      yield
        assertEquals(r, (1, 2))
        assertEquals(c, (2, 2))
        assertEquals(seen.reverse.collect { case Event.Destroyed(reason) => reason }, List(Reason.Unhealthy, Reason.Closed))
    }

  test("an expired idle entry is destroyed on the next lease, and the observer is told why"):
    run {
      for
        f <- Factory.make
        events <- IO.ref(List.empty[Event])
        r <- Pool(f.create, Config(2).withIdleTimeout(1.second).withObserver(e => events.update(e :: _))).use { p =>
               p.lease
                 .use(a => IO.pure(a.id))
                 .absolve
                 .flatMap(first => IO.sleep(2.seconds) *> p.lease.use(a => IO.pure((first, a.id))).absolve)
             }.absolve
        c <- f.counts
        seen <- events.get
      yield
        assertEquals(r, (1, 2))
        assertEquals(c, (2, 2))
        assertEquals(seen.reverse.collect { case Event.Destroyed(reason) => reason }, List(Reason.Expired, Reason.Closed))
    }

  test("an invalidated entry is destroyed on return rather than reused, and the observer is told why"):
    run {
      for
        f <- Factory.make
        events <- IO.ref(List.empty[Event])
        r <- Pool(f.create, Config(2).withObserver(e => events.update(e :: _))).use { p =>
               p.lease
                 .use(a => p.invalidate(a).absolve.as(a.id))
                 .absolve
                 .flatMap(first => p.lease.use(a => IO.pure((first, a.id))).absolve)
             }.absolve
        c <- f.counts
        seen <- events.get
      yield
        assertEquals(r, (1, 2))
        assertEquals(c, (2, 2))
        assertEquals(seen.reverse.collect { case Event.Destroyed(reason) => reason }, List(Reason.Invalidated, Reason.Closed))
    }

  test("a creation failure surfaces as the factory's typed error with the permit restored"):
    run {
      for
        f <- Factory.make
        r <- Pool(f.create, Config(1)).use { p =>
               for
                 _ <- f.failing.set(true)
                 e <- p.lease.use(_ => IO.unit).either.absolve
                 _ <- f.failing.set(false)
                 ok <- p.lease.use(a => IO.pure(a.id)).absolve
               yield (e, ok)
             }.absolve
      yield assertEquals(r, (Left(ConnError("down")), 1))
    }

  test("the observer sees each event and stats snapshots the pool"):
    run {
      for
        f <- Factory.make
        events <- IO.ref(List.empty[Event])
        st <- Pool(f.create, Config(3).withObserver(e => events.update(e :: _)))
                .use(p => p.lease.use(_ => Pool.stats(p).absolve).absolve)
                .absolve
        seen <- events.get
      yield
        assertEquals(st, Stats(3, 0, 1, 0))
        assertEquals(seen.reverse, List(Event.Created, Event.Leased(false), Event.Returned, Event.Destroyed(Reason.Closed)))
    }

  test("a lease after the pool's scope has closed is a defect, not a typed failure"):
    run {
      for
        f <- Factory.make
        escaped <- Pool(f.create, Config(1)).use(p => IO.pure(p)).absolve
        outcome <- escaped.lease.use(a => IO.pure(a.id)).absolve.attempt
      yield assert(
        outcome.left.exists(_.isInstanceOf[IllegalStateException]), // scalafix:ok DisableSyntax.isInstanceOf
        s"expected a closed-pool defect, got: $outcome"
      )
    }

  test("a non-positive capacity is a programmer error and raises when the config is built"):
    val zero = intercept[IllegalArgumentException](Config(0))
    val negative = intercept[IllegalArgumentException](Config(-1))
    assert(zero.getMessage.contains("capacity must be positive"))
    assert(negative.getMessage.contains("capacity must be positive"))

  test("the Fail posture reports the exhaustion to the observer"):
    run {
      for
        f <- Factory.make
        events <- IO.ref(List.empty[Event])
        outcome <- Pool(f.create, Config(1).withObserver(e => events.update(e :: _)))
                     .use(p => p.lease.use(_ => p.lease.use(_ => IO.unit).either.absolve).absolve)
                     .absolve
        seen <- events.get
      yield
        assertEquals(outcome, Left(Exhausted(1)))
        assertEquals(seen.count(_ == Event.Exhausted), 1)
    }

  test("a queued waiter is counted in stats and reported as an idle lease when it is served"):
    run {
      for
        f <- Factory.make
        events <- IO.ref(List.empty[Event])
        config = Config(1).withExhaustion(Exhaustion.Wait(10.seconds)).withObserver(e => events.update(e :: _))
        st <- Pool(f.create, config).use { p =>
                p.lease
                  .use { _ =>
                    for
                      waiter <- p.lease.use(_ => IO.unit).absolve.start
                      _ <- IO.sleep(100.millis)
                      s <- Pool.stats(p).absolve
                    yield (s, waiter)
                  }
                  .absolve
                  .flatMap((s, waiter) => waiter.join.as(s))
              }.absolve
        seen <- events.get
      yield
        assertEquals(st, Stats(1, 0, 1, 1))
        assertEquals(seen.reverse.collect { case Event.Leased(idle) => idle }, List(false, true))
        assert(!seen.contains(Event.Exhausted), seen.toString)
    }

  test("Config's only door is the validated one - the raw constructor and copy are out of reach"):
    assert(scala.compiletime.testing.typeChecks("boilerplate.effect.Pool.Config(1)"))
    assert(
      !scala.compiletime.testing.typeChecks(
        "boilerplate.effect.Pool.Config(0, boilerplate.effect.Pool.Exhaustion.Fail, None, (_: boilerplate.effect.Pool.Event) => cats.effect.IO.unit)"
      )
    )
    assert(!scala.compiletime.testing.typeChecks("boilerplate.effect.Pool.Config(1).copy(capacity = 0)"))

  test("a pooled value is a reference - a primitive element type is refused with the bound"):
    val errors = scala.compiletime.testing.typeCheckErrors(
      "val p: boilerplate.effect.Pool[boilerplate.effect.ConnError, Int] = ???"
    )
    assert(errors.nonEmpty)
    assert(errors.exists(_.message.contains("AnyRef")), errors.map(_.message).mkString("; "))
    assert(
      scala.compiletime.testing.typeChecks("val p: boilerplate.effect.Pool[boilerplate.effect.ConnError, boilerplate.effect.Handle] = ???")
    )

  test("invalidate destroys exactly the invalidated entry, an opaque AnyRef handle included"):
    run {
      for
        created <- IO.ref(0)
        destroyed <- IO.ref(0)
        handles = EffResource.make(created.updateAndGet(_ + 1).map(n => Handle(new Conn(n))))(_ => destroyed.update(_ + 1))
        r <- Pool(handles, Config(2)).use { p =>
               for
                 // Both entries are live at once, so invalidation has to pick one by identity
                 // rather than land on whichever was returned last.
                 held <- p.lease
                           .use(first => p.lease.use(second => p.invalidate(first).absolve.as((first.id, second.id))).absolve)
                           .absolve
                 next <- p.lease.use(a => p.lease.use(b => IO.pure((a.id, b.id))).absolve).absolve
                 during <- destroyed.get
               yield (held, next, during)
             }.absolve
        c <- created.get
      yield
        assertEquals(r, ((1, 2), (2, 3), 1))
        assertEquals(c, 3)
    }

  test("the lease channel is exactly the factory's error or Exhausted"):
    assert(
      scala.compiletime.testing.typeChecks(
        "def f(p: boilerplate.effect.Pool[boilerplate.effect.ConnError, boilerplate.effect.Conn]): boilerplate.effect.EffResource[boilerplate.effect.ConnError | boilerplate.effect.Pool.Exhausted, boilerplate.effect.Conn] = p.lease"
      )
    )
    assert(
      !scala.compiletime.testing.typeChecks(
        "def f(p: boilerplate.effect.Pool[boilerplate.effect.ConnError, boilerplate.effect.Conn]): boilerplate.effect.EffResource[boilerplate.effect.ConnError, boilerplate.effect.Conn] = p.lease"
      )
    )
end PoolSuite
