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

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Semaphore
import cats.syntax.foldable.*

import boilerplate.TypedError

/** A bounded pool of `A`s built by `create`. A lease hands out an idle entry or builds a new one
  * within `capacity` and returns it on release; entries are destroyed when unhealthy, expired,
  * invalidated, or when the pool's own scope ends.
  *
  * Capacity is a `Semaphore` and eviction happens on lease, so a pool holds no background fibre and
  * no timer: an idle entry that has expired or gone unhealthy is destroyed when it is next reached
  * for, and no stale entry is ever lent.
  *
  * A pooled value is a reference: entries are tracked by identity, so an opaque handle type over a
  * reference declares `<: AnyRef` to be poolable.
  *
  * Refer to [[boilerplate.effect.Pool$ Pool]] for construction, leasing, and observation.
  */
final class Pool[E <: Throwable, A <: AnyRef] private[effect] (
  private[effect] val config: Pool.Config,
  private[effect] val permits: Semaphore[IO],
  private[effect] val state: Ref[IO, Pool.State[A]],
  private[effect] val create: EffResource[E, A],
  private[effect] val healthy: A => UEff[Boolean]
)

/** Provides construction, leasing, and observation for [[boilerplate.effect.Pool Pool]]. */
object Pool:
  /** A lease found no entry free: raised at once under [[Exhaustion.Fail Fail]], at the deadline
    * under [[Exhaustion.Wait Wait]].
    */
  final case class Exhausted(capacity: Int) extends TypedError(s"pool of $capacity exhausted", None)

  /** What a lease does when every entry is in use. */
  enum Exhaustion derives CanEqual:
    /** Raise [[Exhausted]] immediately. */
    case Fail

    /** Wait for an entry, raising [[Exhausted]] once `deadline` passes. */
    case Wait(deadline: FiniteDuration)

  /** What a pool did, as reported to [[Config.withObserver withObserver]]. */
  enum Event derives CanEqual:
    /** An entry was built by `create`. */
    case Created

    /** An entry came back to the idle list. */
    case Returned

    /** An entry was handed to a lease; `idle` distinguishes reuse from a fresh build. */
    case Leased(idle: Boolean)

    /** An entry's finaliser ran. */
    case Destroyed(reason: Reason)

    /** A lease found no entry free. */
    case Exhausted
  end Event

  /** Why an entry was destroyed. */
  enum Reason derives CanEqual:
    /** The health check refused the idle entry a lease had reached for. */
    case Unhealthy

    /** The entry had been idle for longer than `idleTimeout` when a lease reached for it. */
    case Expired

    /** A consumer called `invalidate` on the entry before releasing its lease. */
    case Invalidated

    /** The pool's own scope ended, so the entry was destroyed instead of kept. */
    case Closed
  end Reason

  /** A pool's settings; build with `Config(capacity)` and refine through the `with` methods. */
  final case class Config private (capacity: Int, exhaustion: Exhaustion, idleTimeout: Option[FiniteDuration], observer: Event => IO[Unit])

  /** Provides the constructor and the refinements for [[boilerplate.effect.Pool.Config Config]]. */
  object Config:
    /** A pool of `capacity` entries that fails rather than waits, keeps idle entries indefinitely,
      * and observes nothing. A non-positive `capacity` is a programmer error and raises.
      */
    def apply(capacity: Int): Config =
      require(capacity > 0, "capacity must be positive")
      Config(capacity, Exhaustion.Fail, None, _ => IO.unit)

    extension (c: Config)
      /** The same settings, with `e` deciding what an exhausted lease does. */
      def withExhaustion(e: Exhaustion): Config = c.copy(exhaustion = e)

      /** The same settings, destroying an entry idle for longer than `d` when it is next reached
        * for.
        */
      def withIdleTimeout(d: FiniteDuration): Config = c.copy(idleTimeout = Some(d))

      /** The same settings, reporting every [[Event]] to `f`; anything `f` raises is a defect. */
      def withObserver(f: Event => IO[Unit]): Config = c.copy(observer = f)
  end Config

  /** A snapshot of a pool: how many entries are idle, how many are lent, and how many leases are
    * waiting for one.
    */
  final case class Stats(capacity: Int, idle: Int, inUse: Int, waiting: Int) derives CanEqual

  final private[effect] case class Entry[A <: AnyRef](value: A, release: Resource.ExitCase => IO[Unit], idleSince: FiniteDuration)
  final private[effect] case class State[A <: AnyRef](idle: List[Entry[A]], inUse: List[Entry[A]], invalidated: List[A], closed: Boolean)

  /** A pool over `create`, closed when its own scope ends: every idle entry is destroyed then, and
    * an entry still lent is destroyed when its lease releases it.
    */
  def apply[E <: Throwable, A <: AnyRef](create: EffResource[E, A], config: Config): EffResource[Nothing, Pool[E, A]] =
    apply(create, config, _ => Eff.succeed(true))

  /** As the two-argument form, checking `healthy` on an idle entry before lending it: an entry that
    * reports `false` is destroyed and the lease moves on to the next, or builds one.
    */
  def apply[E <: Throwable, A <: AnyRef](
    create: EffResource[E, A],
    config: Config,
    healthy: A => UEff[Boolean]
  ): EffResource[Nothing, Pool[E, A]] =
    Resource.make(
      for
        permits <- Semaphore[IO](config.capacity.toLong)
        state <- IO.ref(State[A](Nil, Nil, Nil, closed = false))
      yield new Pool(config, permits, state, create, healthy)
    )(p => close(p))

  private def close[E <: Throwable, A <: AnyRef](p: Pool[E, A]): IO[Unit] =
    p.state.modify(s => (s.copy(idle = Nil, closed = true), s.idle)).flatMap { idle =>
      idle.traverse_(e => destroy(p, e, Reason.Closed))
    }

  private def destroy[E <: Throwable, A <: AnyRef](p: Pool[E, A], e: Entry[A], reason: Reason): IO[Unit] =
    e.release(Resource.ExitCase.Succeeded).guarantee(p.config.observer(Event.Destroyed(reason)))

  /** A snapshot of `p`, consistent per field rather than across them: a concurrent lease may move
    * an entry between the counts while they are read.
    */
  def stats[E <: Throwable, A <: AnyRef](p: Pool[E, A]): UEff[Stats] =
    for
      s <- p.state.get
      // Waiters are the semaphore's negative count; `permits.count` reports it directly.
      c <- p.permits.count
    yield Stats(p.config.capacity, s.idle.length, s.inUse.length, if c < 0 then (-c).toInt else 0)

  extension [E <: Throwable, A <: AnyRef](p: Pool[E, A])
    /** Leases an entry for the resource's scope: an idle one, health-checked and unexpired, or a
      * freshly built one within capacity. The entry returns to the pool on release - after success,
      * after a typed failure, and after cancellation alike - so the channel is the factory's own
      * error or [[Exhausted]] and nothing else. Leasing from a pool whose scope has already ended
      * is a programmer error and raises rather than joining that channel.
      */
    def lease: EffResource[E | Exhausted, A] =
      Resource.applyFull[IO, A] { poll =>
        val permit: IO[Unit] = p.config.exhaustion match
          case Exhaustion.Fail =>
            p.permits.tryAcquire.flatMap(ok =>
              if ok then IO.unit else p.config.observer(Event.Exhausted) *> IO.raiseError(Exhausted(p.config.capacity))
            )
          case Exhaustion.Wait(deadline) =>
            poll(p.permits.acquire).timeoutTo(deadline, p.config.observer(Event.Exhausted) *> IO.raiseError(Exhausted(p.config.capacity)))
        // A failed or cancelled obtain has no entry to give back, so the permit is returned here.
        permit *> poll(obtain(p))
          .guaranteeCase {
            case Outcome.Succeeded(_) => IO.unit
            case _                    => p.permits.release
          }
          .map(entry => (entry.value, (ec: Resource.ExitCase) => giveBack(p, entry, ec)))
      }

    /** Marks the leased `a` for destruction on return instead of reuse - the seam for a consumer
      * that knows the entry is broken, which a typed failure during use does not by itself imply.
      */
    def invalidate(a: A): UEff[Unit] = p.state.update(s => s.copy(invalidated = a :: s.invalidated))
  end extension

  // Pops idle entries until one is fresh and healthy, destroying the rest; creates when none is left.
  private def obtain[E <: Throwable, A <: AnyRef](p: Pool[E, A]): IO[Entry[A]] =
    IO.monotonic.flatMap { now =>
      p.state
        .modify { s =>
          if s.closed then (s, Left(new IllegalStateException("pool is closed")))
          else
            s.idle match
              case e :: rest => (s.copy(idle = rest), Right(Some(e)))
              case Nil       => (s, Right(None))
        }
        .flatMap {
          case Left(defect)   => IO.raiseError(defect)
          case Right(Some(e)) =>
            val expired = p.config.idleTimeout.exists(t => now - e.idleSince > t)
            if expired then destroy(p, e, Reason.Expired) *> obtain(p)
            else
              p.healthy(e.value).absolve.flatMap { ok =>
                if ok then lend(p, e, idle = true) else destroy(p, e, Reason.Unhealthy) *> obtain(p)
              }
          case Right(None) =>
            p.create.absolve.allocatedCase.flatMap { (a, rel) =>
              p.config.observer(Event.Created) *> lend(p, Entry(a, rel, now), idle = false)
            }
        }
    }

  private def lend[E <: Throwable, A <: AnyRef](p: Pool[E, A], e: Entry[A], idle: Boolean): IO[Entry[A]] =
    p.state.update(s => s.copy(inUse = e :: s.inUse)) *> p.config.observer(Event.Leased(idle)).as(e)

  private def giveBack[E <: Throwable, A <: AnyRef](p: Pool[E, A], e: Entry[A], @scala.annotation.unused ec: Resource.ExitCase): IO[Unit] =
    IO.monotonic.flatMap { now =>
      p.state
        .modify { s =>
          val invalid = s.invalidated.exists(_ eq e.value)
          val remaining = s.inUse.filterNot(_ eq e)
          val cleared = s.invalidated.filterNot(_ eq e.value)
          if s.closed then (s.copy(inUse = remaining, invalidated = cleared), Some(Reason.Closed))
          else if invalid then (s.copy(inUse = remaining, invalidated = cleared), Some(Reason.Invalidated))
          else (s.copy(inUse = remaining, idle = e.copy(idleSince = now) :: s.idle, invalidated = cleared), None)
        }
        .flatMap {
          case Some(reason) => destroy(p, e, reason)
          case None         => p.config.observer(Event.Returned)
        }
        .guarantee(p.permits.release)
    }
end Pool
