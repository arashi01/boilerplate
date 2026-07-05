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

import scala.annotation.publicInBinary
import scala.annotation.targetName
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.reflect.TypeTest
import scala.util.Try

import cats.Bifunctor
import cats.Eq
import cats.MonadError
import cats.Monoid
import cats.Parallel
import cats.Semigroup
import cats.SemigroupK
import cats.Show
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
import cats.effect.kernel.GenSpawn
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.AtomicCell
import cats.effect.std.CountDownLatch
import cats.effect.std.CyclicBarrier
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.effect.std.Supervisor
import cats.kernel.PartialOrder
import cats.~>

/** Covariant, `cats.effect.IO`-specialised typed-error effect, represented as `IO[Either[E, A]]`.
  *
  * `EffIO` is covariant in both `E` and `A`: `IO` and `Either` are each covariant, so a value of
  * `EffIO[Narrow, A]` is usable wherever `EffIO[Wide, A]` is expected when `Narrow <: Wide`, with
  * no call-site method. It coexists with [[boilerplate.effect.Eff Eff]], shares its runtime
  * representation with `Eff[IO, E, A]`, and converts to and from it at zero cost.
  *
  * A consequence of the covariant `E`: a `flatMap`/for-comprehension over steps with distinct error
  * types infers their union (`E1 | E2 | ...`), and that widening is silent - the channel can grow
  * wider than intended with no compile error. Ascribe the result type, or `mapError`/`catchOnly`,
  * to contain it.
  *
  * Refer to [[boilerplate.effect.EffIO$ EffIO]] for constructors, combinators, and type class
  * instances.
  */
opaque type EffIO[+E, +A] = IO[Either[E, A]]

/** Infallible `IO`-specialised effect: [[boilerplate.effect.EffIO EffIO]] with `Nothing` errors. */
type UEffIO[+A] = EffIO[Nothing, A]

/** Throwable-errored `IO`-specialised effect: [[boilerplate.effect.EffIO EffIO]] over `Throwable`. */
type TEffIO[+A] = EffIO[Throwable, A]

/** Provides constructors, combinators, and type class instances for
  * [[boilerplate.effect.EffIO EffIO]].
  */
object EffIO extends EffIOInstances:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.EffIO EffIO]]. */
  type Of[E] = [A] =>> EffIO[E, A]

  /** Views an `Eff[IO, E, A]` as an `EffIO[E, A]`. Identity at runtime; O(0). */
  inline def fromEff[E, A](eff: Eff[IO, E, A]): EffIO[E, A] = Eff.unwrapUnsafe(eff)

  /** Creates a successful computation. */
  inline def succeed[A](a: A): UEffIO[A] = IO.pure(Right(a))

  /** Creates a failed computation. */
  inline def fail[E](e: E): EffIO[E, Nothing] = IO.pure(Left(e))

  /** Lifts a pure `Either` into the effect. */
  inline def from[E, A](either: Either[E, A]): EffIO[E, A] = IO.pure(either)

  /** Converts an `Option`, supplying an error when empty. */
  inline def from[E, A](opt: Option[A], ifNone: => E): EffIO[E, A] = IO.pure(opt.toRight(ifNone))

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  inline def from[E, A](result: Try[A], ifFailure: Throwable => E): EffIO[E, A] =
    result.fold(th => fail(ifFailure(th)), succeed(_))

  /** Extracts the underlying computation from `EitherT`. */
  inline def from[E, A](et: EitherT[IO, E, A]): EffIO[E, A] = et.value

  /** Canonical successful unit value, interned and shared across call sites. */
  val unit: UEffIO[Unit] = IO.pure(Right(()))

  /** Wraps an existing `IO[Either[E, A]]` without recomputation. */
  inline def lift[E, A](io: IO[Either[E, A]]): EffIO[E, A] = io

  /** Converts an `IO[Option[A]]`, supplying an error when empty. */
  inline def lift[E, A](io: IO[Option[A]], ifNone: => E): EffIO[E, A] =
    io.map(_.toRight(ifNone))

  /** Embeds any `IO[A]`, treating values as successes. */
  inline def liftF[A](io: IO[A]): UEffIO[A] = io.map(Right(_))

  /** Captures throwables raised in `IO`, translating them via `ifFailure`. */
  inline def attempt[E, A](io: IO[A], ifFailure: Throwable => E): EffIO[E, A] =
    io.attempt.map(_.fold(th => Left(ifFailure(th)), Right(_)))

  /** Captures matching throwables as typed errors; unmatched throwables propagate as defects in
    * `IO`'s error channel.
    */
  inline def attempt[E, A](io: IO[A])(pf: PartialFunction[Throwable, E]): EffIO[E, A] =
    io.redeemWith(
      t => if pf.isDefinedAt(t) then IO.pure(Left(pf(t))) else IO.raiseError(t),
      a => IO.pure(Right(a))
    )

  /** Suspends evaluation until demanded. */
  inline def defer[E, A](thunk: => EffIO[E, A]): EffIO[E, A] = IO.defer(thunk)

  /** Suspends a side-effecting computation that yields an `Either[E, A]`.
    *
    * For an already-evaluated `Either`, use [[from]]. For an infallible side effect, use
    * [[suspend]]. For unconditional success or failure, use [[succeed]] / [[fail]].
    */
  inline def delay[E, A](ea: => Either[E, A]): EffIO[E, A] = IO.delay(ea)

  /** Suspends a synchronous side effect as a success value.
    *
    * For side effects that may produce typed errors, use [[delay]].
    */
  inline def suspend[A](thunk: => A): UEffIO[A] = IO.delay(thunk).map(Right(_))

  /** As [[delay]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def blocking[E, A](ea: => Either[E, A]): EffIO[E, A] = IO.blocking(ea)

  /** As [[suspend]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def suspendBlocking[A](thunk: => A): UEffIO[A] = IO.blocking(thunk).map(Right(_))

  /** Suspends execution for the specified duration. */
  inline def sleep(duration: FiniteDuration): UEffIO[Unit] = liftF(IO.sleep(duration))

  /** Returns the current monotonic time as a `FiniteDuration`. */
  inline def monotonic: UEffIO[FiniteDuration] = liftF(IO.monotonic)

  /** Returns the current wall-clock time as a `FiniteDuration` since the epoch. */
  inline def realTime: UEffIO[FiniteDuration] = liftF(IO.realTime)

  /** Creates a new `Ref` initialised with `a`, operating in the `EffIO` context. */
  inline def ref[E, A](a: A): EffIO[E, Ref[Of[E], A]] =
    liftF(IO.ref(a).map(_.mapK(new LiftK[E])))

  /** Creates an empty `Deferred` operating in the `EffIO` context. */
  inline def deferred[E, A]: EffIO[E, Deferred[Of[E], A]] =
    liftF(IO.deferred[A].map(_.mapK(new LiftK[E])))

  /** Introduces a self-cancellation point, immediately cancelling the current fibre. */
  val canceled: UEffIO[Unit] = liftF(IO.canceled)

  /** Introduces a cooperative yielding point. */
  val cede: UEffIO[Unit] = liftF(IO.cede)

  /** A computation that never completes. Useful for representing timeouts or blocking operations
    * that should never produce a value on their own.
    */
  val never: UEffIO[Nothing] = IO.never

  /** Converts a `Future` into an `EffIO`, translating failures via `ifFailure`.
    *
    * The `Future` is evaluated lazily when the effect is run.
    */
  inline def fromFuture[E, A](future: IO[Future[A]], ifFailure: Throwable => E): EffIO[E, A] =
    fromEff(Eff.fromFuture[IO, E, A](future, ifFailure))

  /** Converts a `Future` into an `EffIO`, catching matching throwables as typed errors; unmatched
    * throwables propagate as defects in `IO`'s error channel.
    */
  inline def fromFuture[E, A](future: IO[Future[A]])(pf: PartialFunction[Throwable, E]): EffIO[E, A] =
    fromEff(Eff.fromFuture[IO, E, A](future)(pf))

  /** Suspends an asynchronous callback-driven computation completing with a typed `Either[E, A]`.
    *
    * The callback is invoked with `Left(e)` for a typed error or `Right(a)` for success - there is
    * no defect-channel nesting. A raised throwable surfaces as a defect in `IO`'s error channel;
    * use [[asyncAttempt]] to fold it into a typed error instead. The returned
    * `IO[Option[IO[Unit]]]` optionally yields a finaliser run on cancellation.
    */
  inline def async[E, A](k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]): EffIO[E, A] =
    fromEff(Eff.async[IO, E, A](k))

  /** As [[async]], additionally folding a raised throwable into a typed error via `ifDefect`.
    * Cancellation is never folded.
    */
  inline def asyncAttempt[E, A](ifDefect: Throwable => E)(k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]): EffIO[E, A] =
    fromEff(Eff.asyncAttempt[IO, E, A](ifDefect)(k))

  /** Executes `eff` only when `cond` is true, otherwise succeeds with `Unit`. */
  inline def when[E](cond: Boolean)(eff: => EffIO[E, Unit]): EffIO[E, Unit] =
    if cond then eff else unit

  /** Executes `eff` only when `cond` is false, otherwise succeeds with `Unit`. */
  inline def unless[E](cond: Boolean)(eff: => EffIO[E, Unit]): EffIO[E, Unit] =
    if cond then unit else eff

  /** Raises an error when `cond` is true, otherwise succeeds with `Unit`. */
  inline def raiseWhen[E](cond: Boolean)(e: => E): EffIO[E, Unit] =
    if cond then fail(e) else unit

  /** Raises an error when `cond` is false, otherwise succeeds with `Unit`. */
  inline def raiseUnless[E](cond: Boolean)(e: => E): EffIO[E, Unit] =
    if cond then unit else fail(e)

  /** Lifts a Boolean predicate into a typed-error effect. Both branches are evaluated lazily; the
    * unselected branch is never run.
    */
  inline def cond[E, A](pred: Boolean, ifTrue: => A, ifFalse: => E): EffIO[E, A] =
    if pred then succeed(ifTrue) else fail(ifFalse)

  /** Traverses a collection, short-circuiting on first error. */
  inline def traverse[E, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, List[B]] =
    fromEff(Eff.traverse[IO, E, A, B](as)(a => f(a).toEff))

  /** Sequences a collection of effects, short-circuiting on first error. */
  inline def sequence[E, A](effs: Iterable[EffIO[E, A]]): EffIO[E, List[A]] =
    traverse(effs)(identity)

  /** Traverses a collection for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("traverseUnit")
  inline def traverse_[E, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, Unit] =
    fromEff(Eff.traverse_[IO, E, A, B](as)(a => f(a).toEff))

  /** Runs a collection of effects for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("sequenceUnit")
  inline def sequence_[E, A](effs: Iterable[EffIO[E, A]]): EffIO[E, Unit] =
    traverse_(effs)(identity)

  /** Traverses a collection in parallel. */
  inline def parTraverse[E, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, List[B]] =
    fromEff(Eff.parTraverse[IO, E, A, B](as)(a => f(a).toEff))

  /** Sequences a collection of effects in parallel. */
  inline def parSequence[E, A](effs: Iterable[EffIO[E, A]]): EffIO[E, List[A]] =
    parTraverse(effs)(identity)

  /** Traverses a collection in parallel for effect only, discarding results. */
  @targetName("parTraverseUnit")
  inline def parTraverse_[E, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, Unit] =
    fromEff(Eff.parTraverse_[IO, E, A, B](as)(a => f(a).toEff))

  /** Sequences a collection of effects in parallel for effect only, discarding results. */
  @targetName("parSequenceUnit")
  inline def parSequence_[E, A](effs: Iterable[EffIO[E, A]]): EffIO[E, Unit] =
    parTraverse_(effs)(identity)

  /** Retries the effect up to `maxRetries` times on failure. */
  inline def retry[E, A](eff: EffIO[E, A], maxRetries: Int): EffIO[E, A] =
    fromEff(Eff.retry(eff.toEff, maxRetries))

  /** Retries the effect with exponential backoff between attempts.
    *
    * @param eff the effect to retry
    * @param maxRetries maximum number of retry attempts
    * @param initialDelay delay before first retry
    * @param maxDelay optional cap on delay duration
    */
  inline def retryWithBackoff[E, A](
    eff: EffIO[E, A],
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Option[FiniteDuration]
  ): EffIO[E, A] =
    fromEff(Eff.retryWithBackoff(eff.toEff, maxRetries, initialDelay, maxDelay))

  extension [E, A](self: EffIO[E, A])
    /** Views this effect as an invariant `Eff[IO, E, A]`. Identity at runtime; O(0). */
    inline def toEff: Eff[IO, E, A] = Eff.wrapUnsafe(self)

    /** Unwraps to the underlying `IO[Either[E, A]]`. */
    inline def either: IO[Either[E, A]] = self

    /** Maps the success channel while preserving the error type. */
    inline def map[B](f: A => B): EffIO[E, B] = fromEff(self.toEff.map(f))

    /** Sequences computations, widening the error channel on demand. */
    inline def flatMap[E2 >: E, B](f: A => EffIO[E2, B]): EffIO[E2, B] =
      fromEff(self.toEff.flatMap(a => f(a).toEff))

    /** Handles any failure by switching to an alternative computation. */
    inline def catchAll[E2, B >: A](f: E => EffIO[E2, B]): EffIO[E2, B] =
      fromEff(self.toEff.catchAll(e => f(e).toEff))

    /** Recovers the errors `pf` handles with an effect; unmatched errors pass through, widening to
      * `E2`. The effectful sibling of [[mapErrorPartial]], pairing with [[catchAll]].
      */
    inline def catchSome[E2 >: E, B >: A](pf: PartialFunction[E, EffIO[E2, B]]): EffIO[E2, B] =
      fromEff(self.toEff.catchSome(pf.andThen(_.toEff)))

    /** Recovers the `H` arm of a union error with an effect, narrowing the channel to the residual
      * `R` (where `E <: R | H`); unmatched errors stay typed as `R`, and `f` may itself fail into
      * `R`. The residual is inferred from the `E <:< (R | H)` witness - no annotation is needed:
      *
      * {{{
      * val consumed: EffIO[IoError | AppError, Unit] = ...
      * consumed.catchOnly((app: AppError) => log(app)) // : EffIO[IoError, Unit]
      * }}}
      *
      * `H` must be runtime-testable; an erasure-ambiguous `H` is rejected at the call site.
      */
    inline def catchOnly[H, R, B >: A](f: H => EffIO[R, B])(using
      ev: E <:< (R | H),
      tt: TypeTest[E, H]
    ): EffIO[R, B] =
      self.either.flatMap {
        case Right(a) => IO.pure(Right(a))
        case Left(e)  =>
          e match
            case tt(h) => f(h).either
            case _     => IO.pure(Left(ev(e).asInstanceOf[R])) // scalafix:ok DisableSyntax.asInstanceOf
      }

    /** Handles both error and success with effectful functions, allowing error type change. */
    inline def redeemAll[E2, B](fe: E => EffIO[E2, B], fa: A => EffIO[E2, B]): EffIO[E2, B] =
      fromEff(self.toEff.redeemAll(e => fe(e).toEff, a => fa(a).toEff))

    /** Folds over both channels, returning to the base `IO`. */
    inline def fold[B](fe: E => B, fa: A => B): IO[B] = self.toEff.fold(fe, fa)

    /** Effectfully folds both channels, allowing different continuations. */
    inline def foldF[B](fe: E => IO[B], fa: A => IO[B]): IO[B] = self.toEff.foldF(fe, fa)

    /** Observes failures without altering the result.
      *
      * The side effect is a raw `IO[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    inline def tapError(f: E => IO[Unit]): EffIO[E, A] = fromEff(self.toEff.tapError(f))

    /** Observes failures via an effectful action that can also fail.
      *
      * If the side effect fails, that failure propagates and replaces the original error. For
      * infallible side effects, use [[tapError]].
      */
    inline def flatTapError(f: E => EffIO[E, Unit]): EffIO[E, A] =
      fromEff(self.toEff.flatTapError(e => f(e).toEff))

    /** Observes success values without altering the result. */
    inline def tap(f: A => IO[Unit]): EffIO[E, A] = fromEff(self.toEff.tap(f))

    /** Observes the attempt result without altering the outcome.
      *
      * The observation function receives `Right(a)` on success or `Left(e)` on typed error. Defects
      * propagate through without observation.
      */
    inline def attemptTap(f: Either[E, A] => EffIO[E, Unit]): EffIO[E, A] =
      fromEff(self.toEff.attemptTap(ea => f(ea).toEff))

    /** Fallback to an alternative computation when this one fails. */
    inline def alt[E2, B >: A](that: => EffIO[E2, B]): EffIO[E2, B] =
      fromEff(self.toEff.alt(that.toEff))

    /** Recovers from any failure with a constant success value. */
    inline def orElseSucceed[B >: A](value: => B): UEffIO[B] =
      fromEff(self.toEff.orElseSucceed(value))

    /** Replaces any failure with a different error. */
    inline def orElseFail[E2](error: => E2): EffIO[E2, A] =
      fromEff(self.toEff.orElseFail(error))

    /** Recovers from all errors by mapping them to a success value. */
    inline def valueOr(f: E => A): UEffIO[A] = fromEff(self.toEff.valueOr(f))

    /** Transforms the error channel. */
    inline def mapError[E2](f: E => E2): EffIO[E2, A] = fromEff(self.toEff.mapError(f))

    /** Transforms the error channel partially; unmatched errors pass through. */
    inline def mapErrorPartial[E2 >: E](pf: PartialFunction[E, E2]): EffIO[E2, A] =
      fromEff(self.toEff.mapErrorPartial(pf))

    /** Maps the success value through an effectful function. */
    inline def semiflatMap[B](f: A => IO[B]): EffIO[E, B] = fromEff(self.toEff.semiflatMap(f))

    /** Flat-maps the success through a pure `Either`-returning function. */
    inline def subflatMap[E2 >: E, B](f: A => Either[E2, B]): EffIO[E2, B] =
      fromEff(self.toEff.subflatMap(f))

    /** Transforms the entire `Either` structure. */
    inline def transform[E2, B](f: Either[E, A] => Either[E2, B]): EffIO[E2, B] =
      fromEff(self.toEff.transform(f))

    /** Absorbs the typed error into `IO` when `E` is a `Throwable`. */
    inline def absolve(using E <:< Throwable): IO[A] = self.toEff.absolve[Throwable]

    /** Converts to `EitherT` for ecosystem interop. */
    inline def eitherT: EitherT[IO, E, A] = EitherT(self)

    /** Converts to an infallible effect returning `Option[A]`, treating errors as `None`. */
    inline def option: UEffIO[Option[A]] = fromEff(self.toEff.option)

    /** Extracts an inner `Option[B]` value, failing with `ifNone` when absent. */
    inline def collectSome[B](ifNone: => E)(using A <:< Option[B]): EffIO[E, B] =
      fromEff(self.toEff.collectSome(ifNone))

    /** Extracts an inner `Either[L, B]` value, mapping left to error via `ifLeft`. */
    inline def collectRight[L, B](ifLeft: L => E)(using A <:< Either[L, B]): EffIO[E, B] =
      fromEff(self.toEff.collectRight(ifLeft))

    // scalafix:off DisableSyntax.asInstanceOf
    /** Treats the error type as a subtype, for trusted casts. */
    transparent inline def assumeError[E2 <: E]: EffIO[E2, A] =
      self.either.asInstanceOf[IO[Either[E2, A]]]

    /** Treats the success channel as a subtype, for trusted casts. */
    transparent inline def assume[B <: A]: EffIO[E, B] =
      self.either.asInstanceOf[IO[Either[E, B]]]
    // scalafix:on

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    inline def *>[B](that: => EffIO[E, B]): EffIO[E, B] = fromEff(self.toEff.productR(that.toEff))

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    inline def <*[B](that: => EffIO[E, B]): EffIO[E, A] = fromEff(self.toEff.productL(that.toEff))

    /** Sequences this computation with `that`, discarding the result of `this`. */
    inline def productR[B](that: => EffIO[E, B]): EffIO[E, B] =
      fromEff(self.toEff.productR(that.toEff))

    /** Sequences this computation with `that`, discarding the result of `that`. */
    inline def productL[B](that: => EffIO[E, B]): EffIO[E, A] =
      fromEff(self.toEff.productL(that.toEff))

    /** Combines this computation with `that` into a tuple. */
    inline def product[B](that: EffIO[E, B]): EffIO[E, (A, B)] =
      fromEff(self.toEff.product(that.toEff))

    /** Applies an effectful function to the success value, discarding its result. */
    inline def flatTap[B](f: A => EffIO[E, B]): EffIO[E, A] =
      fromEff(self.toEff.flatTap(a => f(a).toEff))

    /** Discards the success value, returning `Unit`. */
    inline def void: EffIO[E, Unit] = fromEff(self.toEff.void)

    /** Replaces the success value with `b`. */
    inline def as[B](b: B): EffIO[E, B] = fromEff(self.toEff.as(b))

    /** Acquires a resource, uses it, and ensures release even on failure. */
    inline def bracket[B](use: A => EffIO[E, B])(release: A => IO[Unit]): EffIO[E, B] =
      fromEff(self.toEff.bracket(a => use(a).toEff)(release))

    /** Acquires a resource, uses it, and ensures release with outcome information. */
    inline def bracketCase[B](use: A => EffIO[E, B])(
      release: (A, Outcome[IO, Throwable, Either[E, B]]) => IO[Unit]
    ): EffIO[E, B] =
      fromEff(self.toEff.bracketCase(a => use(a).toEff)(release))

    /** Starts this computation as a fibre, returning immediately. */
    inline def start: EffIO[E, Fiber[Of[E], Throwable, A]] =
      summon[GenSpawn[Of[E], Throwable]].start(self)

    /** Runs this computation as a background fibre, cancelling it on scope exit.
      *
      * The outcome is reported in the base `IO` context: a typed error surfaces as a `Succeeded`
      * holding `Left(e)`.
      */
    inline def background: Resource[IO, IO[Outcome[IO, Throwable, Either[E, A]]]] =
      summon[GenSpawn[IO, Throwable]].background(self.either)

    /** Ensures `fin` runs with the completion outcome after this computation.
      *
      * The finaliser observes the base `IO` outcome: a `Succeeded` carries `Either[E, A]`, so a
      * typed error appears as `Succeeded(Left(e))` and a defect as `Errored`.
      */
    inline def guaranteeCase(fin: Outcome[IO, Throwable, Either[E, A]] => IO[Unit]): EffIO[E, A] =
      summon[MonadCancel[IO, Throwable]].guaranteeCase(self.either)(fin)

    /** Races this computation against `that`, returning the winner's result. */
    inline def race[B](that: EffIO[E, B]): EffIO[E, Either[A, B]] =
      fromEff(self.toEff.race(that.toEff))

    /** Runs this computation and `that` concurrently, returning both results. */
    inline def both[B](that: EffIO[E, B]): EffIO[E, (A, B)] =
      fromEff(self.toEff.both(that.toEff))

    /** Runs this computation and `that` in parallel, discarding the result of `this`. */
    @targetName("parProductR")
    inline def &>[B](that: EffIO[E, B]): EffIO[E, B] = fromEff(self.toEff &> that.toEff)

    /** Runs this computation and `that` in parallel, discarding the result of `that`. */
    @targetName("parProductL")
    inline def <&[B](that: EffIO[E, B]): EffIO[E, A] = fromEff(self.toEff <& that.toEff)

    /** Registers a finaliser to run if this computation is cancelled. */
    inline def onCancel(fin: EffIO[E, Unit]): EffIO[E, A] =
      fromEff(self.toEff.onCancel(fin.toEff))

    /** Ensures `fin` runs after this computation regardless of outcome. */
    inline def guarantee(fin: EffIO[E, Unit]): EffIO[E, A] =
      fromEff(self.toEff.guarantee(fin.toEff))

    /** Delays execution of this computation by `duration`. */
    inline def delayBy(duration: FiniteDuration): EffIO[E, A] =
      fromEff(self.toEff.delayBy(duration))

    /** Executes this computation, then waits for `duration` before returning. */
    inline def andWait(duration: FiniteDuration): EffIO[E, A] =
      fromEff(self.toEff.andWait(duration))

    /** Returns the result paired with the execution duration. */
    inline def timed: EffIO[E, (FiniteDuration, A)] = fromEff(self.toEff.timed)

    /** Fails with `onTimeout` if the computation does not complete within `duration`. */
    inline def timeout(duration: FiniteDuration, onTimeout: => E): EffIO[E, A] =
      fromEff(self.toEff.timeout(duration, onTimeout))

    /** Returns `fallback` if this computation does not complete within `duration`. */
    inline def timeoutTo[B >: A](duration: FiniteDuration, fallback: => EffIO[E, B]): EffIO[E, B] =
      fromEff(self.toEff.timeoutTo(duration, fallback.toEff))
  end extension

  /** Error-widening natural transformation. Identity at runtime - `EffIO` is covariant in `E`.
    *
    * The `~>` value is still required for invariant positions: `Resource`, `Stream`, and `Pipe`
    * cannot widen their effect parameter by subtyping.
    */
  inline def widenK[E1, E2 >: E1]: Of[E1] ~> Of[E2] = new WidenK

  /** Lifts plain `IO` into the infallible `EffIO` context.
    *
    * Required wherever a value-level `~>` is needed - lifting `IO` into invariant positions such as
    * `Resource`, `Stream`, or `Pipe`.
    */
  val liftK: IO ~> Of[Nothing] = new LiftK[Nothing]

  /** Identity error-widening natural transformation for [[boilerplate.effect.EffIO EffIO]]. */
  private[effect] class WidenK[E1, E2 >: E1] @publicInBinary private[EffIO] () extends FunctionK[Of[E1], Of[E2]]:
    def apply[A](fa: EffIO[E1, A]): EffIO[E2, A] = fa

  /** Lifts `IO` into [[boilerplate.effect.EffIO EffIO]] as an infallible computation. */
  private[effect] class LiftK[E] @publicInBinary private[EffIO] () extends FunctionK[IO, Of[E]]:
    def apply[A](io: IO[A]): EffIO[E, A] = liftF(io)

  /** Transforms a `Resource[IO, A]` to `Resource[EffIO.Of[E], A]`. */
  inline def liftResource[E, A](resource: Resource[IO, A]): Resource[Of[E], A] =
    resource.mapK(new LiftK[E])

  /** Transforms a `Ref[IO, A]` to `Ref[EffIO.Of[E], A]`. */
  inline def liftRef[E, A](ref: Ref[IO, A]): Ref[Of[E], A] =
    ref.mapK(new LiftK[E])

  /** Transforms a `Deferred[IO, A]` to `Deferred[EffIO.Of[E], A]`. */
  inline def liftDeferred[E, A](deferred: Deferred[IO, A]): Deferred[Of[E], A] =
    deferred.mapK(new LiftK[E])

  /** Transforms a `Queue[IO, A]` to `Queue[EffIO.Of[E], A]`. */
  inline def liftQueue[E, A](queue: Queue[IO, A]): Queue[Of[E], A] =
    queue.mapK(new LiftK[E])

  /** Transforms a `Semaphore[IO]` to `Semaphore[EffIO.Of[E]]`. */
  inline def liftSemaphore[E](semaphore: Semaphore[IO]): Semaphore[Of[E]] =
    semaphore.mapK(new LiftK[E])

  /** Transforms a `CountDownLatch[IO]` to `CountDownLatch[EffIO.Of[E]]`. */
  inline def liftLatch[E](latch: CountDownLatch[IO]): CountDownLatch[Of[E]] =
    latch.mapK(new LiftK[E])

  /** Transforms a `CyclicBarrier[IO]` to `CyclicBarrier[EffIO.Of[E]]`. */
  inline def liftBarrier[E](barrier: CyclicBarrier[IO]): CyclicBarrier[Of[E]] =
    barrier.mapK(new LiftK[E])

  /** Transforms an `AtomicCell[IO, A]` to `AtomicCell[EffIO.Of[E], A]`. */
  def liftCell[E, A](cell: AtomicCell[IO, A]): AtomicCell[Of[E], A] =
    new AtomicCellImpl[E, A](cell)

  /** Transforms a `Supervisor[IO]` to `Supervisor[EffIO.Of[E]]`. */
  def liftSupervisor[E](supervisor: Supervisor[IO]): Supervisor[Of[E]] =
    new SupervisorImpl[E](supervisor)

  /** `Fiber` operating in the `EffIO` context, wrapping a base-`IO` fibre. */
  private[effect] class FiberImpl[E, A] @publicInBinary private[EffIO] (
    fiber: Fiber[IO, Throwable, Either[E, A]]
  ) extends Fiber[Of[E], Throwable, A]:
    def cancel: EffIO[E, Unit] = liftF(fiber.cancel)
    def join: EffIO[E, Outcome[Of[E], Throwable, A]] =
      liftF(fiber.join.map {
        case Outcome.Succeeded(fea) => Outcome.succeeded[Of[E], Throwable, A](lift(fea))
        case Outcome.Errored(e)     => Outcome.errored[Of[E], Throwable, A](e)
        case Outcome.Canceled()     => Outcome.canceled[Of[E], Throwable, A]
      })

  /** `Supervisor` operating in the `EffIO` context. */
  private[effect] class SupervisorImpl[E] @publicInBinary private[EffIO] (
    supervisor: Supervisor[IO]
  ) extends Supervisor[Of[E]]:
    def supervise[A](fa: EffIO[E, A]): EffIO[E, Fiber[Of[E], Throwable, A]] =
      liftF(supervisor.supervise(fa.either).map(new FiberImpl[E, A](_)))

  /** `AtomicCell` operating in the `EffIO` context.
    *
    * An `evalModify` whose function yields a typed error leaves the cell state unchanged.
    */
  private[effect] class AtomicCellImpl[E, A] @publicInBinary private[EffIO] (
    cell: AtomicCell[IO, A]
  ) extends AtomicCell[Of[E], A]:
    def get: EffIO[E, A] = liftF(cell.get)
    def set(a: A): EffIO[E, Unit] = liftF(cell.set(a))
    def modify[B](f: A => (A, B)): EffIO[E, B] = liftF(cell.modify(f))
    def evalModify[B](f: A => EffIO[E, (A, B)]): EffIO[E, B] =
      cell.get.flatMap { a =>
        f(a).flatMap {
          case Right((updated, b)) => cell.set(updated).as(Right(b))
          case Left(e)             => IO.pure(Left(e))
        }
      }
    def evalUpdate(f: A => EffIO[E, A]): EffIO[E, Unit] =
      evalModify(a => fromEff(f(a).toEff.map(updated => (updated, ()))))
    def evalGetAndUpdate(f: A => EffIO[E, A]): EffIO[E, A] =
      evalModify(a => fromEff(f(a).toEff.map(updated => (updated, a))))
    def evalUpdateAndGet(f: A => EffIO[E, A]): EffIO[E, A] =
      evalModify(a => fromEff(f(a).toEff.map(updated => (updated, updated))))
  end AtomicCellImpl

  // scalafix:off DisableSyntax.asInstanceOf
  /** Canonical `MonadError` for the typed error channel `E`. */
  given [E] => MonadError[Of[E], E] =
    summon[MonadError[Eff.Of[IO, E], E]].asInstanceOf[MonadError[Of[E], E]]

  /** `Bifunctor` enabling `bimap` and `leftMap` on both channels. */
  given Bifunctor[[E, A] =>> EffIO[E, A]] =
    summon[Bifunctor[[E, A] =>> Eff[IO, E, A]]].asInstanceOf[Bifunctor[[E, A] =>> EffIO[E, A]]]

  /** `Parallel` enabling `parMapN`, `parTraverse`, and related parallel composition. */
  given [E] => Parallel[Of[E]] =
    summon[Parallel[Eff.Of[IO, E]]].asInstanceOf[Parallel[Of[E]]]

  /** Choice semantics: `combineK` falls back to the second computation on typed error. */
  given [E] => SemigroupK[Of[E]] =
    summon[SemigroupK[Eff.Of[IO, E]]].asInstanceOf[SemigroupK[Of[E]]]

  /** Combines two successful computations using `Semigroup` on their values. */
  given [E, A] => Semigroup[A] => Semigroup[EffIO[E, A]] =
    summon[Semigroup[Eff[IO, E, A]]].asInstanceOf[Semigroup[EffIO[E, A]]]

  /** Combines `EffIO` computations with an identity element from `Monoid`. */
  given [E, A] => Monoid[A] => Monoid[EffIO[E, A]] =
    summon[Monoid[Eff[IO, E, A]]].asInstanceOf[Monoid[EffIO[E, A]]]
  // scalafix:on

  /** `Show` delegating to the underlying `IO[Either[E, A]]`. */
  given [E, A] => (S: Show[IO[Either[E, A]]]) => Show[EffIO[E, A]] = S

  /** `Eq` delegating to the underlying `IO[Either[E, A]]`. */
  given [E, A] => (E0: Eq[IO[Either[E, A]]]) => Eq[EffIO[E, A]] = E0

  /** `PartialOrder` delegating to the underlying `IO[Either[E, A]]`. */
  given [E, A] => (P: PartialOrder[IO[Either[E, A]]]) => PartialOrder[EffIO[E, A]] = P
end EffIO

/** Lower-priority instance scope for [[boilerplate.effect.EffIO EffIO]]. */
private[effect] trait EffIOInstances:
  /** `Async` for `EffIO`, and by subtyping every effect type class it extends. */
  given [E] => Async[EffIO.Of[E]] =
    summon[Async[Eff.Of[IO, E]]]
      .asInstanceOf[Async[EffIO.Of[E]]] // scalafix:ok DisableSyntax.asInstanceOf
