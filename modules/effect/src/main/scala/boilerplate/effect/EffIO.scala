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
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
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

/** Covariant, `cats.effect.IO`-specialised typed-error effect, represented as a PHANTOM over `IO`'s
  * own error channel: `IO[A]`.
  *
  * `EffIO` is [[boilerplate.effect.Eff Eff]] with `F` fixed to `IO`. The typed error
  * `E <: Throwable` is a compile-time phantom; a real failure rides `IO`'s native `Throwable`
  * channel, so the happy path IS `IO` - no `Either` allocation - and `absolve` is O(0) identity. It
  * shares its runtime representation with `Eff[IO, E, A]` (both are `IO[A]`) and converts to and
  * from it at zero cost.
  *
  * `EffIO` is covariant in both `E` and `A`: `IO` is covariant, and `E` is a phantom absent from
  * the representation, so a value of `EffIO[Narrow, A]` is usable wherever `EffIO[Wide, A]` is
  * expected when `Narrow <: Wide`, with no call-site method. A `flatMap`/for-comprehension over
  * steps with distinct error types therefore infers their union (`E1 | E2 | ...`); that widening is
  * silent - the channel can grow wider than intended with no compile error, so ascribe the result
  * type, or `mapError`/`catchOnly`, to contain it.
  *
  * Observing the typed channel (`either`, `catchAll`, `mapError`, `fold`, ...) filters the caught
  * `Throwable` by `TypeTest[Throwable, E]`, re-raising any non-`E` defect unchanged. For a concrete
  * `E` (a sealed `Throwable` root or a union of them) the `TypeTest` is synthesised by the
  * compiler, so no `using` clause is written at the call site. On the infallible channel (`UEffIO`,
  * `E = Nothing`) the typed error is uninhabited, so these observers are degenerate - a defect
  * always propagates and any handler is dead code.
  *
  * Refer to [[boilerplate.effect.EffIO$ EffIO]] for constructors, combinators, and type class
  * instances.
  */
opaque type EffIO[+E <: Throwable, +A] = IO[A]

/** Infallible `IO`-specialised effect: [[boilerplate.effect.EffIO EffIO]] with `Nothing` errors. */
type UEffIO[+A] = EffIO[Nothing, A]

/** Throwable-errored `IO`-specialised effect: [[boilerplate.effect.EffIO EffIO]] over `Throwable`. */
type TEffIO[+A] = EffIO[Throwable, A]

/** `TypeTest` for the empty typed channel. An infallible effect (`E = Nothing`) admits no typed
  * error, so this test never matches - every `Throwable` on the channel is a defect. Supplying it
  * lets the channel-observing combinators (`either`, `catchAll`, `fold`, ...) be summoned uniformly
  * across `E`, including `E = Nothing`, where the compiler cannot otherwise synthesise a `TypeTest`
  * for the uninhabited type.
  */
given TypeTest[Throwable, Nothing] with
  def unapply(t: Throwable): Option[t.type & Nothing] = None

/** Provides constructors, combinators, and type class instances for
  * [[boilerplate.effect.EffIO EffIO]].
  */
object EffIO extends EffIOInstances:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.EffIO EffIO]]. */
  type Of[E <: Throwable] = [A] =>> EffIO[E, A]

  /** Reifies the typed channel into an `Either`; a non-`E` defect propagates on `IO`'s channel. */
  private def reify[E, A](io: IO[A])(using tt: TypeTest[Throwable, E]): IO[Either[E, A]] =
    io.map(a => Right(a): Either[E, A]).handleErrorWith {
      case tt(e) => IO.pure(Left(e))
      case other => IO.raiseError(other)
    }

  /** Views an `Eff[IO, E, A]` as an `EffIO[E, A]`. Identity at runtime; O(0). */
  inline def fromEff[E <: Throwable, A](eff: Eff[IO, E, A]): EffIO[E, A] = Eff.unwrapUnsafe(eff)

  /** Creates a successful computation. */
  inline def succeed[A](a: A): UEffIO[A] = IO.pure(a)

  /** Creates a failed computation. */
  inline def fail[E <: Throwable](e: E): EffIO[E, Nothing] = IO.raiseError(e)

  /** Lifts a pure `Either` into the effect. */
  inline def from[E <: Throwable, A](either: Either[E, A]): EffIO[E, A] =
    either match
      case Right(a) => IO.pure(a)
      case Left(e)  => IO.raiseError(e)

  /** Converts an `Option`, supplying an error when empty. */
  inline def from[E <: Throwable, A](opt: Option[A], ifNone: => E): EffIO[E, A] =
    opt match
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(ifNone)

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  inline def from[E <: Throwable, A](result: Try[A], ifFailure: Throwable => E): EffIO[E, A] =
    result.fold(th => fail(ifFailure(th)), succeed(_))

  /** Extracts the underlying computation from `EitherT`. */
  inline def from[E <: Throwable, A](et: EitherT[IO, E, A]): EffIO[E, A] = lift(et.value)

  /** Canonical successful unit value, interned and shared across call sites. */
  val unit: UEffIO[Unit] = IO.unit

  /** Absorbs an existing `IO[Either[E, A]]` into the typed channel; a `Left` fails on `IO`'s
    * channel.
    */
  inline def lift[E <: Throwable, A](io: IO[Either[E, A]]): EffIO[E, A] =
    io.flatMap {
      case Right(a) => IO.pure(a)
      case Left(e)  => IO.raiseError(e)
    }

  /** Converts an `IO[Option[A]]`, supplying an error when empty. */
  inline def lift[E <: Throwable, A](io: IO[Option[A]], ifNone: => E): EffIO[E, A] =
    io.flatMap {
      case Some(a) => IO.pure(a)
      case None    => IO.raiseError(ifNone)
    }

  /** Embeds any `IO[A]`, treating values as successes. Identity at runtime; O(0). */
  inline def liftF[A](io: IO[A]): UEffIO[A] = io

  /** Captures throwables raised in `IO`, translating them via `ifFailure`. */
  inline def attempt[E <: Throwable, A](io: IO[A], ifFailure: Throwable => E): EffIO[E, A] =
    io.handleErrorWith(t => IO.raiseError(ifFailure(t)))

  /** Captures matching throwables as typed errors; unmatched throwables propagate as defects in
    * `IO`'s error channel.
    */
  inline def attempt[E <: Throwable, A](io: IO[A])(pf: PartialFunction[Throwable, E]): EffIO[E, A] =
    io.handleErrorWith(t => if pf.isDefinedAt(t) then IO.raiseError(pf(t)) else IO.raiseError(t))

  /** Suspends evaluation until demanded. */
  inline def defer[E <: Throwable, A](thunk: => EffIO[E, A]): EffIO[E, A] = IO.defer(thunk)

  /** Suspends a side-effecting computation that yields an `Either[E, A]`; for an infallible side
    * effect use [[suspend]].
    */
  inline def delay[E <: Throwable, A](ea: => Either[E, A]): EffIO[E, A] = lift(IO.delay(ea))

  /** Suspends a synchronous side effect as a success value; for typed errors use [[delay]]. */
  inline def suspend[A](thunk: => A): UEffIO[A] = IO.delay(thunk)

  /** As [[delay]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def blocking[E <: Throwable, A](ea: => Either[E, A]): EffIO[E, A] = lift(IO.blocking(ea))

  /** As [[suspend]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def suspendBlocking[A](thunk: => A): UEffIO[A] = IO.blocking(thunk)

  /** Suspends execution for the specified duration. */
  inline def sleep(duration: FiniteDuration): UEffIO[Unit] = IO.sleep(duration)

  /** Returns the current monotonic time as a `FiniteDuration`. */
  inline def monotonic: UEffIO[FiniteDuration] = IO.monotonic

  /** Returns the current wall-clock time as a `FiniteDuration` since the epoch. */
  inline def realTime: UEffIO[FiniteDuration] = IO.realTime

  /** Creates a new `Ref` initialised with `a`, operating in the `EffIO` context. */
  inline def ref[E <: Throwable, A](a: A): EffIO[E, Ref[Of[E], A]] =
    IO.ref(a).map(_.asInstanceOf[Ref[Of[E], A]]) // scalafix:ok DisableSyntax.asInstanceOf

  /** Creates an empty `Deferred` operating in the `EffIO` context. */
  inline def deferred[E <: Throwable, A]: EffIO[E, Deferred[Of[E], A]] =
    IO.deferred[A].map(_.asInstanceOf[Deferred[Of[E], A]]) // scalafix:ok DisableSyntax.asInstanceOf

  /** Introduces a self-cancellation point, immediately cancelling the current fibre. */
  val canceled: UEffIO[Unit] = IO.canceled

  /** Introduces a cooperative yielding point. */
  val cede: UEffIO[Unit] = IO.cede

  /** A computation that never completes. */
  val never: UEffIO[Nothing] = IO.never

  /** Converts a `Future` into an `EffIO`, translating failures via `ifFailure`. */
  inline def fromFuture[E <: Throwable, A](future: IO[Future[A]], ifFailure: Throwable => E): EffIO[E, A] =
    IO.fromFuture(future).handleErrorWith(t => IO.raiseError(ifFailure(t)))

  /** Converts a `Future` into an `EffIO`, catching matching throwables as typed errors; unmatched
    * throwables propagate as defects in `IO`'s error channel.
    */
  inline def fromFuture[E <: Throwable, A](future: IO[Future[A]])(pf: PartialFunction[Throwable, E]): EffIO[E, A] =
    IO.fromFuture(future).handleErrorWith(t => if pf.isDefinedAt(t) then IO.raiseError(pf(t)) else IO.raiseError(t))

  /** Suspends an asynchronous callback-driven computation completing with a typed `Either[E, A]`.
    *
    * The callback is invoked with `Left(e)` for a typed error or `Right(a)` for success - there is
    * no defect-channel nesting. A raised throwable surfaces as a defect in `IO`'s error channel;
    * use [[asyncAttempt]] to fold it into a typed error instead. The returned
    * `IO[Option[IO[Unit]]]` optionally yields a finaliser run on cancellation.
    */
  inline def async[E <: Throwable, A](k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]): EffIO[E, A] =
    IO.async[A](cb => k(ea => cb(ea)))

  /** As [[async]], additionally folding a throwable raised while registering the callback into a
    * typed error via `ifDefect`. A typed error delivered through the callback (`Left(e)`) passes
    * through unchanged, and cancellation is never folded. Needs no `TypeTest`, so it works for an
    * abstract `E` - a registration-time failure is a defect by construction.
    */
  inline def asyncAttempt[E <: Throwable, A](ifDefect: Throwable => E)(
    k: (Either[E, A] => Unit) => IO[Option[IO[Unit]]]
  ): EffIO[E, A] =
    IO.async[A](cb => k(ea => cb(ea)).handleErrorWith(t => IO.raiseError(ifDefect(t))))

  /** Executes `eff` only when `cond` is true, otherwise succeeds with `Unit`. */
  inline def when[E <: Throwable](cond: Boolean)(eff: => EffIO[E, Unit]): EffIO[E, Unit] =
    if cond then eff else unit

  /** Executes `eff` only when `cond` is false, otherwise succeeds with `Unit`. */
  inline def unless[E <: Throwable](cond: Boolean)(eff: => EffIO[E, Unit]): EffIO[E, Unit] =
    if cond then unit else eff

  /** Raises an error when `cond` is true, otherwise succeeds with `Unit`. */
  inline def raiseWhen[E <: Throwable](cond: Boolean)(e: => E): EffIO[E, Unit] =
    if cond then fail(e) else unit

  /** Raises an error when `cond` is false, otherwise succeeds with `Unit`. */
  inline def raiseUnless[E <: Throwable](cond: Boolean)(e: => E): EffIO[E, Unit] =
    if cond then unit else fail(e)

  /** Lifts a Boolean predicate into a typed-error effect. Both branches are evaluated lazily; the
    * unselected branch is never run.
    */
  inline def cond[E <: Throwable, A](pred: Boolean, ifTrue: => A, ifFalse: => E): EffIO[E, A] =
    if pred then succeed(ifTrue) else fail(ifFalse)

  /** Traverses a collection, short-circuiting on first error. */
  inline def traverse[E <: Throwable, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, List[B]] =
    fromEff(Eff.traverse[IO, E, A, B](as)(a => f(a).toEff))

  /** Sequences a collection of effects, short-circuiting on first error. */
  inline def sequence[E <: Throwable, A](effs: Iterable[EffIO[E, A]]): EffIO[E, List[A]] =
    traverse(effs)(identity)

  /** Traverses a collection for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("traverseUnit")
  inline def traverse_[E <: Throwable, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, Unit] =
    fromEff(Eff.traverse_[IO, E, A, B](as)(a => f(a).toEff))

  /** Runs a collection of effects for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("sequenceUnit")
  inline def sequence_[E <: Throwable, A](effs: Iterable[EffIO[E, A]]): EffIO[E, Unit] =
    traverse_(effs)(identity)

  /** Traverses a collection in parallel. */
  inline def parTraverse[E <: Throwable, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, List[B]] =
    fromEff(Eff.parTraverse[IO, E, A, B](as)(a => f(a).toEff))

  /** Sequences a collection of effects in parallel. */
  inline def parSequence[E <: Throwable, A](effs: Iterable[EffIO[E, A]]): EffIO[E, List[A]] =
    parTraverse(effs)(identity)

  /** Traverses a collection in parallel for effect only, discarding results. */
  @targetName("parTraverseUnit")
  inline def parTraverse_[E <: Throwable, A, B](as: Iterable[A])(f: A => EffIO[E, B]): EffIO[E, Unit] =
    fromEff(Eff.parTraverse_[IO, E, A, B](as)(a => f(a).toEff))

  /** Sequences a collection of effects in parallel for effect only, discarding results. */
  @targetName("parSequenceUnit")
  inline def parSequence_[E <: Throwable, A](effs: Iterable[EffIO[E, A]]): EffIO[E, Unit] =
    parTraverse_(effs)(identity)

  /** Retries the effect up to `maxRetries` times on failure. */
  inline def retry[E <: Throwable, A](eff: EffIO[E, A], maxRetries: Int)(using TypeTest[Throwable, E]): EffIO[E, A] =
    fromEff(Eff.retry[IO, E, A](eff.toEff, maxRetries))

  /** Retries the effect with exponential backoff, capping each delay at `maxDelay`. */
  inline def retryWithBackoff[E <: Throwable, A](
    eff: EffIO[E, A],
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Option[FiniteDuration]
  )(using TypeTest[Throwable, E]): EffIO[E, A] =
    fromEff(Eff.retryWithBackoff[IO, E, A](eff.toEff, maxRetries, initialDelay, maxDelay))

  extension [E <: Throwable, A](self: EffIO[E, A])
    /** Views this effect as an invariant `Eff[IO, E, A]`. Identity at runtime; O(0). */
    inline def toEff: Eff[IO, E, A] = Eff.wrapUnsafe(self)

    /** Reifies to `IO[Either[E, A]]`; a non-`E` defect propagates on `IO`'s channel. */
    inline def either(using TypeTest[Throwable, E]): IO[Either[E, A]] = reify[E, A](self)

    /** Absorbs the typed error into `IO`. O(0) identity - the failure is already there. */
    inline def absolve: IO[A] = self

    /** Maps the success channel while preserving the error type. */
    inline def map[B](f: A => B): EffIO[E, B] = (self: IO[A]).map(f)

    /** Sequences computations, widening the error channel on demand. */
    inline def flatMap[E2 <: Throwable, B](f: A => EffIO[E2, B]): EffIO[E2, B] =
      (self: IO[A]).flatMap(a => f(a))

    /** Maps the success value through an effectful function. */
    inline def semiflatMap[B](f: A => IO[B]): EffIO[E, B] = (self: IO[A]).flatMap(f)

    /** Flat-maps the success through a pure `Either`-returning function; a `Left` fails. */
    inline def subflatMap[E2 <: Throwable, B](f: A => Either[E2, B]): EffIO[E2, B] =
      (self: IO[A]).flatMap(a =>
        f(a) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** Transforms the entire reified `Either` structure. */
    inline def transform[E2 <: Throwable, B](f: Either[E, A] => Either[E2, B])(using TypeTest[Throwable, E]): EffIO[E2, B] =
      reify[E, A](self).flatMap(ea =>
        f(ea) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** Handles any failure by switching to an alternative computation. */
    inline def catchAll[E2 <: Throwable, B >: A](f: E => EffIO[E2, B])(using tt: TypeTest[Throwable, E]): EffIO[E2, B] =
      (self: IO[B]).handleErrorWith {
        case tt(e) => f(e)
        case other => IO.raiseError(other)
      }

    /** Recovers the errors `pf` handles with an effect; unmatched errors pass through, widening to
      * `E2`. The effectful sibling of [[mapErrorPartial]], pairing with [[catchAll]].
      */
    inline def catchSome[E2 >: E <: Throwable, B >: A](pf: PartialFunction[E, EffIO[E2, B]])(using
      tt: TypeTest[Throwable, E]): EffIO[E2, B] =
      (self: IO[B]).handleErrorWith {
        case tt(e) if pf.isDefinedAt(e) => pf(e)
        case other                      => IO.raiseError(other)
      }

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
    inline def catchOnly[H, R <: Throwable, B >: A](f: H => EffIO[R, B])(using
      ev: E <:< (R | H),
      tt: TypeTest[Throwable, H]
    ): EffIO[R, B] =
      val _ = ev
      (self: IO[B]).handleErrorWith {
        case tt(h) => f(h)
        case other => IO.raiseError(other)
      }

    /** Handles both error and success with effectful functions, allowing error type change. */
    inline def redeemAll[E2 <: Throwable, B](fe: E => EffIO[E2, B], fa: A => EffIO[E2, B])(using TypeTest[Throwable, E]): EffIO[E2, B] =
      reify[E, A](self).flatMap {
        case Left(e)  => fe(e)
        case Right(a) => fa(a)
      }

    /** Folds over both channels, returning to the base `IO`. */
    inline def fold[B](fe: E => B, fa: A => B)(using TypeTest[Throwable, E]): IO[B] =
      reify[E, A](self).map(_.fold(fe, fa))

    /** Effectfully folds both channels, allowing different continuations. */
    inline def foldF[B](fe: E => IO[B], fa: A => IO[B])(using TypeTest[Throwable, E]): IO[B] =
      reify[E, A](self).flatMap(_.fold(fe, fa))

    /** Transforms the error channel. */
    inline def mapError[E2 <: Throwable](f: E => E2)(using tt: TypeTest[Throwable, E]): EffIO[E2, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => IO.raiseError(f(e))
        case other => IO.raiseError(other)
      }

    /** Transforms the error channel partially; unmatched errors pass through. */
    inline def mapErrorPartial[E2 >: E <: Throwable](pf: PartialFunction[E, E2])(using tt: TypeTest[Throwable, E]): EffIO[E2, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => IO.raiseError(pf.applyOrElse(e, (x: E) => x))
        case other => IO.raiseError(other)
      }

    /** Fallback to an alternative computation when this one fails with a typed error. */
    inline def alt[E2 <: Throwable, B >: A](that: => EffIO[E2, B])(using tt: TypeTest[Throwable, E]): EffIO[E2, B] =
      (self: IO[B]).handleErrorWith {
        case tt(_) => that
        case other => IO.raiseError(other)
      }

    /** Recovers from any typed failure with a constant success value. */
    inline def orElseSucceed[B >: A](value: => B)(using tt: TypeTest[Throwable, E]): UEffIO[B] =
      (self: IO[B]).handleErrorWith {
        case tt(_) => IO.pure(value)
        case other => IO.raiseError(other)
      }

    /** Replaces any typed failure with a different error. */
    inline def orElseFail[E2 <: Throwable](error: => E2)(using tt: TypeTest[Throwable, E]): EffIO[E2, A] =
      (self: IO[A]).handleErrorWith {
        case tt(_) => IO.raiseError(error)
        case other => IO.raiseError(other)
      }

    /** Recovers from all typed errors by mapping them to a success value. */
    inline def valueOr(f: E => A)(using tt: TypeTest[Throwable, E]): UEffIO[A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => IO.pure(f(e))
        case other => IO.raiseError(other)
      }

    /** Observes typed failures without altering the result.
      *
      * The side effect is a raw `IO[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    inline def tapError(f: E => IO[Unit])(using tt: TypeTest[Throwable, E]): EffIO[E, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => f(e).flatMap(_ => IO.raiseError(e))
        case other => IO.raiseError(other)
      }

    /** Observes typed failures via an effectful action that can also fail.
      *
      * If the side effect fails, that failure propagates and replaces the original error. For
      * infallible side effects, use [[tapError]].
      */
    inline def flatTapError(f: E => EffIO[E, Unit])(using tt: TypeTest[Throwable, E]): EffIO[E, A] =
      (self: IO[A]).handleErrorWith {
        case tt(e) => f(e).flatMap(_ => IO.raiseError(e))
        case other => IO.raiseError(other)
      }

    /** Observes success values without altering the result. */
    inline def tap(f: A => IO[Unit]): EffIO[E, A] = (self: IO[A]).flatMap(a => f(a).map(_ => a))

    /** Observes the reified attempt result without altering the outcome. Defects propagate through
      * without observation.
      */
    inline def attemptTap(f: Either[E, A] => EffIO[E, Unit])(using TypeTest[Throwable, E]): EffIO[E, A] =
      reify[E, A](self).flatMap { ea =>
        f(ea).flatMap { _ =>
          ea match
            case Right(a) => IO.pure(a)
            case Left(e)  => IO.raiseError(e)
        }
      }

    /** Converts to an infallible effect returning `Option[A]`, treating typed errors as `None`. */
    inline def option(using TypeTest[Throwable, E]): UEffIO[Option[A]] =
      reify[E, A](self).map(_.toOption)

    /** Extracts an inner `Option[B]` value, failing with `ifNone` when absent. */
    inline def collectSome[B](ifNone: => E)(using ev: A <:< Option[B]): EffIO[E, B] =
      (self: IO[A]).flatMap(a =>
        ev(a) match
          case Some(b) => IO.pure(b)
          case None    => IO.raiseError(ifNone)
      )

    /** Extracts an inner `Either[L, B]` value, mapping left to error via `ifLeft`. */
    inline def collectRight[L, B](ifLeft: L => E)(using ev: A <:< Either[L, B]): EffIO[E, B] =
      (self: IO[A]).flatMap(a =>
        ev(a) match
          case Right(b) => IO.pure(b)
          case Left(l)  => IO.raiseError(ifLeft(l))
      )

    /** Converts to `EitherT` for ecosystem interop. */
    inline def eitherT(using TypeTest[Throwable, E]): EitherT[IO, E, A] = EitherT(reify[E, A](self))

    // scalafix:off DisableSyntax.asInstanceOf
    /** Treats the error type as a subtype, for trusted casts. */
    transparent inline def assumeError[E2 <: E]: EffIO[E2, A] = (self: IO[A]).asInstanceOf[EffIO[E2, A]]

    /** Treats the success channel as a subtype, for trusted casts. */
    transparent inline def assume[B <: A]: EffIO[E, B] = (self: IO[A]).asInstanceOf[EffIO[E, B]]
    // scalafix:on

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    inline def *>[B](that: => EffIO[E, B]): EffIO[E, B] = (self: IO[A]).flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    inline def <*[B](that: => EffIO[E, B]): EffIO[E, A] = (self: IO[A]).flatMap(a => (that: IO[B]).map(_ => a))

    /** Sequences this computation with `that`, discarding the result of `this`. */
    inline def productR[B](that: => EffIO[E, B]): EffIO[E, B] = (self: IO[A]).flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    inline def productL[B](that: => EffIO[E, B]): EffIO[E, A] = (self: IO[A]).flatMap(a => (that: IO[B]).map(_ => a))

    /** Combines this computation with `that` into a tuple. */
    inline def product[B](that: EffIO[E, B]): EffIO[E, (A, B)] =
      (self: IO[A]).flatMap(a => (that: IO[B]).map(b => (a, b)))

    /** Applies an effectful function to the success value, discarding its result. */
    inline def flatTap[B](f: A => EffIO[E, B]): EffIO[E, A] =
      (self: IO[A]).flatMap(a => (f(a): IO[B]).map(_ => a))

    /** Discards the success value, returning `Unit`. */
    inline def void: EffIO[E, Unit] = (self: IO[A]).map(_ => ())

    /** Replaces the success value with `b`. */
    inline def as[B](b: B): EffIO[E, B] = (self: IO[A]).map(_ => b)

    /** Acquires a resource, uses it, and ensures release even on failure. */
    inline def bracket[B](use: A => EffIO[E, B])(release: A => IO[Unit]): EffIO[E, B] =
      (self: IO[A]).bracket(a => use(a))(release)

    /** Acquires a resource, uses it, and ensures release with outcome information. */
    inline def bracketCase[B](use: A => EffIO[E, B])(
      release: (A, Outcome[Of[E], Throwable, B]) => IO[Unit]
    ): EffIO[E, B] =
      (self: IO[A]).bracketCase(a => use(a))((a, oc) => release(a, oc.asInstanceOf[Outcome[Of[E], Throwable, B]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Starts this computation as a fibre, returning immediately. A fibre completing with a typed
      * error is an `Outcome.Errored`.
      */
    inline def start: EffIO[E, Fiber[Of[E], Throwable, A]] =
      (self: IO[A]).start.map(_.asInstanceOf[Fiber[Of[E], Throwable, A]]) // scalafix:ok DisableSyntax.asInstanceOf

    /** Runs this computation as a background fibre, cancelling it on scope exit. */
    inline def background: Resource[IO, IO[Outcome[Of[E], Throwable, A]]] =
      (self: IO[A]).background.asInstanceOf[Resource[IO, IO[Outcome[Of[E], Throwable, A]]]] // scalafix:ok DisableSyntax.asInstanceOf

    /** Ensures `fin` runs with the completion outcome after this computation. */
    inline def guaranteeCase(fin: Outcome[Of[E], Throwable, A] => IO[Unit]): EffIO[E, A] =
      (self: IO[A]).guaranteeCase(oc => fin(oc.asInstanceOf[Outcome[Of[E], Throwable, A]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Races this computation against `that`, returning the winner's result. */
    inline def race[B](that: EffIO[E, B]): EffIO[E, Either[A, B]] = IO.race(self, that)

    /** Runs this computation and `that` concurrently, returning both results. */
    inline def both[B](that: EffIO[E, B]): EffIO[E, (A, B)] = IO.both(self, that)

    /** Runs this computation and `that` in parallel, discarding the result of `this`. */
    @targetName("parProductR")
    inline def &>[B](that: EffIO[E, B]): EffIO[E, B] = IO.both(self, that).map(_._2)

    /** Runs this computation and `that` in parallel, discarding the result of `that`. */
    @targetName("parProductL")
    inline def <&[B](that: EffIO[E, B]): EffIO[E, A] = IO.both(self, that).map(_._1)

    /** Registers a finaliser to run if this computation is cancelled. */
    inline def onCancel(fin: EffIO[E, Unit]): EffIO[E, A] = (self: IO[A]).onCancel(fin)

    /** Ensures `fin` runs after this computation regardless of outcome. */
    inline def guarantee(fin: EffIO[E, Unit]): EffIO[E, A] = (self: IO[A]).guarantee(fin)

    /** Delays execution of this computation by `duration`. */
    inline def delayBy(duration: FiniteDuration): EffIO[E, A] = (self: IO[A]).delayBy(duration)

    /** Executes this computation, then waits for `duration` before returning. */
    inline def andWait(duration: FiniteDuration): EffIO[E, A] = (self: IO[A]).andWait(duration)

    /** Returns the result paired with the execution duration. */
    inline def timed: EffIO[E, (FiniteDuration, A)] = (self: IO[A]).timed

    /** Fails with `onTimeout` if the computation does not complete within `duration`. */
    inline def timeout(duration: FiniteDuration, onTimeout: => E): EffIO[E, A] =
      (self: IO[A]).timeoutTo(duration, IO.raiseError(onTimeout))

    /** Returns `fallback` if this computation does not complete within `duration`. */
    inline def timeoutTo[B >: A](duration: FiniteDuration, fallback: => EffIO[E, B]): EffIO[E, B] =
      (self: IO[B]).timeoutTo(duration, fallback)
  end extension

  // Channel-observers on the infallible (`Nothing`) channel. The typed error is uninhabited, but the
  // general observers' `TypeTest[Throwable, E]` widens `E` to `Throwable` here - the covariant
  // receiver admits any `E`, and resolving the test pins `E := Throwable` - turning the test into the
  // identity and capturing defects. These overloads pin `E = Nothing` via a more specific receiver;
  // each is degenerate and correct by construction - an error handler can never fire, so it is
  // dropped and the effect passes through `self` (defects included), while a success observer maps
  // the value. No `TypeTest`, no `reify`.
  extension [A](self: EffIO[Nothing, A])
    /** The success reified as `Right`; a defect propagates. */
    inline def either: IO[Either[Nothing, A]] = (self: IO[A]).map(Right(_))

    /** Applies `f` to the (always-`Right`) success; a `Left` result fails, a defect propagates. */
    inline def transform[E2 <: Throwable, B](f: Either[Nothing, A] => Either[E2, B]): EffIO[E2, B] =
      (self: IO[A]).flatMap(a =>
        f(Right(a)) match
          case Right(b) => IO.pure(b)
          case Left(e)  => IO.raiseError(e)
      )

    /** No typed error to catch; identity. */
    inline def catchAll[E2 <: Throwable, B >: A](@unused f: Nothing => EffIO[E2, B]): EffIO[E2, B] = self

    /** No typed error to catch; identity. */
    inline def catchSome[E2 <: Throwable, B >: A](@unused pf: PartialFunction[Nothing, EffIO[E2, B]]): EffIO[E2, B] = self

    /** No typed error to catch; identity. */
    inline def catchOnly[H, R <: Throwable, B >: A](@unused f: H => EffIO[R, B]): EffIO[R, B] = self

    /** No typed error; `fa` folds the success. */
    inline def redeemAll[E2 <: Throwable, B](@unused fe: Nothing => EffIO[E2, B], fa: A => EffIO[E2, B]): EffIO[E2, B] =
      (self: IO[A]).flatMap(a => fa(a))

    /** No typed error; `fa` folds the success. */
    inline def fold[B](@unused fe: Nothing => B, fa: A => B): IO[B] = (self: IO[A]).map(fa)

    /** No typed error; `fa` folds the success. */
    inline def foldF[B](@unused fe: Nothing => IO[B], fa: A => IO[B]): IO[B] = (self: IO[A]).flatMap(fa)

    /** No typed error to map; identity. */
    inline def mapError[E2 <: Throwable](@unused f: Nothing => E2): EffIO[E2, A] = self

    /** No typed error to map; identity. */
    inline def mapErrorPartial[E2 <: Throwable](@unused pf: PartialFunction[Nothing, E2]): EffIO[E2, A] = self

    /** Never fails typed; identity. */
    inline def alt[E2 <: Throwable, B >: A](@unused that: => EffIO[E2, B]): EffIO[E2, B] = self

    /** Never fails typed; identity. */
    inline def orElseSucceed[B >: A](@unused value: => B): UEffIO[B] = self

    /** Never fails typed; identity. */
    inline def orElseFail[E2 <: Throwable](@unused error: => E2): EffIO[E2, A] = self

    /** Never fails typed; identity. */
    inline def valueOr(@unused f: Nothing => A): UEffIO[A] = self

    /** No typed error to observe; identity. */
    inline def tapError(@unused f: Nothing => IO[Unit]): EffIO[Nothing, A] = self

    /** No typed error to observe; identity. */
    inline def flatTapError(@unused f: Nothing => EffIO[Nothing, Unit]): EffIO[Nothing, A] = self

    /** The attempt is always `Right`; `f` observes it, then the value passes through. */
    inline def attemptTap(f: Either[Nothing, A] => EffIO[Nothing, Unit]): EffIO[Nothing, A] =
      (self: IO[A]).flatMap(a => (f(Right(a)): IO[Unit]).flatMap(_ => IO.pure(a)))

    /** The success wrapped as `Some`; a defect propagates. */
    inline def option: UEffIO[Option[A]] = (self: IO[A]).map(Some(_))

    /** The success reified as `Right`; a defect propagates. */
    inline def eitherT: EitherT[IO, Nothing, A] = EitherT((self: IO[A]).map(Right(_)))
  end extension

  /** Error-widening natural transformation. Identity at runtime - `EffIO` is covariant in `E`.
    *
    * The `~>` value is still required for invariant positions: `Resource`, `Stream`, and `Pipe`
    * cannot widen their effect parameter by subtyping.
    */
  inline def widenK[E1 <: Throwable, E2 >: E1 <: Throwable]: Of[E1] ~> Of[E2] = new WidenK[E1, E2]

  /** Lifts plain `IO` into the infallible `EffIO` context.
    *
    * Required wherever a value-level `~>` is needed - lifting `IO` into invariant positions such as
    * `Resource`, `Stream`, or `Pipe`.
    */
  val liftK: IO ~> Of[Nothing] = new LiftK[Nothing]

  /** Identity error-widening natural transformation for [[boilerplate.effect.EffIO EffIO]]. */
  private[effect] class WidenK[E1 <: Throwable, E2 >: E1 <: Throwable] @publicInBinary private[EffIO] () extends FunctionK[Of[E1], Of[E2]]:
    def apply[A](fa: EffIO[E1, A]): EffIO[E2, A] = fa

  /** Lifts `IO` into [[boilerplate.effect.EffIO EffIO]] as an infallible computation. */
  private[effect] class LiftK[E <: Throwable] @publicInBinary private[EffIO] () extends FunctionK[IO, Of[E]]:
    def apply[A](io: IO[A]): EffIO[E, A] = liftF(io)

  // scalafix:off DisableSyntax.asInstanceOf
  /** Transforms a `Resource[IO, A]` to `Resource[EffIO.Of[E], A]`. O(0) - `Of[E]` is `IO`. */
  inline def liftResource[E <: Throwable, A](resource: Resource[IO, A]): Resource[Of[E], A] =
    resource.asInstanceOf[Resource[Of[E], A]]

  /** Transforms a `Ref[IO, A]` to `Ref[EffIO.Of[E], A]`. */
  inline def liftRef[E <: Throwable, A](ref: Ref[IO, A]): Ref[Of[E], A] =
    ref.asInstanceOf[Ref[Of[E], A]]

  /** Transforms a `Deferred[IO, A]` to `Deferred[EffIO.Of[E], A]`. */
  inline def liftDeferred[E <: Throwable, A](deferred: Deferred[IO, A]): Deferred[Of[E], A] =
    deferred.asInstanceOf[Deferred[Of[E], A]]

  /** Transforms a `Queue[IO, A]` to `Queue[EffIO.Of[E], A]`. */
  inline def liftQueue[E <: Throwable, A](queue: Queue[IO, A]): Queue[Of[E], A] =
    queue.asInstanceOf[Queue[Of[E], A]]

  /** Transforms a `Semaphore[IO]` to `Semaphore[EffIO.Of[E]]`. */
  inline def liftSemaphore[E <: Throwable](semaphore: Semaphore[IO]): Semaphore[Of[E]] =
    semaphore.asInstanceOf[Semaphore[Of[E]]]

  /** Transforms a `CountDownLatch[IO]` to `CountDownLatch[EffIO.Of[E]]`. */
  inline def liftLatch[E <: Throwable](latch: CountDownLatch[IO]): CountDownLatch[Of[E]] =
    latch.asInstanceOf[CountDownLatch[Of[E]]]

  /** Transforms a `CyclicBarrier[IO]` to `CyclicBarrier[EffIO.Of[E]]`. */
  inline def liftBarrier[E <: Throwable](barrier: CyclicBarrier[IO]): CyclicBarrier[Of[E]] =
    barrier.asInstanceOf[CyclicBarrier[Of[E]]]

  /** Transforms an `AtomicCell[IO, A]` to `AtomicCell[EffIO.Of[E], A]`. */
  inline def liftCell[E <: Throwable, A](cell: AtomicCell[IO, A]): AtomicCell[Of[E], A] =
    cell.asInstanceOf[AtomicCell[Of[E], A]]

  /** Transforms a `Supervisor[IO]` to `Supervisor[EffIO.Of[E]]`. */
  inline def liftSupervisor[E <: Throwable](supervisor: Supervisor[IO]): Supervisor[Of[E]] =
    supervisor.asInstanceOf[Supervisor[Of[E]]]

  /** Plain `Monad` (hence `Functor`/`Invariant`), sourced from `IO` without a `TypeTest`. Mirrors
    * `Eff`'s fallback so that `Functor`/`Monad`/`Invariant[EffIO.Of[E]]` resolve even for an
    * abstract `E`, where the typed `MonadError` below cannot synthesise its `TypeTest`. For a
    * concrete `E` the more specific `MonadError` still wins.
    */
  given [E <: Throwable] => Monad[Of[E]] =
    IO.asyncForIO.asInstanceOf[Monad[Of[E]]]

  /** Canonical `MonadError` for the typed error channel `E`. */
  given [E <: Throwable] => (tt: TypeTest[Throwable, E]) => MonadError[Of[E], E]:
    def pure[A](a: A): EffIO[E, A] = IO.pure(a)
    def flatMap[A, B](fa: EffIO[E, A])(f: A => EffIO[E, B]): EffIO[E, B] = (fa: IO[A]).flatMap(a => f(a))
    // Reference `IO`'s instance by name, not `summon[Monad[IO]]`: inside this object `EffIO.Of[E]` is
    // structurally `IO`, so a summon would resolve back to this very given and loop.
    def tailRecM[A, B](a: A)(f: A => EffIO[E, Either[A, B]]): EffIO[E, B] =
      IO.asyncForIO.tailRecM(a)(x => f(x): IO[Either[A, B]])
    def raiseError[A](e: E): EffIO[E, A] = IO.raiseError(e)
    def handleErrorWith[A](fa: EffIO[E, A])(f: E => EffIO[E, A]): EffIO[E, A] =
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
    def combineK[A](x: EffIO[E, A], y: EffIO[E, A]): EffIO[E, A] =
      (x: IO[A]).handleErrorWith {
        case tt(_) => y
        case other => IO.raiseError(other)
      }

  /** Combines two successful computations using `Semigroup` on their values. */
  given [E <: Throwable, A] => (S: Semigroup[A]) => Semigroup[EffIO[E, A]]:
    def combine(x: EffIO[E, A], y: EffIO[E, A]): EffIO[E, A] =
      (x: IO[A]).flatMap(a => (y: IO[A]).map(b => S.combine(a, b)))

  /** Combines `EffIO` computations with an identity element from `Monoid`. */
  given [E <: Throwable, A] => (M: Monoid[A]) => Monoid[EffIO[E, A]]:
    def empty: EffIO[E, A] = IO.pure(M.empty)
    def combine(x: EffIO[E, A], y: EffIO[E, A]): EffIO[E, A] =
      (x: IO[A]).flatMap(a => (y: IO[A]).map(b => M.combine(a, b)))

  /** `Show` delegating to the underlying `IO[A]`. */
  given [E <: Throwable, A] => (S: Show[IO[A]]) => Show[EffIO[E, A]] = S.asInstanceOf[Show[EffIO[E, A]]]

  /** `Eq` delegating to the underlying `IO[A]`. */
  given [E <: Throwable, A] => (E0: Eq[IO[A]]) => Eq[EffIO[E, A]] = E0.asInstanceOf[Eq[EffIO[E, A]]]

  /** `PartialOrder` delegating to the underlying `IO[A]`. */
  given [E <: Throwable, A] => (P: PartialOrder[IO[A]]) => PartialOrder[EffIO[E, A]] = P.asInstanceOf[PartialOrder[EffIO[E, A]]]
  // scalafix:on
end EffIO

/** Lower-priority instance scope for [[boilerplate.effect.EffIO EffIO]]. */
private[effect] trait EffIOInstances:
  /** `Async` for `EffIO`, and by subtyping every effect type class it extends. Reference `IO`'s
    * instance by name: inside `EffIO`'s scope `EffIO.Of[E]` is `IO`, so a summon could loop.
    */
  given [E <: Throwable] => Async[EffIO.Of[E]] =
    IO.asyncForIO.asInstanceOf[Async[EffIO.Of[E]]] // scalafix:ok DisableSyntax.asInstanceOf
