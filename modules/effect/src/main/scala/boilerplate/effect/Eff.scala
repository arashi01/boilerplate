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

import scala.annotation.targetName
import scala.annotation.unused
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import cats.Monad
import cats.MonadError
import cats.Monoid
import cats.Parallel
import cats.Semigroup
import cats.SemigroupK
import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Fiber
import cats.effect.kernel.Outcome
import cats.effect.kernel.Resource

import boilerplate.ErrorTest

/** Typed-error effect over `cats.effect.IO`, represented as a PHANTOM on `IO`'s own error channel:
  * the representation is exactly `IO[A]`, and `E <: Throwable` exists only at compile time. A real
  * failure rides `IO`'s native `Throwable` channel, so the happy path IS `IO` - no `Either`
  * allocation - and `absolve` is O(0) identity.
  *
  * `IO[A]` is declared as a supertype of `Eff[E, A]`, so any `IO` value flows into an `Eff`-typed
  * position by subtyping alone: no conversion, no import, no compiler flag. The relation is
  * one-directional - reaching `IO` from a typed channel needs the explicit `absolve`. Lifting
  * commits nothing about the error channel: an `IO` placed where `Eff[E, A]` is expected simply IS
  * that value, and the channel a context claims is the channel its observers filter by.
  *
  * ==Composition==
  *
  * Every combinator that joins two channels yields their PRECISE union, so a for-comprehension over
  * steps that fail differently infers `Eff[NotFound | Invalid, A]` with no ascription, and reifying
  * it gives an `Either` whose `Left` matches exhaustively.
  *
  * Two shapes still lose the union, both to Scala's own widening of an inferred union rather than
  * to anything here. A continuation whose channel comes from an `if` or `match` over branches that
  * fail differently infers their JOIN, so ascribe the lambda's result (or its branches) where the
  * precise union matters. An enum's simple case widens to the enum type
  * (`Eff.fail(Refused.Malformed)` is `Eff[Refused, Nothing]`), so a channel that is a strict subset
  * of an enum's simple cases has to be ascribed; `case object` arms and parameterised enum cases
  * are unaffected.
  *
  * ==Observation==
  *
  * Observing the typed channel (`either`, `catchAll`, `mapError`, `fold`, ...) filters the caught
  * `Throwable` by [[boilerplate.ErrorTest ErrorTest]], re-raising any non-`E` defect unchanged. For
  * a concrete `E` - a class, a `case object`, or a union of them - the evidence is derived at the
  * call site with no `using` clause written. Generic code takes `using ErrorTest[E]` and can then
  * derive evidence for a channel it has widened with a concrete arm (`ErrorTest[IOError | E]`). A
  * channel that is an inferred join of unrelated roots is an intersection, and deriving evidence
  * for it is a compile error naming the remedy - observing it would capture unrelated failures as
  * typed.
  *
  * On the infallible channel ([[UEff]], `E = Nothing`) the typed error is uninhabited, so these
  * observers are degenerate - a defect always propagates and any handler is dead code.
  *
  * An API of your own that is GENERIC in `E` and threads `using ErrorTest[E]` must pin `E` from a
  * covariant value parameter (an effect or handler, ordered before the evidence) and ship an
  * `E = Nothing` overload: where `E` is left to inference against the evidence alone, the solver
  * silently widens it to `Throwable`, whose test captures every defect. The same holds for any
  * channel variable a `using` witness alone constrains - bound it by the channel it refines (as
  * `catchOnly`'s infallible-handler twin bounds its residual by `E`), or the solver discharges the
  * witness by widening the variable to `Throwable`. The combinators here follow exactly that
  * discipline.
  *
  * Reach this surface with `import boilerplate.effect.*`. Every combinator whose name cats or
  * cats-effect syntax also provides is carried at package level as well as here, and only a
  * wildcard import puts that copy ahead of an imported conversion - naming `Eff` in the import
  * instead leaves such calls to the conversion, which pins one channel where the union belongs. The
  * README's `cats interop` section lists the names and what a captured call costs.
  *
  * Refer to [[boilerplate.effect.Eff$ Eff]] for constructors, combinators, and type class
  * instances.
  */
opaque type Eff[+E <: Throwable, +A] >: IO[A] = IO[A]

/** Infallible effect: [[boilerplate.effect.Eff Eff]] with `Nothing` errors. */
type UEff[+A] = Eff[Nothing, A]

/** Throwable-errored effect: [[boilerplate.effect.Eff Eff]] over `Throwable`. */
type TEff[+A] = Eff[Throwable, A]

/** Provides constructors, combinators, and type class instances for [[boilerplate.effect.Eff Eff]]. */
object Eff extends EffInstances:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.Eff Eff]]. */
  type Of[E <: Throwable] = [A] =>> Eff[E, A]

  // Reifies the typed channel into an `Either`; a non-`E` defect propagates on `IO`'s channel.
  private def reify[E <: Throwable, A](io: IO[A])(using et: ErrorTest[E]): IO[Either[E, A]] =
    io.map(a => Right(a): Either[E, A]).handleErrorWith { t =>
      if et.test(t) then IO.pure(Left(t.asInstanceOf[E])) else IO.raiseError(t) // scalafix:ok DisableSyntax.asInstanceOf
    }

  /** Creates a successful computation. */
  def succeed[A](a: A): UEff[A] = IO.pure(a)

  /** Creates a failed computation. */
  def fail[E <: Throwable](e: E): Eff[E, Nothing] = IO.raiseError(e)

  /** Lifts a pure `Either` into the effect. */
  def from[E <: Throwable, A](either: Either[E, A]): Eff[E, A] =
    either match
      case Right(a) => IO.pure(a)
      case Left(e)  => IO.raiseError(e)

  /** Converts an `Option`, supplying an error when empty. */
  def from[E <: Throwable, A](opt: Option[A], ifNone: => E): Eff[E, A] =
    opt match
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(ifNone)

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  def from[E <: Throwable, A](result: Try[A], ifFailure: Throwable => E): Eff[E, A] =
    result.fold(th => fail(ifFailure(th)), succeed(_))

  /** Extracts the underlying computation from `EitherT`. */
  def from[E <: Throwable, A](et: EitherT[IO, E, A]): Eff[E, A] = lift(et.value)

  /** Canonical successful unit value, interned and shared across call sites. */
  val unit: UEff[Unit] = IO.unit

  /** Absorbs an existing `IO[Either[E, A]]` into the typed channel; a `Left` fails on `IO`'s
    * channel.
    */
  def lift[E <: Throwable, A](io: IO[Either[E, A]]): Eff[E, A] =
    io.flatMap {
      case Right(a) => IO.pure(a)
      case Left(e)  => IO.raiseError(e)
    }

  /** Converts an `IO[Option[A]]`, supplying an error when empty. */
  def lift[E <: Throwable, A](io: IO[Option[A]], ifNone: => E): Eff[E, A] =
    io.flatMap {
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(ifNone)
    }

  /** Captures throwables raised in `IO`, translating them via `ifFailure`. */
  def attempt[E <: Throwable, A](io: IO[A], ifFailure: Throwable => E): Eff[E, A] =
    io.handleErrorWith(t => IO.raiseError(ifFailure(t)))

  /** Captures matching throwables as typed errors; unmatched throwables propagate as defects in
    * `IO`'s error channel.
    */
  def attempt[E <: Throwable, A](io: IO[A])(pf: PartialFunction[Throwable, E]): Eff[E, A] =
    io.handleErrorWith(t => if pf.isDefinedAt(t) then IO.raiseError(pf(t)) else IO.raiseError(t))

  /** Suspends evaluation until demanded. */
  def defer[E <: Throwable, A](thunk: => Eff[E, A]): Eff[E, A] = IO.defer(thunk)

  /** Suspends a side-effecting computation that yields an `Either[E, A]`; for an infallible side
    * effect use [[suspend]].
    */
  def delay[E <: Throwable, A](ea: => Either[E, A]): Eff[E, A] = lift(IO.delay(ea))

  /** Suspends a synchronous side effect as a success value; for typed errors use [[delay]]. */
  def suspend[A](thunk: => A): UEff[A] = IO.delay(thunk)

  /** As [[delay]], on the blocking thread pool - for synchronous work that blocks a thread. */
  def blocking[E <: Throwable, A](ea: => Either[E, A]): Eff[E, A] = lift(IO.blocking(ea))

  /** As [[suspend]], on the blocking thread pool - for synchronous work that blocks a thread. */
  def suspendBlocking[A](thunk: => A): UEff[A] = IO.blocking(thunk)

  /** Suspends execution for the specified duration. */
  def sleep(duration: FiniteDuration): UEff[Unit] = IO.sleep(duration)

  /** Returns the current monotonic time as a `FiniteDuration`. */
  def monotonic: UEff[FiniteDuration] = IO.monotonic

  /** Returns the current wall-clock time as a `FiniteDuration` since the epoch. */
  def realTime: UEff[FiniteDuration] = IO.realTime

  /** Introduces a self-cancellation point, immediately cancelling the current fibre. */
  val canceled: UEff[Unit] = IO.canceled

  /** Introduces a cooperative yielding point. */
  val cede: UEff[Unit] = IO.cede

  /** A computation that never completes. */
  val never: UEff[Nothing] = IO.never

  /** Converts a `Future` into an `Eff`, translating failures via `ifFailure`. */
  def fromFuture[E <: Throwable, A](future: IO[Future[A]], ifFailure: Throwable => E): Eff[E, A] =
    IO.fromFuture(future).handleErrorWith(t => IO.raiseError(ifFailure(t)))

  /** Converts a `Future` into an `Eff`, catching matching throwables as typed errors; unmatched
    * throwables propagate as defects in `IO`'s error channel.
    */
  def fromFuture[E <: Throwable, A](future: IO[Future[A]])(pf: PartialFunction[Throwable, E]): Eff[E, A] =
    IO.fromFuture(future).handleErrorWith(t => if pf.isDefinedAt(t) then IO.raiseError(pf(t)) else IO.raiseError(t))

  /** Suspends an asynchronous callback-driven computation completing with a typed `Either[E, A]`.
    *
    * The callback is invoked with `Left(e)` for a typed error or `Right(a)` for success - there is
    * no defect-channel nesting. A raised throwable surfaces as a defect in `IO`'s error channel;
    * use [[asyncAttempt]] to fold it into a typed error instead. The returned
    * `IO[Option[IO[Unit]]]` optionally yields a finaliser run on cancellation.
    */
  def async[E <: Throwable, A](k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]): Eff[E, A] =
    IO.async[A](cb => k(ea => cb(ea)))

  /** As [[async]], additionally folding a throwable raised while registering the callback into a
    * typed error via `ifDefect`. A typed error delivered through the callback (`Left(e)`) passes
    * through unchanged, and cancellation is never folded. Needs no evidence, so it works for an
    * abstract `E` - a registration-time failure is a defect by construction.
    */
  def asyncAttempt[E <: Throwable, A](ifDefect: Throwable => E)(
    k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]
  ): Eff[E, A] =
    IO.async[A](cb => k(ea => cb(ea)).handleErrorWith(t => IO.raiseError(ifDefect(t))))

  /** Executes `eff` only when `cond` is true, otherwise succeeds with `Unit`. */
  def when[E <: Throwable](cond: Boolean)(eff: => Eff[E, Unit]): Eff[E, Unit] =
    if cond then eff else unit

  /** Executes `eff` only when `cond` is false, otherwise succeeds with `Unit`. */
  def unless[E <: Throwable](cond: Boolean)(eff: => Eff[E, Unit]): Eff[E, Unit] =
    if cond then unit else eff

  /** Raises an error when `cond` is true, otherwise succeeds with `Unit`. */
  def raiseWhen[E <: Throwable](cond: Boolean)(e: => E): Eff[E, Unit] =
    if cond then fail(e) else unit

  /** Raises an error when `cond` is false, otherwise succeeds with `Unit`. */
  def raiseUnless[E <: Throwable](cond: Boolean)(e: => E): Eff[E, Unit] =
    if cond then unit else fail(e)

  /** Lifts a Boolean predicate into a typed-error effect. Both branches are evaluated lazily; the
    * unselected branch is never run.
    */
  def cond[E <: Throwable, A](pred: Boolean, ifTrue: => A, ifFalse: => E): Eff[E, A] =
    if pred then succeed(ifTrue) else fail(ifFalse)

  /** Traverses a collection, short-circuiting on first error. */
  def traverse[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, List[B]] =
    as.foldLeft(IO.pure(List.empty[B]))((acc, a) => acc.flatMap(bs => (f(a): IO[B]).map(b => b :: bs))).map(_.reverse)

  /** Sequences a collection of effects, short-circuiting on first error. */
  def sequence[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, List[A]] =
    traverse(effs)(identity)

  /** Traverses a collection for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("traverseUnit")
  def traverse_[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, Unit] =
    as.foldLeft(IO.unit)((acc, a) => acc.flatMap(_ => (f(a): IO[B]).void))

  /** Runs a collection of effects for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("sequenceUnit")
  def sequence_[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, Unit] =
    traverse_(effs)(identity)

  /** Traverses a collection in parallel. */
  def parTraverse[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, List[B]] =
    val P = IO.parallelForIO
    val parF = P.applicative
    P.sequential(as.toList.foldRight(parF.pure(List.empty[B]))((a, acc) => parF.map2(P.parallel(f(a)), acc)(_ :: _)))

  /** Sequences a collection of effects in parallel. */
  def parSequence[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, List[A]] =
    parTraverse(effs)(identity)

  /** Traverses a collection in parallel for effect only, discarding results. */
  @targetName("parTraverseUnit")
  def parTraverse_[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, Unit] =
    val P = IO.parallelForIO
    val parF = P.applicative
    P.sequential(as.toList.foldRight(parF.pure(()))((a, acc) => parF.map2(P.parallel(f(a)), acc)((_, _) => ())))

  /** Sequences a collection of effects in parallel for effect only, discarding results. */
  @targetName("parSequenceUnit")
  def parSequence_[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, Unit] =
    parTraverse_(effs)(identity)

  /** Retries the effect on typed failures, paced and bounded by `policy`; the final typed error
    * propagates once the policy stops. A defect propagates without retrying.
    */
  def retry[E <: Throwable, A](eff: Eff[E, A], policy: RetryPolicy)(using ErrorTest[E]): Eff[E, A] =
    retryPolicyImpl(eff, policy, _ => true, (_, _, _) => IO.unit)

  /** As the policy overload, retrying only failures `retryOn` accepts; a rejected error propagates
    * immediately.
    */
  def retry[E <: Throwable, A](eff: Eff[E, A], policy: RetryPolicy, retryOn: E => Boolean)(using
    ErrorTest[E]
  ): Eff[E, A] =
    retryPolicyImpl(eff, policy, retryOn, (_, _, _) => IO.unit)

  /** As the policy overload, invoking `onRetry` with the 1-based number of the attempt that just
    * failed, its error, and the delay about to be slept - only when a retry will actually happen,
    * before its sleep. The side effect is a raw `IO[Unit]`: anything it raises propagates on `IO`'s
    * channel.
    */
  def retry[E <: Throwable, A](
    eff: Eff[E, A],
    policy: RetryPolicy,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using ErrorTest[E]): Eff[E, A] =
    retryPolicyImpl(eff, policy, _ => true, onRetry)

  /** As the policy overload, with both the `retryOn` filter and the `onRetry` observer. */
  def retry[E <: Throwable, A](
    eff: Eff[E, A],
    policy: RetryPolicy,
    retryOn: E => Boolean,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using ErrorTest[E]): Eff[E, A] =
    retryPolicyImpl(eff, policy, retryOn, onRetry)

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay.
    */
  def retry[A](eff: Eff[Nothing, A], @unused policy: RetryPolicy): Eff[Nothing, A] = eff

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay.
    */
  def retry[A](
    eff: Eff[Nothing, A],
    @unused policy: RetryPolicy,
    @unused retryOn: Nothing => Boolean
  ): Eff[Nothing, A] = eff

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay, no observation.
    */
  def retry[A](
    eff: Eff[Nothing, A],
    @unused policy: RetryPolicy,
    @unused onRetry: (Int, Nothing, FiniteDuration) => IO[Unit]
  ): Eff[Nothing, A] = eff

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay, no observation.
    */
  def retry[A](
    eff: Eff[Nothing, A],
    @unused policy: RetryPolicy,
    @unused retryOn: Nothing => Boolean,
    @unused onRetry: (Int, Nothing, FiniteDuration) => IO[Unit]
  ): Eff[Nothing, A] = eff

  // SplitMix64 mixer: jitter needs statistical spread only - platform-uniform, allocation-free,
  // and free of any randomness capability constraint at the call site.
  private def mixSeed(z0: Long): Long =
    val z1 = z0 + 0x9e3779b97f4a7c15L
    val z2 = (z1 ^ (z1 >>> 30)) * 0xbf58476d1ce4e5b9L
    val z3 = (z2 ^ (z2 >>> 27)) * 0x94d049bb133111ebL
    z3 ^ (z3 >>> 31)

  private def unitDouble(bits: Long): Double = (bits >>> 11).toDouble * 1.1102230246251565e-16

  // Mixed into each run's seed: coarse clocks (notably on JS) would otherwise hand concurrent
  // retry loops identical seeds, correlating their jitter and defeating its purpose.
  private val retrySeedCounter = new java.util.concurrent.atomic.AtomicLong(0L)

  // Half of Long.MaxValue nanoseconds: ample headroom below FiniteDuration's bounds for a single
  // clamped delay (the cumulative accumulator saturates separately - see `loop`).
  private val retryMaxNanos: Double = Long.MaxValue.toDouble * 0.5

  private def fromNanosClamped(nanos: Double): FiniteDuration =
    import scala.concurrent.duration.*
    if nanos <= 0d then Duration.Zero
    else if nanos >= retryMaxNanos then retryMaxNanos.toLong.nanos
    else nanos.toLong.nanos

  private def retryPolicyImpl[E <: Throwable, A](
    eff: Eff[E, A],
    policy: RetryPolicy,
    retryOn: E => Boolean,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using et: ErrorTest[E]): Eff[E, A] =
    import scala.concurrent.duration.Duration
    import RetryPolicy.Backoff

    def delayFor(attempt: Int, prev: FiniteDuration, rnd: Long): (FiniteDuration, Long) =
      policy.backoff match
        case Backoff.Constant(d)                  => (d, rnd)
        case Backoff.Exponential(initial, factor) =>
          (fromNanosClamped(initial.toNanos.toDouble * math.pow(factor, (attempt - 1).toDouble)), rnd)
        case Backoff.FullJitter(initial, factor) =>
          val ceiling = initial.toNanos.toDouble * math.pow(factor, (attempt - 1).toDouble)
          val next = mixSeed(rnd)
          (fromNanosClamped(unitDouble(next) * ceiling), next)
        case Backoff.Decorrelated(base, factor) =>
          val lo = base.toNanos.toDouble
          val hiRaw = prev.toNanos.toDouble * factor
          val (min, max) = if hiRaw >= lo then (lo, hiRaw) else (hiRaw, lo)
          val next = mixSeed(rnd)
          (fromNanosClamped(min + unitDouble(next) * (max - min)), next)

    // The accumulator is a saturating Long of nanoseconds, never FiniteDuration arithmetic:
    // `FiniteDuration.+` throws past its range, which would convert a typed-error retry loop into
    // a defect once enough delay accumulates (both operands are non-negative, so a negative sum
    // is the overflow signal).
    def loop(attempt: Int, prev: FiniteDuration, cumulativeNanos: Long, rnd: Long): IO[A] =
      (eff: IO[A]).handleErrorWith { t =>
        if !et.test(t) then IO.raiseError(t)
        else
          val e = t.asInstanceOf[E] // scalafix:ok DisableSyntax.asInstanceOf
          if !retryOn(e) then IO.raiseError(e)
          else if policy.maxAttempts.exists(attempt >= _) then IO.raiseError(e)
          else
            val (raw, rnd2) = delayFor(attempt, prev, rnd)
            val capped = policy.maxDelay.fold(raw)(cap => if raw > cap then cap else raw)
            val sum = cumulativeNanos + capped.toNanos
            val nextCumulativeNanos = if sum < 0 then Long.MaxValue else sum
            if policy.maxCumulativeDelay.exists(budget => nextCumulativeNanos > budget.toNanos) then IO.raiseError(e)
            else
              val slept =
                if capped > Duration.Zero then IO.sleep(capped).flatMap(_ => loop(attempt + 1, capped, nextCumulativeNanos, rnd2))
                else loop(attempt + 1, capped, nextCumulativeNanos, rnd2)
              onRetry(attempt, e, capped).flatMap(_ => slept)
          end if
      }

    // Decorrelated jitter's recurrence starts from the base; every other strategy ignores `prev`.
    val prev0 = policy.backoff match
      case Backoff.Decorrelated(base, _) => base
      case _                             => Duration.Zero

    // Seeded inside the effect so each RUN reseeds - re-running a shared program value must not
    // replay one jitter sequence.
    IO.monotonic.flatMap(now => loop(1, prev0, 0L, mixSeed(now.toNanos ^ retrySeedCounter.incrementAndGet())))
  end retryPolicyImpl

  extension [E <: Throwable, A](self: Eff[E, A])
    /** Reifies the typed channel, staying on the typed surface: the result is infallible, so a
      * following typed generator needs no marker, and `absolve` is the explicit `IO` exit. A
      * non-`E` defect propagates on `IO`'s channel.
      */
    def either(using ErrorTest[E]): UEff[Either[E, A]] = reify[E, A](self)

    /** Absorbs the typed error into `IO`. O(0) identity - the failure is already there. */
    def absolve: IO[A] = self

    /** Maps the success channel while preserving the error type. */
    def map[B](f: A => B): Eff[E, B] = (self: IO[A]).map(f)

    /** Sequences computations; the result fails with either channel. */
    def flatMap[E2 <: Throwable, B](f: A => Eff[E2, B]): Eff[E | E2, B] = (self: IO[A]).flatMap(f)

    /** Flat-maps the success through a pure `Either`-returning function; a `Left` fails. */
    def subflatMap[E2 <: Throwable, B](f: A => Either[E2, B]): Eff[E | E2, B] =
      (self: IO[A]).flatMap(a =>
        f(a) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** Transforms the entire reified `Either` structure. */
    def transform[E2 <: Throwable, B](f: Either[E, A] => Either[E2, B])(using ErrorTest[E]): Eff[E2, B] =
      reify[E, A](self).flatMap(ea =>
        f(ea) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** Handles any typed failure by switching to an alternative computation; a defect propagates. */
    def catchAll[E2 <: Throwable, B >: A](f: E => Eff[E2, B])(using et: ErrorTest[E]): Eff[E2, B] =
      (self: IO[B]).handleErrorWith(t =>
        if et.test(t) then f(t.asInstanceOf[E]) else IO.raiseError(t) // scalafix:ok DisableSyntax.asInstanceOf
      )

    /** Recovers the errors `pf` handles with an effect; unmatched errors stay on the channel, which
      * the handler's own channel joins. The effectful sibling of [[mapErrorPartial]], pairing with
      * [[catchAll]].
      */
    def catchSome[E2 <: Throwable, B >: A](pf: PartialFunction[E, Eff[E2, B]])(using et: ErrorTest[E]): Eff[E | E2, B] =
      (self: IO[B]).handleErrorWith { t =>
        if !et.test(t) then IO.raiseError(t)
        else
          val e = t.asInstanceOf[E] // scalafix:ok DisableSyntax.asInstanceOf
          if pf.isDefinedAt(e) then pf(e) else IO.raiseError(t)
      }

    /** Recovers the `H` arm of a union error with an effect, narrowing the channel to the residual
      * `R` (where `E <: R | H`); unmatched errors stay typed as `R`, and `f` may itself fail into
      * `R`. The handler's return type pins the residual - a handler re-failing into it ascribes the
      * residual root (`Eff.fail(e): Eff[R, Nothing]`), otherwise the solver pins `R` to the
      * failure's concrete subtype. An infallible handler selects the twin below instead:
      *
      * {{{
      * val consumed: Eff[IOError | AppError, Unit] = ...
      * consumed.catchOnly((app: AppError) => log(app)) // : Eff[IOError, Unit]
      * }}}
      */
    def catchOnly[H <: Throwable, R <: Throwable, B >: A](f: H => Eff[R, B])(using
      ev: E <:< (R | H),
      et: ErrorTest[H]
    ): Eff[R, B] =
      val _ = ev
      (self: IO[B]).handleErrorWith(t =>
        if et.test(t) then f(t.asInstanceOf[H]) else IO.raiseError(t) // scalafix:ok DisableSyntax.asInstanceOf
      )

    /** As [[catchOnly]], for an infallible handler, whose return type pins no residual: bounding
      * `R` by the receiver's channel lets the solver subtract the handled arm, so on a union
      * channel the residual is inferred narrow with no annotation - left unbounded it silently
      * widens to `Throwable`, whose test makes every later observer capture defects. A handler
      * whose domain covers the whole channel infers `Nothing`; a root-typed receiver stays bounded
      * by the root, which does not decompose into its arms.
      */
    @targetName("catchOnlyInfallible")
    def catchOnly[H <: Throwable, R <: E, B >: A](f: H => Eff[Nothing, B])(using
      ev: E <:< (R | H),
      et: ErrorTest[H]
    ): Eff[R, B] =
      val _ = ev
      (self: IO[B]).handleErrorWith(t =>
        if et.test(t) then f(t.asInstanceOf[H]) else IO.raiseError(t) // scalafix:ok DisableSyntax.asInstanceOf
      )

    /** Handles both error and success with effectful functions, allowing error type change. */
    def redeemAll[E2 <: Throwable, B](fe: E => Eff[E2, B], fa: A => Eff[E2, B])(using ErrorTest[E]): Eff[E2, B] =
      reify[E, A](self).flatMap {
        case Left(e)  => fe(e)
        case Right(a) => fa(a)
      }

    /** Folds over both channels; both are consumed, so the result is infallible. */
    def fold[B](fe: E => B, fa: A => B)(using ErrorTest[E]): UEff[B] =
      reify[E, A](self).map(_.fold(fe, fa))

    /** Effectfully folds both channels; the continuations are infallible (an `IO` lambda lands by
      * subtyping), so the result is too.
      */
    def foldF[B](fe: E => UEff[B], fa: A => UEff[B])(using ErrorTest[E]): UEff[B] =
      reify[E, A](self).flatMap(ea => ea.fold(fe, fa): IO[B])

    /** Transforms the error channel. */
    def mapError[E2 <: Throwable](f: E => E2)(using et: ErrorTest[E]): Eff[E2, A] =
      (self: IO[A]).handleErrorWith(t =>
        if et.test(t) then IO.raiseError(f(t.asInstanceOf[E])) else IO.raiseError(t) // scalafix:ok DisableSyntax.asInstanceOf
      )

    /** Transforms the error channel partially; unmatched errors stay on the channel. */
    def mapErrorPartial[E2 <: Throwable](pf: PartialFunction[E, E2])(using et: ErrorTest[E]): Eff[E | E2, A] =
      (self: IO[A]).handleErrorWith { t =>
        if !et.test(t) then IO.raiseError(t)
        else
          val e = t.asInstanceOf[E] // scalafix:ok DisableSyntax.asInstanceOf
          IO.raiseError(pf.applyOrElse(e, (x: E) => x))
      }

    /** Fallback to an alternative computation when this one fails with a typed error. */
    def alt[E2 <: Throwable, B >: A](that: => Eff[E2, B])(using et: ErrorTest[E]): Eff[E2, B] =
      (self: IO[B]).handleErrorWith(t => if et.test(t) then that else IO.raiseError(t))

    /** Recovers from any typed failure with a constant success value. */
    def orElseSucceed[B >: A](value: => B)(using et: ErrorTest[E]): UEff[B] =
      (self: IO[B]).handleErrorWith(t => if et.test(t) then IO.pure(value) else IO.raiseError(t))

    /** Replaces any typed failure with a different error. */
    def orElseFail[E2 <: Throwable](error: => E2)(using et: ErrorTest[E]): Eff[E2, A] =
      (self: IO[A]).handleErrorWith(t => if et.test(t) then IO.raiseError(error) else IO.raiseError(t))

    /** Recovers from all typed errors by mapping them to a success value. */
    def valueOr(f: E => A)(using et: ErrorTest[E]): UEff[A] =
      (self: IO[A]).handleErrorWith(t =>
        if et.test(t) then IO.pure(f(t.asInstanceOf[E])) else IO.raiseError(t) // scalafix:ok DisableSyntax.asInstanceOf
      )

    /** Observes typed failures without altering the result.
      *
      * The side effect is a raw `IO[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    def tapError(f: E => IO[Unit])(using et: ErrorTest[E]): Eff[E, A] =
      (self: IO[A]).handleErrorWith { t =>
        if !et.test(t) then IO.raiseError(t)
        else f(t.asInstanceOf[E]).flatMap(_ => IO.raiseError(t)) // scalafix:ok DisableSyntax.asInstanceOf
      }

    /** Observes typed failures via an effectful action that can also fail.
      *
      * If the side effect fails, that failure propagates and replaces the original error. For
      * infallible side effects, use [[tapError]].
      */
    def flatTapError[E2 <: Throwable](f: E => Eff[E2, Unit])(using et: ErrorTest[E]): Eff[E | E2, A] =
      (self: IO[A]).handleErrorWith { t =>
        if !et.test(t) then IO.raiseError(t)
        else (f(t.asInstanceOf[E]): IO[Unit]).flatMap(_ => IO.raiseError(t)) // scalafix:ok DisableSyntax.asInstanceOf
      }

    /** Observes the reified attempt result without altering the outcome. Defects propagate through
      * without observation.
      */
    def attemptTap[E2 <: Throwable](f: Either[E, A] => Eff[E2, Unit])(using ErrorTest[E]): Eff[E | E2, A] =
      reify[E, A](self).flatMap { ea =>
        (f(ea): IO[Unit]).flatMap { _ =>
          ea match
            case Right(a) => IO.pure(a)
            case Left(e)  => IO.raiseError(e)
        }
      }

    /** Converts to an infallible effect returning `Option[A]`, treating typed errors as `None`. */
    def option(using ErrorTest[E]): UEff[Option[A]] = reify[E, A](self).map(_.toOption)

    /** Extracts an inner `Option[B]` value, failing with `ifNone` when absent. */
    def collectSome[E2 <: Throwable, B](ifNone: => E2)(using ev: A <:< Option[B]): Eff[E | E2, B] =
      (self: IO[A]).flatMap(a =>
        ev(a) match
          case Some(b) => IO.pure(b)
          case None    => IO.raiseError(ifNone)
      )

    /** Extracts an inner `Either[L, B]` value, mapping left to error via `ifLeft`. */
    def collectRight[E2 <: Throwable, L, B](ifLeft: L => E2)(using ev: A <:< Either[L, B]): Eff[E | E2, B] =
      (self: IO[A]).flatMap(a =>
        ev(a) match
          case Right(b) => IO.pure(b)
          case Left(l)  => IO.raiseError(ifLeft(l))
      )

    /** Converts to `EitherT` for ecosystem interop. */
    def eitherT(using ErrorTest[E]): EitherT[IO, E, A] = EitherT(reify[E, A](self))

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    def *>[E2 <: Throwable, B](that: => Eff[E2, B]): Eff[E | E2, B] = (self: IO[A]).flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    def <*[E2 <: Throwable, B](that: => Eff[E2, B]): Eff[E | E2, A] =
      (self: IO[A]).flatMap(a => (that: IO[B]).map(_ => a))

    /** Combines this computation with `that` into a tuple. */
    def product[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, (A, B)] =
      (self: IO[A]).flatMap(a => (that: IO[B]).map(b => (a, b)))

    /** Applies an effectful function to the success value, discarding its result. */
    def flatTap[E2 <: Throwable, B](f: A => Eff[E2, B]): Eff[E | E2, A] =
      (self: IO[A]).flatMap(a => (f(a): IO[B]).map(_ => a))

    /** Discards the success value, returning `Unit`. */
    def void: Eff[E, Unit] = (self: IO[A]).map(_ => ())

    /** Replaces the success value with `b`. */
    def as[B](b: B): Eff[E, B] = (self: IO[A]).map(_ => b)

    /** Acquires a resource, uses it, and ensures release even on failure. */
    def bracket[E2 <: Throwable, B](use: A => Eff[E2, B])(release: A => IO[Unit]): Eff[E | E2, B] =
      (self: IO[A]).bracket(a => use(a))(release)

    // `E` is absent from the representation, so `Of[E]` IS `IO` and each cast below re-labels a
    // cats-effect type with the phantom the caller declared; none of them inspects a value.
    /** Acquires a resource, uses it, and ensures release with outcome information. */
    def bracketCase[E2 <: Throwable, B](use: A => Eff[E2, B])(
      release: (A, Outcome[Of[E | E2], Throwable, B]) => IO[Unit]
    ): Eff[E | E2, B] =
      (self: IO[A]).bracketCase(a => use(a))((a, oc) => release(a, oc.asInstanceOf[Outcome[Of[E | E2], Throwable, B]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Starts this computation as a fibre, returning immediately. A fibre completing with a typed
      * error is an `Outcome.Errored`.
      */
    def start: Eff[E, Fiber[Of[E], Throwable, A]] =
      (self: IO[A]).start.map(_.asInstanceOf[Fiber[Of[E], Throwable, A]]) // scalafix:ok DisableSyntax.asInstanceOf

    /** Runs this computation as a background fibre, cancelling it on scope exit. */
    def background: Resource[IO, IO[Outcome[Of[E], Throwable, A]]] =
      (self: IO[A]).background.asInstanceOf[Resource[IO, IO[Outcome[Of[E], Throwable, A]]]] // scalafix:ok DisableSyntax.asInstanceOf

    /** Ensures `fin` runs with the completion outcome after this computation. */
    def guaranteeCase(fin: Outcome[Of[E], Throwable, A] => IO[Unit]): Eff[E, A] =
      (self: IO[A]).guaranteeCase(oc => fin(oc.asInstanceOf[Outcome[Of[E], Throwable, A]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Races this computation against `that`, returning the winner's result. */
    def race[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, Either[A, B]] = IO.race(self, that)

    /** Runs this computation and `that` concurrently, returning both results. */
    def both[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, (A, B)] = IO.both(self, that)

    /** Runs this computation and `that` in parallel, discarding the result of `this`. */
    @targetName("parProductR")
    def &>[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, B] = IO.both(self, that).map(_._2)

    /** Runs this computation and `that` in parallel, discarding the result of `that`. */
    @targetName("parProductL")
    def <&[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, A] = IO.both(self, that).map(_._1)

    /** Registers a finaliser to run if this computation is cancelled. */
    def onCancel[E2 <: Throwable](fin: Eff[E2, Unit]): Eff[E | E2, A] = (self: IO[A]).onCancel(fin)

    /** Ensures `fin` runs after this computation regardless of outcome. */
    def guarantee[E2 <: Throwable](fin: Eff[E2, Unit]): Eff[E | E2, A] = (self: IO[A]).guarantee(fin)

    /** Delays execution of this computation by `duration`. */
    def delayBy(duration: FiniteDuration): Eff[E, A] = (self: IO[A]).delayBy(duration)

    /** Executes this computation, then waits for `duration` before returning. */
    def andWait(duration: FiniteDuration): Eff[E, A] = (self: IO[A]).andWait(duration)

    /** Returns the result paired with the execution duration. */
    def timed: Eff[E, (FiniteDuration, A)] = (self: IO[A]).timed

    /** Shifts execution of this computation to `ec`, returning to the default pool afterwards.
      * Executor placement is channel-neutral: nothing is observed, so `E` is preserved with no
      * evidence and no `Nothing` twin.
      */
    def evalOn(ec: scala.concurrent.ExecutionContext): Eff[E, A] = (self: IO[A]).evalOn(ec)

    /** Fails with `onTimeout` if the computation does not complete within `duration`. */
    def timeout[E2 <: Throwable](duration: FiniteDuration, onTimeout: => E2): Eff[E | E2, A] =
      (self: IO[A]).timeoutTo(duration, IO.raiseError(onTimeout))

    /** Returns `fallback` if this computation does not complete within `duration`. */
    def timeoutTo[E2 <: Throwable, B >: A](duration: FiniteDuration, fallback: => Eff[E2, B]): Eff[E | E2, B] =
      (self: IO[B]).timeoutTo(duration, fallback)
  end extension

  // Channel-observers on the infallible (`Nothing`) channel. The typed error is uninhabited, but the
  // general observers' `ErrorTest[E]` widens `E` to `Throwable` here - the covariant receiver admits
  // any `E`, and resolving the evidence pins `E := Throwable` - turning the test into the identity
  // and capturing defects. These overloads pin `E = Nothing` via a more specific receiver; each is
  // degenerate and correct by construction - an error handler can never fire, so it is dropped and
  // the effect passes through `self` (defects included), while a success observer maps the value.
  // No evidence, no `reify`.
  extension [A](self: Eff[Nothing, A])
    /** The success reified as `Right`; a defect propagates. */
    def either: UEff[Either[Nothing, A]] = (self: IO[A]).map(Right(_))

    /** Applies `f` to the (always-`Right`) success; a `Left` result fails, a defect propagates. */
    def transform[E2 <: Throwable, B](f: Either[Nothing, A] => Either[E2, B]): Eff[E2, B] =
      (self: IO[A]).flatMap(a =>
        f(Right(a)) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** No typed error to catch; identity. */
    def catchAll[E2 <: Throwable, B >: A](@unused f: Nothing => Eff[E2, B]): Eff[E2, B] = self

    /** No typed error to catch; identity. */
    def catchSome[E2 <: Throwable, B >: A](@unused pf: PartialFunction[Nothing, Eff[E2, B]]): Eff[E2, B] = self

    /** No typed error to catch; identity. */
    def catchOnly[H <: Throwable, R <: Throwable, B >: A](@unused f: H => Eff[R, B]): Eff[R, B] = self

    /** No typed error; `fa` folds the success. */
    def redeemAll[E2 <: Throwable, B](@unused fe: Nothing => Eff[E2, B], fa: A => Eff[E2, B]): Eff[E2, B] =
      (self: IO[A]).flatMap(a => fa(a))

    /** No typed error; `fa` folds the success. */
    def fold[B](@unused fe: Nothing => B, fa: A => B): UEff[B] = (self: IO[A]).map(fa)

    /** No typed error; `fa` folds the success. */
    def foldF[B](@unused fe: Nothing => UEff[B], fa: A => UEff[B]): UEff[B] =
      (self: IO[A]).flatMap(a => fa(a): IO[B])

    /** No typed error to map; identity. */
    def mapError[E2 <: Throwable](@unused f: Nothing => E2): Eff[E2, A] = self

    /** No typed error to map; identity. */
    def mapErrorPartial[E2 <: Throwable](@unused pf: PartialFunction[Nothing, E2]): Eff[E2, A] = self

    /** Never fails typed; identity. */
    def alt[E2 <: Throwable, B >: A](@unused that: => Eff[E2, B]): Eff[E2, B] = self

    /** Never fails typed; identity. */
    def orElseSucceed[B >: A](@unused value: => B): UEff[B] = self

    /** Never fails typed; identity. */
    def orElseFail[E2 <: Throwable](@unused error: => E2): Eff[E2, A] = self

    /** Never fails typed; identity. */
    def valueOr(@unused f: Nothing => A): UEff[A] = self

    /** No typed error to observe; identity. */
    def tapError(@unused f: Nothing => IO[Unit]): Eff[Nothing, A] = self

    /** No typed error to observe; identity. */
    def flatTapError[E2 <: Throwable](@unused f: Nothing => Eff[E2, Unit]): Eff[E2, A] = self

    /** The attempt is always `Right`; `f` observes it, then the value passes through. */
    def attemptTap[E2 <: Throwable](f: Either[Nothing, A] => Eff[E2, Unit]): Eff[E2, A] =
      (self: IO[A]).flatMap(a => (f(Right(a)): IO[Unit]).flatMap(_ => IO.pure(a)))

    /** The success wrapped as `Some`; a defect propagates. */
    def option: UEff[Option[A]] = (self: IO[A]).map(Some(_))

    /** The success reified as `Right`; a defect propagates. */
    def eitherT: EitherT[IO, Nothing, A] = EitherT((self: IO[A]).map(Right(_)))
  end extension

  // scalafix:off DisableSyntax.asInstanceOf
  /** Plain `Monad` (hence `Functor`/`Invariant`), sourced from `IO` without evidence, so that
    * `Functor`/`Monad`/`Invariant[Eff.Of[E]]` resolve even for an abstract `E`, where the typed
    * `MonadError` below cannot derive its `ErrorTest`. For a concrete `E` the more specific
    * `MonadError` still wins.
    */
  given effMonad: [E <: Throwable] => Monad[Of[E]] =
    IO.asyncForIO.asInstanceOf[Monad[Of[E]]]

  /** Canonical `MonadError` for the typed error channel `E`. */
  given effMonadError: [E <: Throwable] => (et: ErrorTest[E]) => MonadError[Of[E], E]:
    def pure[A](a: A): Eff[E, A] = IO.pure(a)
    def flatMap[A, B](fa: Eff[E, A])(f: A => Eff[E, B]): Eff[E, B] = (fa: IO[A]).flatMap(a => f(a))
    // Reference `IO`'s instance by name, not `summon[Monad[IO]]`: inside this object `Eff.Of[E]` is
    // structurally `IO`, so a summon would resolve back to this very given and loop.
    def tailRecM[A, B](a: A)(f: A => Eff[E, Either[A, B]]): Eff[E, B] =
      IO.asyncForIO.tailRecM(a)(x => f(x): IO[Either[A, B]])
    def raiseError[A](e: E): Eff[E, A] = IO.raiseError(e)
    def handleErrorWith[A](fa: Eff[E, A])(f: E => Eff[E, A]): Eff[E, A] =
      (fa: IO[A]).handleErrorWith(t => if et.test(t) then f(t.asInstanceOf[E]) else IO.raiseError(t))

  /** `Parallel` enabling `parMapN`, `parTraverse`, and related parallel composition. */
  given effParallel: [E <: Throwable] => Parallel[Of[E]] =
    IO.parallelForIO.asInstanceOf[Parallel[Of[E]]]

  /** Choice semantics: `combineK` falls back to the second computation on typed error. */
  given effSemigroupK: [E <: Throwable] => (et: ErrorTest[E]) => SemigroupK[Of[E]]:
    def combineK[A](x: Eff[E, A], y: Eff[E, A]): Eff[E, A] =
      (x: IO[A]).handleErrorWith(t => if et.test(t) then y else IO.raiseError(t))

  /** Combines two successful computations using `Semigroup` on their values. */
  given effSemigroup: [E <: Throwable, A] => (S: Semigroup[A]) => Semigroup[Eff[E, A]]:
    def combine(x: Eff[E, A], y: Eff[E, A]): Eff[E, A] =
      (x: IO[A]).flatMap(a => (y: IO[A]).map(b => S.combine(a, b)))

  /** Combines `Eff` computations with an identity element from `Monoid`. */
  given effMonoid: [E <: Throwable, A] => (M: Monoid[A]) => Monoid[Eff[E, A]]:
    def empty: Eff[E, A] = IO.pure(M.empty)
    def combine(x: Eff[E, A], y: Eff[E, A]): Eff[E, A] =
      (x: IO[A]).flatMap(a => (y: IO[A]).map(b => M.combine(a, b)))
  // scalafix:on
end Eff

// The general `Async` sits below the companion's own givens so that resolution reaches the typed
// `MonadError` first: the two are incomparable and both extend `Monad`, so a summon at `Monad` or
// `Functor` would otherwise be free to pick `Async` widened.
private[effect] trait EffInstances:
  /** `Async` for `Eff`, and by subtyping every effect type class it extends. Reference `IO`'s
    * instance by name: inside `Eff`'s scope `Eff.Of[E]` is `IO`, so a summon could loop.
    */
  given effAsync: [E <: Throwable] => Async[Eff.Of[E]] =
    IO.asyncForIO.asInstanceOf[Async[Eff.Of[E]]] // scalafix:ok DisableSyntax.asInstanceOf
