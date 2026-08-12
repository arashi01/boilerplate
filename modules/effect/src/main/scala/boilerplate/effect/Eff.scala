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
import scala.reflect.TypeTest
import scala.util.Try

import cats.Eq
import cats.Monad
import cats.MonadError
import cats.Monoid
import cats.Parallel
import cats.Semigroup
import cats.SemigroupK
import cats.Show
import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Fiber
import cats.effect.kernel.Outcome
import cats.effect.kernel.Resource
import cats.kernel.PartialOrder

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
  * `Eff` is covariant in both parameters. A `flatMap`/for-comprehension over steps with distinct
  * error types therefore widens the channel to their join - the root itself for arms of one sealed
  * root, a structural type wider than their union for unrelated arms; either the union or the root
  * is reachable by ascribing the result type. That widening is silent - the channel can grow wider
  * than intended with no compile error, so ascribe, or `mapError`/`catchOnly`, to contain it.
  *
  * Observing the typed channel (`either`, `catchAll`, `mapError`, `fold`, ...) filters the caught
  * `Throwable` by `TypeTest[Throwable, E]`, re-raising any non-`E` defect unchanged. For a concrete
  * `E` (a sealed `Throwable` root or a union of them) the `TypeTest` is synthesised by the
  * compiler, so no `using` clause is written at the call site. On the infallible channel ([[UEff]],
  * `E = Nothing`) the typed error is uninhabited, so these observers are degenerate - a defect
  * always propagates and any handler is dead code.
  *
  * An API of your own that is GENERIC in `E` and threads `using TypeTest[Throwable, E]` must pin
  * `E` from a covariant value parameter (an effect or handler, ordered before the evidence) and
  * ship an `E = Nothing` overload: where `E` is left to inference against the evidence alone, the
  * solver silently widens it to `Throwable`, whose synthesised test captures every defect. The same
  * holds for any channel variable a `using` witness alone constrains - bound it by the channel it
  * refines (as `catchOnly`'s infallible-handler twin bounds its residual by `E`), or the solver
  * discharges the witness by widening the variable to `Throwable`. The combinators here follow
  * exactly that discipline.
  *
  * Refer to [[boilerplate.effect.Eff$ Eff]] for constructors, combinators, and type class
  * instances.
  */
opaque type Eff[+E <: Throwable, +A] >: IO[A] = IO[A]

/** Infallible effect: [[boilerplate.effect.Eff Eff]] with `Nothing` errors. */
type UEff[+A] = Eff[Nothing, A]

/** Throwable-errored effect: [[boilerplate.effect.Eff Eff]] over `Throwable`. */
type TEff[+A] = Eff[Throwable, A]

/** `TypeTest` for the empty typed channel. An infallible effect (`E = Nothing`) admits no typed
  * error, so this test never matches - every `Throwable` on the channel is a defect. Supplying it
  * lets the channel-observing combinators (`either`, `catchAll`, `fold`, ...) be summoned uniformly
  * across `E`, including `E = Nothing`, where the compiler cannot otherwise synthesise a `TypeTest`
  * for the uninhabited type.
  */
given TypeTest[Throwable, Nothing] with
  def unapply(t: Throwable): Option[t.type & Nothing] = None

/** Provides constructors, combinators, and type class instances for [[boilerplate.effect.Eff Eff]]. */
object Eff extends EffInstances:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.Eff Eff]]. */
  type Of[E <: Throwable] = [A] =>> Eff[E, A]

  // A by-name argument is substituted verbatim when its combinator inlines, so at a call site whose
  // argument is typed as the opaque `Eff` a match branch holding it would lub to `Eff | IO` - not
  // `IO`, and the enclosing `handleErrorWith` would not typecheck. Routing the branch through a
  // non-inline identity pins it to `IO` before the lub is formed.
  private def raw[E <: Throwable, A](eff: Eff[E, A]): IO[A] = eff

  /** Reifies the typed channel into an `Either`; a non-`E` defect propagates on `IO`'s channel. */
  private def reify[E, A](io: IO[A])(using tt: TypeTest[Throwable, E]): IO[Either[E, A]] =
    io.map(a => Right(a): Either[E, A]).handleErrorWith {
      case tt(e) => IO.pure(Left(e))
      case other => IO.raiseError(other)
    }

  /** Creates a successful computation. */
  inline def succeed[A](a: A): UEff[A] = IO.pure(a)

  /** Creates a failed computation. */
  inline def fail[E <: Throwable](e: E): Eff[E, Nothing] = IO.raiseError(e)

  /** Lifts a pure `Either` into the effect. */
  inline def from[E <: Throwable, A](either: Either[E, A]): Eff[E, A] =
    either match
      case Right(a) => IO.pure(a)
      case Left(e)  => IO.raiseError(e)

  /** Converts an `Option`, supplying an error when empty. */
  inline def from[E <: Throwable, A](opt: Option[A], ifNone: => E): Eff[E, A] =
    opt match
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(ifNone)

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  inline def from[E <: Throwable, A](result: Try[A], ifFailure: Throwable => E): Eff[E, A] =
    result.fold(th => fail(ifFailure(th)), succeed(_))

  /** Extracts the underlying computation from `EitherT`. */
  inline def from[E <: Throwable, A](et: EitherT[IO, E, A]): Eff[E, A] = lift(et.value)

  /** Canonical successful unit value, interned and shared across call sites. */
  val unit: UEff[Unit] = IO.unit

  /** Absorbs an existing `IO[Either[E, A]]` into the typed channel; a `Left` fails on `IO`'s
    * channel.
    */
  inline def lift[E <: Throwable, A](io: IO[Either[E, A]]): Eff[E, A] =
    io.flatMap {
      case Right(a) => IO.pure(a)
      case Left(e)  => IO.raiseError(e)
    }

  /** Converts an `IO[Option[A]]`, supplying an error when empty. */
  inline def lift[E <: Throwable, A](io: IO[Option[A]], ifNone: => E): Eff[E, A] =
    io.flatMap {
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(ifNone)
    }

  /** Captures throwables raised in `IO`, translating them via `ifFailure`. */
  inline def attempt[E <: Throwable, A](io: IO[A], ifFailure: Throwable => E): Eff[E, A] =
    io.handleErrorWith(t => IO.raiseError(ifFailure(t)))

  /** Captures matching throwables as typed errors; unmatched throwables propagate as defects in
    * `IO`'s error channel.
    */
  inline def attempt[E <: Throwable, A](io: IO[A])(pf: PartialFunction[Throwable, E]): Eff[E, A] =
    io.handleErrorWith(t => if pf.isDefinedAt(t) then IO.raiseError(pf(t)) else IO.raiseError(t))

  /** Suspends evaluation until demanded. */
  inline def defer[E <: Throwable, A](thunk: => Eff[E, A]): Eff[E, A] = IO.defer(thunk)

  /** Suspends a side-effecting computation that yields an `Either[E, A]`; for an infallible side
    * effect use [[suspend]].
    */
  inline def delay[E <: Throwable, A](ea: => Either[E, A]): Eff[E, A] = lift(IO.delay(ea))

  /** Suspends a synchronous side effect as a success value; for typed errors use [[delay]]. */
  inline def suspend[A](thunk: => A): UEff[A] = IO.delay(thunk)

  /** As [[delay]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def blocking[E <: Throwable, A](ea: => Either[E, A]): Eff[E, A] = lift(IO.blocking(ea))

  /** As [[suspend]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def suspendBlocking[A](thunk: => A): UEff[A] = IO.blocking(thunk)

  /** Suspends execution for the specified duration. */
  inline def sleep(duration: FiniteDuration): UEff[Unit] = IO.sleep(duration)

  /** Returns the current monotonic time as a `FiniteDuration`. */
  inline def monotonic: UEff[FiniteDuration] = IO.monotonic

  /** Returns the current wall-clock time as a `FiniteDuration` since the epoch. */
  inline def realTime: UEff[FiniteDuration] = IO.realTime

  /** Introduces a self-cancellation point, immediately cancelling the current fibre. */
  val canceled: UEff[Unit] = IO.canceled

  /** Introduces a cooperative yielding point. */
  val cede: UEff[Unit] = IO.cede

  /** A computation that never completes. */
  val never: UEff[Nothing] = IO.never

  /** Converts a `Future` into an `Eff`, translating failures via `ifFailure`. */
  inline def fromFuture[E <: Throwable, A](future: IO[Future[A]], ifFailure: Throwable => E): Eff[E, A] =
    IO.fromFuture(future).handleErrorWith(t => IO.raiseError(ifFailure(t)))

  /** Converts a `Future` into an `Eff`, catching matching throwables as typed errors; unmatched
    * throwables propagate as defects in `IO`'s error channel.
    */
  inline def fromFuture[E <: Throwable, A](future: IO[Future[A]])(pf: PartialFunction[Throwable, E]): Eff[E, A] =
    IO.fromFuture(future).handleErrorWith(t => if pf.isDefinedAt(t) then IO.raiseError(pf(t)) else IO.raiseError(t))

  /** Suspends an asynchronous callback-driven computation completing with a typed `Either[E, A]`.
    *
    * The callback is invoked with `Left(e)` for a typed error or `Right(a)` for success - there is
    * no defect-channel nesting. A raised throwable surfaces as a defect in `IO`'s error channel;
    * use [[asyncAttempt]] to fold it into a typed error instead. The returned
    * `IO[Option[IO[Unit]]]` optionally yields a finaliser run on cancellation.
    */
  inline def async[E <: Throwable, A](k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]): Eff[E, A] =
    IO.async[A](cb => k(ea => cb(ea)))

  /** As [[async]], additionally folding a throwable raised while registering the callback into a
    * typed error via `ifDefect`. A typed error delivered through the callback (`Left(e)`) passes
    * through unchanged, and cancellation is never folded. Needs no `TypeTest`, so it works for an
    * abstract `E` - a registration-time failure is a defect by construction.
    */
  inline def asyncAttempt[E <: Throwable, A](ifDefect: Throwable => E)(
    k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]
  ): Eff[E, A] =
    IO.async[A](cb => k(ea => cb(ea)).handleErrorWith(t => IO.raiseError(ifDefect(t))))

  /** Executes `eff` only when `cond` is true, otherwise succeeds with `Unit`. */
  inline def when[E <: Throwable](cond: Boolean)(eff: => Eff[E, Unit]): Eff[E, Unit] =
    if cond then eff else unit

  /** Executes `eff` only when `cond` is false, otherwise succeeds with `Unit`. */
  inline def unless[E <: Throwable](cond: Boolean)(eff: => Eff[E, Unit]): Eff[E, Unit] =
    if cond then unit else eff

  /** Raises an error when `cond` is true, otherwise succeeds with `Unit`. */
  inline def raiseWhen[E <: Throwable](cond: Boolean)(e: => E): Eff[E, Unit] =
    if cond then fail(e) else unit

  /** Raises an error when `cond` is false, otherwise succeeds with `Unit`. */
  inline def raiseUnless[E <: Throwable](cond: Boolean)(e: => E): Eff[E, Unit] =
    if cond then unit else fail(e)

  /** Lifts a Boolean predicate into a typed-error effect. Both branches are evaluated lazily; the
    * unselected branch is never run.
    */
  inline def cond[E <: Throwable, A](pred: Boolean, ifTrue: => A, ifFalse: => E): Eff[E, A] =
    if pred then succeed(ifTrue) else fail(ifFalse)

  /** Traverses a collection, short-circuiting on first error. */
  inline def traverse[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, List[B]] =
    traverseImpl(as)(a => f(a))

  private def traverseImpl[A, B](as: Iterable[A])(f: A => IO[B]): IO[List[B]] =
    // Prepend then reverse once: `:+` per element would be O(n^2) on `List`.
    as.foldLeft(IO.pure(List.empty[B]))((acc, a) => acc.flatMap(bs => f(a).map(b => b :: bs))).map(_.reverse)

  /** Sequences a collection of effects, short-circuiting on first error. */
  inline def sequence[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, List[A]] =
    traverse(effs)(identity)

  /** Traverses a collection for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("traverseUnit")
  inline def traverse_[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, Unit] =
    traverseUnitImpl(as)(a => f(a))

  private def traverseUnitImpl[A, B](as: Iterable[A])(f: A => IO[B]): IO[Unit] =
    as.foldLeft(IO.unit)((acc, a) => acc.flatMap(_ => f(a).void))

  /** Runs a collection of effects for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("sequenceUnit")
  inline def sequence_[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, Unit] =
    traverse_(effs)(identity)

  /** Traverses a collection in parallel. */
  inline def parTraverse[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, List[B]] =
    parTraverseImpl(as)(a => f(a))

  private def parTraverseImpl[A, B](as: Iterable[A])(f: A => IO[B]): IO[List[B]] =
    val P = IO.parallelForIO
    val parF = P.applicative
    P.sequential(as.toList.foldRight(parF.pure(List.empty[B]))((a, acc) => parF.map2(P.parallel(f(a)), acc)(_ :: _)))

  /** Sequences a collection of effects in parallel. */
  inline def parSequence[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, List[A]] =
    parTraverse(effs)(identity)

  /** Traverses a collection in parallel for effect only, discarding results. */
  @targetName("parTraverseUnit")
  inline def parTraverse_[E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[E, B]): Eff[E, Unit] =
    parTraverseUnitImpl(as)(a => f(a))

  private def parTraverseUnitImpl[A, B](as: Iterable[A])(f: A => IO[B]): IO[Unit] =
    val P = IO.parallelForIO
    val parF = P.applicative
    P.sequential(as.toList.foldRight(parF.pure(()))((a, acc) => parF.map2(P.parallel(f(a)), acc)((_, _) => ())))

  /** Sequences a collection of effects in parallel for effect only, discarding results. */
  @targetName("parSequenceUnit")
  inline def parSequence_[E <: Throwable, A](effs: Iterable[Eff[E, A]]): Eff[E, Unit] =
    parTraverse_(effs)(identity)

  /** Retries the effect up to `maxRetries` times on a typed failure; a defect propagates. For paced
    * retries use the [[boilerplate.effect.RetryPolicy RetryPolicy]] overloads.
    */
  inline def retry[E <: Throwable, A](eff: Eff[E, A], maxRetries: Int)(using TypeTest[Throwable, E]): Eff[E, A] =
    retryImpl(eff, maxRetries)

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries.
    */
  inline def retry[A](eff: Eff[Nothing, A], @unused maxRetries: Int): Eff[Nothing, A] = eff

  private def retryImpl[E <: Throwable, A](eff: Eff[E, A], maxRetries: Int)(using tt: TypeTest[Throwable, E]): Eff[E, A] =
    if maxRetries <= 0 then eff
    else
      // Not `eff.catchAll`: `E` is abstract here, so the general and `Nothing` observer overloads
      // are ambiguous - inline the typed-vs-defect split directly on `IO`.
      (eff: IO[A]).handleErrorWith {
        case tt(_) => retryImpl(eff, maxRetries - 1)
        case other => IO.raiseError(other)
      }

  /** Retries the effect with exponential backoff, capping each delay at `maxDelay`. */
  inline def retryWithBackoff[E <: Throwable, A](
    eff: Eff[E, A],
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Option[FiniteDuration]
  )(using TypeTest[Throwable, E]): Eff[E, A] =
    retryWithBackoffImpl(eff, maxRetries, initialDelay, maxDelay)

  /** Retries an infallible effect with backoff: a defect propagates on the first execution - zero
    * retries, no delay.
    */
  inline def retryWithBackoff[A](
    eff: Eff[Nothing, A],
    @unused maxRetries: Int,
    @unused initialDelay: FiniteDuration,
    @unused maxDelay: Option[FiniteDuration]
  ): Eff[Nothing, A] = eff

  private def retryWithBackoffImpl[E <: Throwable, A](
    eff: Eff[E, A],
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Option[FiniteDuration]
  )(using tt: TypeTest[Throwable, E]): Eff[E, A] =
    def loop(remaining: Int, delay: FiniteDuration): IO[A] =
      if remaining <= 0 then eff
      else
        (eff: IO[A]).handleErrorWith {
          case tt(_) =>
            val cappedDelay = maxDelay.fold(delay)(d => delay.min(d))
            // Doubling past FiniteDuration's range throws mid-retry; hold the progression steady
            // once another doubling could overflow (sleeps stay capped by `maxDelay` regardless).
            val next = if delay.toNanos > Long.MaxValue / 4 then delay else delay * 2
            IO.sleep(cappedDelay).flatMap(_ => loop(remaining - 1, next))
          case other => IO.raiseError(other)
        }
    loop(maxRetries, initialDelay)
  end retryWithBackoffImpl

  /** Retries the effect on typed failures, paced and bounded by `policy`; the final typed error
    * propagates once the policy stops. A defect propagates without retrying.
    */
  inline def retry[E <: Throwable, A](eff: Eff[E, A], policy: RetryPolicy)(using TypeTest[Throwable, E]): Eff[E, A] =
    retryPolicyImpl(eff, policy, _ => true, (_, _, _) => IO.unit)

  /** As the policy overload, retrying only failures `retryOn` accepts; a rejected error propagates
    * immediately.
    */
  inline def retry[E <: Throwable, A](eff: Eff[E, A], policy: RetryPolicy, retryOn: E => Boolean)(using
    TypeTest[Throwable, E]
  ): Eff[E, A] =
    retryPolicyImpl(eff, policy, retryOn, (_, _, _) => IO.unit)

  /** As the policy overload, invoking `onRetry` with the 1-based number of the attempt that just
    * failed, its error, and the delay about to be slept - only when a retry will actually happen,
    * before its sleep. The side effect is a raw `IO[Unit]`: anything it raises propagates on `IO`'s
    * channel.
    */
  inline def retry[E <: Throwable, A](
    eff: Eff[E, A],
    policy: RetryPolicy,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using TypeTest[Throwable, E]): Eff[E, A] =
    retryPolicyImpl(eff, policy, _ => true, onRetry)

  /** As the policy overload, with both the `retryOn` filter and the `onRetry` observer. */
  inline def retry[E <: Throwable, A](
    eff: Eff[E, A],
    policy: RetryPolicy,
    retryOn: E => Boolean,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using TypeTest[Throwable, E]): Eff[E, A] =
    retryPolicyImpl(eff, policy, retryOn, onRetry)

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay.
    */
  inline def retry[A](eff: Eff[Nothing, A], @unused policy: RetryPolicy): Eff[Nothing, A] = eff

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay.
    */
  inline def retry[A](
    eff: Eff[Nothing, A],
    @unused policy: RetryPolicy,
    @unused retryOn: Nothing => Boolean
  ): Eff[Nothing, A] = eff

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay, no observation.
    */
  inline def retry[A](
    eff: Eff[Nothing, A],
    @unused policy: RetryPolicy,
    @unused onRetry: (Int, Nothing, FiniteDuration) => IO[Unit]
  ): Eff[Nothing, A] = eff

  /** Retries an infallible effect: a defect is never a typed error, so it propagates on the first
    * execution - zero retries, no delay, no observation.
    */
  inline def retry[A](
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
  )(using tt: TypeTest[Throwable, E]): Eff[E, A] =
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
      (eff: IO[A]).handleErrorWith {
        case tt(e) =>
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
        case other => IO.raiseError(other)
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
    inline def either(using TypeTest[Throwable, E]): UEff[Either[E, A]] = reify[E, A](self)

    /** Absorbs the typed error into `IO`. O(0) identity - the failure is already there. */
    inline def absolve: IO[A] = self

    /** Maps the success channel while preserving the error type. */
    inline def map[B](f: A => B): Eff[E, B] = (self: IO[A]).map(f)

    /** Sequences computations, widening the error channel on demand. */
    inline def flatMap[E2 >: E <: Throwable, B](f: A => Eff[E2, B]): Eff[E2, B] =
      (self: IO[A]).flatMap(a => f(a))

    /** Maps the success value through an effectful function. */
    inline def semiflatMap[B](f: A => IO[B]): Eff[E, B] = (self: IO[A]).flatMap(f)

    /** Flat-maps the success through a pure `Either`-returning function; a `Left` fails. */
    inline def subflatMap[E2 >: E <: Throwable, B](f: A => Either[E2, B]): Eff[E2, B] =
      (self: IO[A]).flatMap(a =>
        f(a) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** Transforms the entire reified `Either` structure. */
    inline def transform[E2 <: Throwable, B](f: Either[E, A] => Either[E2, B])(using TypeTest[Throwable, E]): Eff[E2, B] =
      reify[E, A](self).flatMap(ea =>
        f(ea) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** Handles any failure by switching to an alternative computation. */
    inline def catchAll[E2 <: Throwable, B >: A](f: E => Eff[E2, B])(using tt: TypeTest[Throwable, E]): Eff[E2, B] =
      (self: IO[B]).handleErrorWith {
        case tt(e) => f(e)
        case other => IO.raiseError(other)
      }

    /** Recovers the errors `pf` handles with an effect; unmatched errors pass through, widening to
      * `E2`. The effectful sibling of [[mapErrorPartial]], pairing with [[catchAll]].
      */
    inline def catchSome[E2 >: E <: Throwable, B >: A](pf: PartialFunction[E, Eff[E2, B]])(using tt: TypeTest[Throwable, E]): Eff[E2, B] =
      (self: IO[B]).handleErrorWith {
        case tt(e) if pf.isDefinedAt(e) => pf(e)
        case other                      => IO.raiseError(other)
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
      *
      * `H` must be runtime-testable; an erasure-ambiguous `H` is rejected at the call site.
      */
    inline def catchOnly[H, R <: Throwable, B >: A](f: H => Eff[R, B])(using
      ev: E <:< (R | H),
      tt: TypeTest[Throwable, H]
    ): Eff[R, B] =
      val _ = ev
      (self: IO[B]).handleErrorWith {
        case tt(h) => f(h)
        case other => IO.raiseError(other)
      }

    /** As [[catchOnly]], for an infallible handler, whose return type pins no residual: bounding
      * `R` by the receiver's channel lets the solver subtract the handled arm, so on a union
      * channel the residual is inferred narrow with no annotation - left unbounded it silently
      * widens to `Throwable`, whose identity `TypeTest` makes every later observer capture defects.
      * A handler whose domain covers the whole channel infers `Nothing`; a root-typed receiver
      * stays bounded by the root, which does not decompose into its arms.
      */
    @targetName("catchOnlyInfallible")
    inline def catchOnly[H, R <: E, B >: A](f: H => Eff[Nothing, B])(using
      ev: E <:< (R | H),
      tt: TypeTest[Throwable, H]
    ): Eff[R, B] =
      val _ = ev
      (self: IO[B]).handleErrorWith {
        case tt(h) => f(h)
        case other => IO.raiseError(other)
      }

    /** Handles both error and success with effectful functions, allowing error type change. */
    inline def redeemAll[E2 <: Throwable, B](fe: E => Eff[E2, B], fa: A => Eff[E2, B])(using TypeTest[Throwable, E]): Eff[E2, B] =
      reify[E, A](self).flatMap {
        case Left(e)  => fe(e)
        case Right(a) => fa(a)
      }

    /** Folds over both channels; both are consumed, so the result is infallible. */
    inline def fold[B](fe: E => B, fa: A => B)(using TypeTest[Throwable, E]): UEff[B] =
      reify[E, A](self).map(_.fold(fe, fa))

    /** Effectfully folds both channels; the continuations are infallible (an `IO` lambda lands by
      * subtyping), so the result is too.
      */
    inline def foldF[B](fe: E => UEff[B], fa: A => UEff[B])(using TypeTest[Throwable, E]): UEff[B] =
      reify[E, A](self).flatMap(ea => ea.fold(fe, fa): IO[B])

    /** Transforms the error channel. */
    inline def mapError[E2 <: Throwable](f: E => E2)(using tt: TypeTest[Throwable, E]): Eff[E2, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => IO.raiseError(f(e))
        case other => IO.raiseError(other)
      }

    /** Transforms the error channel partially; unmatched errors pass through. */
    inline def mapErrorPartial[E2 >: E <: Throwable](pf: PartialFunction[E, E2])(using tt: TypeTest[Throwable, E]): Eff[E2, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => IO.raiseError(pf.applyOrElse(e, (x: E) => x))
        case other => IO.raiseError(other)
      }

    /** Fallback to an alternative computation when this one fails with a typed error. */
    inline def alt[E2 <: Throwable, B >: A](that: => Eff[E2, B])(using tt: TypeTest[Throwable, E]): Eff[E2, B] =
      (self: IO[B]).handleErrorWith {
        case tt(_) => raw(that)
        case other => IO.raiseError(other)
      }

    /** Recovers from any typed failure with a constant success value. */
    inline def orElseSucceed[B >: A](value: => B)(using tt: TypeTest[Throwable, E]): UEff[B] =
      (self: IO[B]).handleErrorWith {
        case tt(_) => IO.pure(value)
        case other => IO.raiseError(other)
      }

    /** Replaces any typed failure with a different error. */
    inline def orElseFail[E2 <: Throwable](error: => E2)(using tt: TypeTest[Throwable, E]): Eff[E2, A] =
      (self: IO[A]).handleErrorWith {
        case tt(_) => IO.raiseError(error)
        case other => IO.raiseError(other)
      }

    /** Recovers from all typed errors by mapping them to a success value. */
    inline def valueOr(f: E => A)(using tt: TypeTest[Throwable, E]): UEff[A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => IO.pure(f(e))
        case other => IO.raiseError(other)
      }

    /** Observes typed failures without altering the result.
      *
      * The side effect is a raw `IO[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    inline def tapError(f: E => IO[Unit])(using tt: TypeTest[Throwable, E]): Eff[E, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => f(e).flatMap(_ => IO.raiseError(e))
        case other => IO.raiseError(other)
      }

    /** Observes typed failures via an effectful action that can also fail.
      *
      * If the side effect fails, that failure propagates and replaces the original error. For
      * infallible side effects, use [[tapError]].
      */
    inline def flatTapError(f: E => Eff[E, Unit])(using tt: TypeTest[Throwable, E]): Eff[E, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => f(e).flatMap(_ => IO.raiseError(e))
        case other => IO.raiseError(other)
      }

    /** Observes success values without altering the result. */
    inline def tap(f: A => IO[Unit]): Eff[E, A] = (self: IO[A]).flatMap(a => f(a).map(_ => a))

    /** Observes the reified attempt result without altering the outcome. Defects propagate through
      * without observation.
      */
    inline def attemptTap(f: Either[E, A] => Eff[E, Unit])(using TypeTest[Throwable, E]): Eff[E, A] =
      reify[E, A](self).flatMap { ea =>
        f(ea).flatMap { _ =>
          ea match
            case Right(a) => IO.pure(a)
            case Left(e)  => IO.raiseError(e)
        }
      }

    /** Converts to an infallible effect returning `Option[A]`, treating typed errors as `None`. */
    inline def option(using TypeTest[Throwable, E]): UEff[Option[A]] =
      reify[E, A](self).map(_.toOption)

    /** Extracts an inner `Option[B]` value, failing with `ifNone` when absent. */
    inline def collectSome[B](ifNone: => E)(using ev: A <:< Option[B]): Eff[E, B] =
      (self: IO[A]).flatMap(a =>
        ev(a) match
          case Some(b) => IO.pure(b)
          case None    => IO.raiseError(ifNone)
      )

    /** Extracts an inner `Either[L, B]` value, mapping left to error via `ifLeft`. */
    inline def collectRight[L, B](ifLeft: L => E)(using ev: A <:< Either[L, B]): Eff[E, B] =
      (self: IO[A]).flatMap(a =>
        ev(a) match
          case Right(b) => IO.pure(b)
          case Left(l)  => IO.raiseError(ifLeft(l))
      )

    /** Converts to `EitherT` for ecosystem interop. */
    inline def eitherT(using TypeTest[Throwable, E]): EitherT[IO, E, A] = EitherT(reify[E, A](self))

    // scalafix:off DisableSyntax.asInstanceOf
    /** Treats the error type as a subtype, for trusted casts. */
    transparent inline def assumeError[E2 <: E]: Eff[E2, A] = (self: IO[A]).asInstanceOf[Eff[E2, A]]

    /** Treats the success channel as a subtype, for trusted casts. */
    transparent inline def assume[B <: A]: Eff[E, B] = (self: IO[A]).asInstanceOf[Eff[E, B]]
    // scalafix:on

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    inline def *>[B](that: => Eff[E, B]): Eff[E, B] = (self: IO[A]).flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    inline def <*[B](that: => Eff[E, B]): Eff[E, A] = (self: IO[A]).flatMap(a => (that: IO[B]).map(_ => a))

    /** Sequences this computation with `that`, discarding the result of `this`. */
    inline def productR[B](that: => Eff[E, B]): Eff[E, B] = (self: IO[A]).flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    inline def productL[B](that: => Eff[E, B]): Eff[E, A] = (self: IO[A]).flatMap(a => (that: IO[B]).map(_ => a))

    /** Combines this computation with `that` into a tuple. */
    inline def product[B](that: Eff[E, B]): Eff[E, (A, B)] =
      (self: IO[A]).flatMap(a => (that: IO[B]).map(b => (a, b)))

    /** Applies an effectful function to the success value, discarding its result. */
    inline def flatTap[B](f: A => Eff[E, B]): Eff[E, A] =
      (self: IO[A]).flatMap(a => (f(a): IO[B]).map(_ => a))

    /** Discards the success value, returning `Unit`. */
    inline def void: Eff[E, Unit] = (self: IO[A]).map(_ => ())

    /** Replaces the success value with `b`. */
    inline def as[B](b: B): Eff[E, B] = (self: IO[A]).map(_ => b)

    /** Acquires a resource, uses it, and ensures release even on failure. */
    inline def bracket[B](use: A => Eff[E, B])(release: A => IO[Unit]): Eff[E, B] =
      (self: IO[A]).bracket(a => use(a))(release)

    /** Acquires a resource, uses it, and ensures release with outcome information. */
    inline def bracketCase[B](use: A => Eff[E, B])(
      release: (A, Outcome[Of[E], Throwable, B]) => IO[Unit]
    ): Eff[E, B] =
      (self: IO[A]).bracketCase(a => use(a))((a, oc) => release(a, oc.asInstanceOf[Outcome[Of[E], Throwable, B]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Starts this computation as a fibre, returning immediately. A fibre completing with a typed
      * error is an `Outcome.Errored`.
      */
    inline def start: Eff[E, Fiber[Of[E], Throwable, A]] =
      (self: IO[A]).start.map(_.asInstanceOf[Fiber[Of[E], Throwable, A]]) // scalafix:ok DisableSyntax.asInstanceOf

    /** Runs this computation as a background fibre, cancelling it on scope exit. */
    inline def background: Resource[IO, IO[Outcome[Of[E], Throwable, A]]] =
      (self: IO[A]).background.asInstanceOf[Resource[IO, IO[Outcome[Of[E], Throwable, A]]]] // scalafix:ok DisableSyntax.asInstanceOf

    /** Ensures `fin` runs with the completion outcome after this computation. */
    inline def guaranteeCase(fin: Outcome[Of[E], Throwable, A] => IO[Unit]): Eff[E, A] =
      (self: IO[A]).guaranteeCase(oc => fin(oc.asInstanceOf[Outcome[Of[E], Throwable, A]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Races this computation against `that`, returning the winner's result. */
    inline def race[B](that: Eff[E, B]): Eff[E, Either[A, B]] = IO.race(self, that)

    /** Runs this computation and `that` concurrently, returning both results. */
    inline def both[B](that: Eff[E, B]): Eff[E, (A, B)] = IO.both(self, that)

    /** Runs this computation and `that` in parallel, discarding the result of `this`. */
    @targetName("parProductR")
    inline def &>[B](that: Eff[E, B]): Eff[E, B] = IO.both(self, that).map(_._2)

    /** Runs this computation and `that` in parallel, discarding the result of `that`. */
    @targetName("parProductL")
    inline def <&[B](that: Eff[E, B]): Eff[E, A] = IO.both(self, that).map(_._1)

    /** Registers a finaliser to run if this computation is cancelled. */
    inline def onCancel(fin: Eff[E, Unit]): Eff[E, A] = (self: IO[A]).onCancel(fin)

    /** Ensures `fin` runs after this computation regardless of outcome. */
    inline def guarantee(fin: Eff[E, Unit]): Eff[E, A] = (self: IO[A]).guarantee(fin)

    /** Delays execution of this computation by `duration`. */
    inline def delayBy(duration: FiniteDuration): Eff[E, A] = (self: IO[A]).delayBy(duration)

    /** Executes this computation, then waits for `duration` before returning. */
    inline def andWait(duration: FiniteDuration): Eff[E, A] = (self: IO[A]).andWait(duration)

    /** Returns the result paired with the execution duration. */
    inline def timed: Eff[E, (FiniteDuration, A)] = (self: IO[A]).timed

    /** Shifts execution of this computation to `ec`, returning to the default pool afterwards.
      * Executor placement is channel-neutral: nothing is observed, so `E` is preserved with no
      * evidence and no `Nothing` twin.
      */
    inline def evalOn(ec: scala.concurrent.ExecutionContext): Eff[E, A] = (self: IO[A]).evalOn(ec)

    /** Fails with `onTimeout` if the computation does not complete within `duration`. */
    inline def timeout(duration: FiniteDuration, onTimeout: => E): Eff[E, A] =
      (self: IO[A]).timeoutTo(duration, IO.raiseError(onTimeout))

    /** Returns `fallback` if this computation does not complete within `duration`. */
    inline def timeoutTo[B >: A](duration: FiniteDuration, fallback: => Eff[E, B]): Eff[E, B] =
      (self: IO[B]).timeoutTo(duration, fallback)
  end extension

  // Channel-observers on the infallible (`Nothing`) channel. The typed error is uninhabited, but the
  // general observers' `TypeTest[Throwable, E]` widens `E` to `Throwable` here - the covariant
  // receiver admits any `E`, and resolving the test pins `E := Throwable` - turning the test into the
  // identity and capturing defects. These overloads pin `E = Nothing` via a more specific receiver;
  // each is degenerate and correct by construction - an error handler can never fire, so it is
  // dropped and the effect passes through `self` (defects included), while a success observer maps
  // the value. No `TypeTest`, no `reify`.
  extension [A](self: Eff[Nothing, A])
    /** The success reified as `Right`; a defect propagates. */
    inline def either: UEff[Either[Nothing, A]] = (self: IO[A]).map(Right(_))

    /** Applies `f` to the (always-`Right`) success; a `Left` result fails, a defect propagates. */
    inline def transform[E2 <: Throwable, B](f: Either[Nothing, A] => Either[E2, B]): Eff[E2, B] =
      (self: IO[A]).flatMap(a =>
        f(Right(a)) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** No typed error to catch; identity. */
    inline def catchAll[E2 <: Throwable, B >: A](@unused f: Nothing => Eff[E2, B]): Eff[E2, B] = self

    /** No typed error to catch; identity. */
    inline def catchSome[E2 <: Throwable, B >: A](@unused pf: PartialFunction[Nothing, Eff[E2, B]]): Eff[E2, B] = self

    /** No typed error to catch; identity. */
    inline def catchOnly[H, R <: Throwable, B >: A](@unused f: H => Eff[R, B]): Eff[R, B] = self

    /** No typed error; `fa` folds the success. */
    inline def redeemAll[E2 <: Throwable, B](@unused fe: Nothing => Eff[E2, B], fa: A => Eff[E2, B]): Eff[E2, B] =
      (self: IO[A]).flatMap(a => fa(a))

    /** No typed error; `fa` folds the success. */
    inline def fold[B](@unused fe: Nothing => B, fa: A => B): UEff[B] = (self: IO[A]).map(fa)

    /** No typed error; `fa` folds the success. */
    inline def foldF[B](@unused fe: Nothing => UEff[B], fa: A => UEff[B]): UEff[B] =
      (self: IO[A]).flatMap(a => fa(a): IO[B])

    /** No typed error to map; identity. */
    inline def mapError[E2 <: Throwable](@unused f: Nothing => E2): Eff[E2, A] = self

    /** No typed error to map; identity. */
    inline def mapErrorPartial[E2 <: Throwable](@unused pf: PartialFunction[Nothing, E2]): Eff[E2, A] = self

    /** Never fails typed; identity. */
    inline def alt[E2 <: Throwable, B >: A](@unused that: => Eff[E2, B]): Eff[E2, B] = self

    /** Never fails typed; identity. */
    inline def orElseSucceed[B >: A](@unused value: => B): UEff[B] = self

    /** Never fails typed; identity. */
    inline def orElseFail[E2 <: Throwable](@unused error: => E2): Eff[E2, A] = self

    /** Never fails typed; identity. */
    inline def valueOr(@unused f: Nothing => A): UEff[A] = self

    /** No typed error to observe; identity. */
    inline def tapError(@unused f: Nothing => IO[Unit]): Eff[Nothing, A] = self

    /** No typed error to observe; identity. */
    inline def flatTapError(@unused f: Nothing => Eff[Nothing, Unit]): Eff[Nothing, A] = self

    /** The attempt is always `Right`; `f` observes it, then the value passes through. */
    inline def attemptTap(f: Either[Nothing, A] => Eff[Nothing, Unit]): Eff[Nothing, A] =
      (self: IO[A]).flatMap(a => (f(Right(a)): IO[Unit]).flatMap(_ => IO.pure(a)))

    /** The success wrapped as `Some`; a defect propagates. */
    inline def option: UEff[Option[A]] = (self: IO[A]).map(Some(_))

    /** The success reified as `Right`; a defect propagates. */
    inline def eitherT: EitherT[IO, Nothing, A] = EitherT((self: IO[A]).map(Right(_)))
  end extension

  // scalafix:off DisableSyntax.asInstanceOf
  /** Plain `Monad` (hence `Functor`/`Invariant`), sourced from `IO` without a `TypeTest`, so that
    * `Functor`/`Monad`/`Invariant[Eff.Of[E]]` resolve even for an abstract `E`, where the typed
    * `MonadError` below cannot synthesise its `TypeTest`. For a concrete `E` the more specific
    * `MonadError` still wins.
    */
  given [E <: Throwable] => Monad[Of[E]] =
    IO.asyncForIO.asInstanceOf[Monad[Of[E]]]

  /** Canonical `MonadError` for the typed error channel `E`. */
  given [E <: Throwable] => (tt: TypeTest[Throwable, E]) => MonadError[Of[E], E]:
    def pure[A](a: A): Eff[E, A] = IO.pure(a)
    def flatMap[A, B](fa: Eff[E, A])(f: A => Eff[E, B]): Eff[E, B] = (fa: IO[A]).flatMap(a => f(a))
    // Reference `IO`'s instance by name, not `summon[Monad[IO]]`: inside this object `Eff.Of[E]` is
    // structurally `IO`, so a summon would resolve back to this very given and loop.
    def tailRecM[A, B](a: A)(f: A => Eff[E, Either[A, B]]): Eff[E, B] =
      IO.asyncForIO.tailRecM(a)(x => f(x): IO[Either[A, B]])
    def raiseError[A](e: E): Eff[E, A] = IO.raiseError(e)
    def handleErrorWith[A](fa: Eff[E, A])(f: E => Eff[E, A]): Eff[E, A] =
      (fa: IO[A]).handleErrorWith {
        case tt(e) => f(e)
        case other => IO.raiseError(other)
      }
  end given

  /** `Parallel` enabling `parMapN`, `parTraverse`, and related parallel composition. */
  given [E <: Throwable] => Parallel[Of[E]] =
    IO.parallelForIO.asInstanceOf[Parallel[Of[E]]]

  /** Choice semantics: `combineK` falls back to the second computation on typed error. */
  given [E <: Throwable] => (tt: TypeTest[Throwable, E]) => SemigroupK[Of[E]]:
    def combineK[A](x: Eff[E, A], y: Eff[E, A]): Eff[E, A] =
      (x: IO[A]).handleErrorWith {
        case tt(_) => y
        case other => IO.raiseError(other)
      }

  /** Combines two successful computations using `Semigroup` on their values. */
  given [E <: Throwable, A] => (S: Semigroup[A]) => Semigroup[Eff[E, A]]:
    def combine(x: Eff[E, A], y: Eff[E, A]): Eff[E, A] =
      (x: IO[A]).flatMap(a => (y: IO[A]).map(b => S.combine(a, b)))

  /** Combines `Eff` computations with an identity element from `Monoid`. */
  given [E <: Throwable, A] => (M: Monoid[A]) => Monoid[Eff[E, A]]:
    def empty: Eff[E, A] = IO.pure(M.empty)
    def combine(x: Eff[E, A], y: Eff[E, A]): Eff[E, A] =
      (x: IO[A]).flatMap(a => (y: IO[A]).map(b => M.combine(a, b)))

  /** `Show` delegating to the underlying `IO[A]`. */
  given [E <: Throwable, A] => (S: Show[IO[A]]) => Show[Eff[E, A]] = S.asInstanceOf[Show[Eff[E, A]]]

  /** `Eq` delegating to the underlying `IO[A]`. */
  given [E <: Throwable, A] => (E0: Eq[IO[A]]) => Eq[Eff[E, A]] = E0.asInstanceOf[Eq[Eff[E, A]]]

  /** `PartialOrder` delegating to the underlying `IO[A]`. */
  given [E <: Throwable, A] => (P: PartialOrder[IO[A]]) => PartialOrder[Eff[E, A]] = P.asInstanceOf[PartialOrder[Eff[E, A]]]
  // scalafix:on
end Eff

/** Lower-priority instance scope for [[boilerplate.effect.Eff Eff]]. */
private[effect] trait EffInstances:
  /** `Async` for `Eff`, and by subtyping every effect type class it extends. Reference `IO`'s
    * instance by name: inside `Eff`'s scope `Eff.Of[E]` is `IO`, so a summon could loop.
    */
  given [E <: Throwable] => Async[Eff.Of[E]] =
    IO.asyncForIO.asInstanceOf[Async[Eff.Of[E]]] // scalafix:ok DisableSyntax.asInstanceOf
