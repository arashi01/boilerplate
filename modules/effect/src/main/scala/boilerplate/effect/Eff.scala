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

import cats.Applicative
import cats.ApplicativeError
import cats.Defer
import cats.Eq
import cats.Functor
import cats.Monad
import cats.MonadError
import cats.MonadThrow
import cats.Monoid
import cats.Parallel
import cats.Semigroup
import cats.SemigroupK
import cats.Show
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.kernel.Async
import cats.effect.kernel.Clock
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
import cats.effect.kernel.GenConcurrent
import cats.effect.kernel.GenSpawn
import cats.effect.kernel.GenTemporal
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.kernel.Unique
import cats.effect.std.AtomicCell
import cats.effect.std.CountDownLatch
import cats.effect.std.CyclicBarrier
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.effect.std.Supervisor
import cats.kernel.PartialOrder
import cats.~>

/** Zero-cost typed-error effect represented as a PHANTOM over the base effect `F`'s own error
  * channel.
  *
  * The representation is exactly `F[A]`. The typed error `E <: Throwable` never exists at runtime;
  * it is a compile-time phantom the combinators track, and an actual failure rides in `F`'s native
  * `Throwable` channel. Consequences:
  *   - the happy path (`succeed`/`map`/`flatMap`) IS `F` - no `Either` allocation, `flatMap` is
  *     `F.flatMap` with a failure short-circuiting natively;
  *   - `absolve` is O(0) identity (the failure is already in `F`'s channel);
  *   - error observation filters the caught `Throwable` by `TypeTest[Throwable, E]`, re-raising any
  *     non-`E` defect unchanged, and therefore needs `MonadThrow[F]`. On the infallible channel
  *     (`UEff`, `E = Nothing`) the typed error is uninhabited, so the observers are degenerate - a
  *     defect always propagates and any handler is dead code.
  *
  * `E` is a phantom absent from the representation, so `Eff` is '''covariant in `E`''': a value of
  * `Eff[F, Narrow, A]` is usable wherever `Eff[F, Wide, A]` is expected when `Narrow <: Wide`, with
  * no call-site method. A `flatMap`/for-comprehension over steps with distinct error types
  * therefore infers their union (`E1 | E2 | ...`); that widening is silent - ascribe the result
  * type, or `mapError`/`catchOnly`, to contain it. `A` stays invariant because `F` is an arbitrary
  * constructor.
  *
  * `Eff.Of[F, E]` is structurally `F` (the phantom erases), so every cats and cats-effect instance
  * transfers by representation cast rather than a hand-written typeclass ladder. It shares its
  * runtime representation with [[boilerplate.effect.EffIO EffIO]] when `F = IO`, converting at zero
  * cost.
  *
  * Refer to [[boilerplate.effect.Eff$ Eff]] for constructors, combinators, and type class
  * instances.
  */
opaque type Eff[F[_], +E <: Throwable, A] = F[A]

/** Infallible effect: [[boilerplate.effect.Eff Eff]] with `Nothing` as the error type. */
type UEff[F[_], A] = Eff[F, Nothing, A]

/** Throwable-errored effect: [[boilerplate.effect.Eff Eff]] with `Throwable` as the error type. */
type TEff[F[_], A] = Eff[F, Throwable, A]

/** Base of the effect-typeclass ladder for `Eff`. Because `Eff.Of[F, E]` is structurally `F`, each
  * cats-effect capability transfers by a representation cast (`Async[F]` '''is'''
  * `Async[Of[F, E]]`). The instances are split across priority traits so that summoning
  * `Functor`/`Monad`/`MonadError[_, E]` resolves to the dedicated typed-error instances in
  * [[Eff$ Eff]] rather than these `Async`-derived ones (`Async` and `MonadError[_, E]` are both
  * `<: Monad`, yet incomparable). A fibre's typed error `E` rides `F`'s `Throwable` channel, so a
  * typed failure is `Outcome.Errored(e)`.
  */
private[effect] trait EffInstances0:
  import Eff.Of

  // scalafix:off DisableSyntax.asInstanceOf
  /** Delegates cancellation semantics from `F` (defect channel `EE`) whilst retaining typed errors. */
  given [F[_], E <: Throwable, EE] => (MC: MonadCancel[F, EE]) => MonadCancel[Of[F, E], EE] =
    MC.asInstanceOf[MonadCancel[Of[F, E], EE]]

  /** Lifts a `MonadError` from `F` itself, propagating external failures on the defect channel
    * `EE`.
    */
  given [F[_], E <: Throwable, EE] => (F0: MonadError[F, EE]) => MonadError[Of[F, E], EE] =
    F0.asInstanceOf[MonadError[Of[F, E], EE]]
  // scalafix:on
end EffInstances0

private[effect] trait EffInstances1 extends EffInstances0:
  import Eff.Of

  /** `GenSpawn` transfers by representation - `start`/`race`/fibres over `F`. */
  given [F[_], E <: Throwable] => (S: GenSpawn[F, Throwable]) => GenSpawn[Of[F, E], Throwable] =
    S.asInstanceOf[GenSpawn[Of[F, E], Throwable]] // scalafix:ok DisableSyntax.asInstanceOf

private[effect] trait EffInstances2 extends EffInstances1:
  import Eff.Of

  /** `GenConcurrent` transfers by representation - `Ref`/`Deferred`/`memoize` over `F`. */
  given [F[_], E <: Throwable] => (C: GenConcurrent[F, Throwable]) => GenConcurrent[Of[F, E], Throwable] =
    C.asInstanceOf[GenConcurrent[Of[F, E], Throwable]] // scalafix:ok DisableSyntax.asInstanceOf

private[effect] trait EffInstances3 extends EffInstances2:
  import Eff.Of

  /** `GenTemporal` transfers by representation - `sleep`/`timeout` over `F`. */
  given [F[_], E <: Throwable] => (T: GenTemporal[F, Throwable]) => GenTemporal[Of[F, E], Throwable] =
    T.asInstanceOf[GenTemporal[Of[F, E], Throwable]] // scalafix:ok DisableSyntax.asInstanceOf

private[effect] trait EffInstances4 extends EffInstances3:
  import Eff.Of

  /** `Sync` transfers by representation - `delay`/`blocking`/`interruptible` over `F`. */
  given [F[_], E <: Throwable] => (S: Sync[F]) => Sync[Of[F, E]] =
    S.asInstanceOf[Sync[Of[F, E]]] // scalafix:ok DisableSyntax.asInstanceOf

private[effect] trait EffInstances5 extends EffInstances4:
  import Eff.Of

  /** `Async` - and by subtyping every cats-effect capability it extends - transfers by
    * representation. Since `Eff.Of[F, E][A] = F[A]`, `F`'s own instance IS the instance for `Eff`.
    */
  given [F[_], E <: Throwable] => (A: Async[F]) => Async[Of[F, E]] =
    A.asInstanceOf[Async[Of[F, E]]] // scalafix:ok DisableSyntax.asInstanceOf

/** Provides constructors, combinators, and type class instances for [[boilerplate.effect.Eff Eff]]. */
object Eff extends EffInstances5:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.Eff Eff]].
    * Structurally `F` - the phantom `E` erases.
    */
  type Of[F[_], E <: Throwable] = [A] =>> Eff[F, E, A]

  /** Views `F[A]` as `Eff` - identity at runtime, for crossing the opaque boundary within
    * boilerplate without allocation. The `F`-channel failures become defects relative to `E`.
    */
  private[boilerplate] inline def wrapUnsafe[F[_], E <: Throwable, A](fa: F[A]): Eff[F, E, A] = fa

  /** Inverse of [[wrapUnsafe]]. */
  private[boilerplate] inline def unwrapUnsafe[F[_], E <: Throwable, A](eff: Eff[F, E, A]): F[A] = eff

  /** Reifies the typed channel into an `Either`; a non-`E` defect propagates on `F`'s channel. */
  private def reify[F[_], E, A](fa: F[A])(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): F[Either[E, A]] =
    F.handleErrorWith(F.map(fa)(a => Right(a): Either[E, A])) {
      case tt(e) => F.pure(Left(e))
      case other => F.raiseError(other)
    }

  /** Returns a partially-applied constructor fixing the effect type `F`. */
  inline def apply[F[_]]: EffPartiallyApplied[F] = new EffPartiallyApplied[F]

  /** Partially-applied constructor enabling `Eff[F].succeed(a)` syntax. */
  final class EffPartiallyApplied[F[_]] @publicInBinary private[Eff]:
    /** Creates a successful computation. */
    inline def succeed[A](a: A)(using F: Applicative[F]): UEff[F, A] = F.pure(a)

    /** Creates a failed computation. */
    inline def fail[E <: Throwable](e: E)(using F: ApplicativeError[F, Throwable]): Eff[F, E, Nothing] =
      F.raiseError(e)

    /** Lifts a pure `Either` into the effect. */
    inline def from[E <: Throwable, A](either: Either[E, A])(using F: ApplicativeError[F, Throwable]): Eff[F, E, A] =
      either match
        case Right(a) => F.pure(a)
        case Left(e)  => F.raiseError(e)

    /** Embeds any `F[A]`, treating values as successes. Identity at runtime; O(0). */
    inline def liftF[A](fa: F[A]): UEff[F, A] = fa

    /** Canonical successful unit value. */
    inline def unit(using F: Applicative[F]): UEff[F, Unit] = F.pure(())

    /** Suspends a synchronous side effect as a success value; for typed errors use
      * [[Eff.delay delay]].
      */
    inline def suspend[A](thunk: => A)(using F: Sync[F]): UEff[F, A] = F.delay(thunk)
  end EffPartiallyApplied

  extension [F[_], E <: Throwable, A](self: Eff[F, E, A])
    /** Reifies to `F[Either[E, A]]`; a non-`E` defect propagates on `F`'s channel. */
    inline def either(using MonadThrow[F], TypeTest[Throwable, E]): F[Either[E, A]] =
      reify[F, E, A](self)

    /** Absorbs the typed error into `F`'s error channel. O(0) identity - the failure is already
      * there.
      */
    inline def absolve: F[A] = self

    /** Maps the success channel while preserving the error type. */
    inline def map[B](f: A => B)(using F: Functor[F]): Eff[F, E, B] = F.map(self)(f)

    /** Sequences computations, widening the error channel on demand. */
    inline def flatMap[E2 >: E <: Throwable, B](f: A => Eff[F, E2, B])(using F: Monad[F]): Eff[F, E2, B] =
      F.flatMap(self)(a => f(a))

    /** Maps the success value through an effectful function. */
    inline def semiflatMap[B](f: A => F[B])(using F: Monad[F]): Eff[F, E, B] =
      F.flatMap(self)(f)

    /** Flat-maps the success through a pure `Either`-returning function; a `Left` fails. */
    inline def subflatMap[E2 >: E <: Throwable, B](f: A => Either[E2, B])(using F: MonadThrow[F]): Eff[F, E2, B] =
      F.flatMap(self)(a =>
        f(a) match
          case Right(b) => F.pure(b)
          case Left(e)  => F.raiseError(e)
      )

    /** Transforms the entire reified `Either` structure. */
    inline def transform[E2 <: Throwable, B](
      f: Either[E, A] => Either[E2, B]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, B] =
      F.flatMap(reify[F, E, A](self))(ea =>
        f(ea) match
          case Right(b) => F.pure(b)
          case Left(e)  => F.raiseError(e)
      )

    /** Handles any failure by switching to an alternative computation. */
    inline def catchAll[E2 <: Throwable, B >: A](
      f: E => Eff[F, E2, B]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, B] =
      F.handleErrorWith(F.widen[A, B](self)) {
        case tt(e) => f(e)
        case other => F.raiseError(other)
      }

    /** Recovers the errors `pf` handles with an effect; unmatched errors pass through, widening to
      * `E2`. The effectful sibling of [[mapErrorPartial]], pairing with [[catchAll]].
      */
    inline def catchSome[E2 >: E <: Throwable, B >: A](
      pf: PartialFunction[E, Eff[F, E2, B]]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, B] =
      F.handleErrorWith(F.widen[A, B](self)) {
        case tt(e) if pf.isDefinedAt(e) => pf(e)
        case other                      => F.raiseError(other)
      }

    /** Recovers the `H` arm of a union error with an effect, narrowing the channel to the residual
      * `R` (where `E <: R | H`); unmatched errors stay typed as `R`, and `f` may itself fail into
      * `R`. The residual is inferred from the `E <:< (R | H)` witness - no annotation is needed.
      *
      * `H` must be runtime-testable; an erasure-ambiguous `H` is rejected at the call site.
      */
    inline def catchOnly[H, R <: Throwable, B >: A](f: H => Eff[F, R, B])(using
      ev: E <:< (R | H),
      tt: TypeTest[Throwable, H],
      F: MonadThrow[F]
    ): Eff[F, R, B] =
      val _ = ev
      F.handleErrorWith(F.widen[A, B](self)) {
        case tt(h) => f(h)
        case other => F.raiseError(other)
      }

    /** Handles both error and success with effectful functions, allowing error type change. */
    inline def redeemAll[E2 <: Throwable, B](
      fe: E => Eff[F, E2, B],
      fa: A => Eff[F, E2, B]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, B] =
      F.flatMap(reify[F, E, A](self)) {
        case Left(e)  => fe(e)
        case Right(a) => fa(a)
      }

    /** Folds over both channels, returning to the base effect. */
    inline def fold[B](fe: E => B, fa: A => B)(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): F[B] =
      F.map(reify[F, E, A](self))(_.fold(fe, fa))

    /** Effectfully folds both channels, allowing different continuations. */
    inline def foldF[B](fe: E => F[B], fa: A => F[B])(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): F[B] =
      F.flatMap(reify[F, E, A](self))(_.fold(fe, fa))

    /** Transforms the error channel. */
    inline def mapError[E2 <: Throwable](
      f: E => E2
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, A] =
      F.handleErrorWith(self) {
        case tt(e) => F.raiseError(f(e))
        case other => F.raiseError(other)
      }

    /** Transforms matched errors, passing unmatched errors through unchanged. */
    inline def mapErrorPartial[E2 >: E <: Throwable](
      pf: PartialFunction[E, E2]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, A] =
      F.handleErrorWith(self) {
        case tt(e) => F.raiseError(pf.applyOrElse(e, (x: E) => x))
        case other => F.raiseError(other)
      }

    /** Fallback to an alternative computation when this one fails with a typed error. */
    inline def alt[E2 <: Throwable, B >: A](
      that: => Eff[F, E2, B]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, B] =
      F.handleErrorWith(F.widen[A, B](self)) {
        case tt(_) => that
        case other => F.raiseError(other)
      }

    /** Recovers from any typed failure with a constant success value. */
    inline def orElseSucceed[B >: A](
      value: => B
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): UEff[F, B] =
      F.handleErrorWith(F.widen[A, B](self)) {
        case tt(_) => F.pure(value)
        case other => F.raiseError(other)
      }

    /** Replaces any typed failure with a different error. */
    inline def orElseFail[E2 <: Throwable](
      error: => E2
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E2, A] =
      F.handleErrorWith(self) {
        case tt(_) => F.raiseError(error)
        case other => F.raiseError(other)
      }

    /** Recovers from all typed errors by mapping them to a success value. */
    inline def valueOr(f: E => A)(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): UEff[F, A] =
      F.handleErrorWith(self) {
        case tt(e) => F.pure(f(e))
        case other => F.raiseError(other)
      }

    /** Observes typed failures without altering the result.
      *
      * The side effect is a raw `F[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    inline def tapError(f: E => F[Unit])(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E, A] =
      F.handleErrorWith(self) {
        case tt(e) => F.flatMap(f(e))(_ => F.raiseError(e))
        case other => F.raiseError(other)
      }

    /** Observes typed failures via an effectful action that can also fail.
      *
      * If the side effect fails, that failure propagates and replaces the original error. For
      * infallible side effects, use [[tapError]].
      */
    inline def flatTapError(
      f: E => Eff[F, E, Unit]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E, A] =
      F.handleErrorWith(self) {
        case tt(e) => F.flatMap(f(e))(_ => F.raiseError(e))
        case other => F.raiseError(other)
      }

    /** Observes success values without altering the result. */
    inline def tap(f: A => F[Unit])(using F: Monad[F]): Eff[F, E, A] =
      F.flatMap(self)(a => F.map(f(a))(_ => a))

    /** Observes the reified attempt result without altering the outcome. Defects propagate through
      * without observation.
      */
    inline def attemptTap(
      f: Either[E, A] => Eff[F, E, Unit]
    )(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): Eff[F, E, A] =
      F.flatMap(reify[F, E, A](self)) { ea =>
        F.flatMap(f(ea)) { _ =>
          ea match
            case Right(a) => F.pure(a)
            case Left(e)  => F.raiseError(e)
        }
      }

    /** Converts to an infallible effect returning `Option[A]`, treating typed errors as `None`. */
    inline def option(using F: MonadThrow[F], tt: TypeTest[Throwable, E]): UEff[F, Option[A]] =
      F.map(reify[F, E, A](self))(_.toOption)

    /** Extracts an inner `Option[B]` value, failing with `ifNone` when absent. */
    inline def collectSome[B](ifNone: => E)(using F: MonadThrow[F], ev: A <:< Option[B]): Eff[F, E, B] =
      F.flatMap(self)(a =>
        ev(a) match
          case Some(b) => F.pure(b)
          case None    => F.raiseError(ifNone)
      )

    /** Extracts an inner `Either[L, B]` value, mapping left to error via `ifLeft`. */
    inline def collectRight[L, B](ifLeft: L => E)(using F: MonadThrow[F], ev: A <:< Either[L, B]): Eff[F, E, B] =
      F.flatMap(self)(a =>
        ev(a) match
          case Right(b) => F.pure(b)
          case Left(l)  => F.raiseError(ifLeft(l))
      )

    /** Converts to `EitherT` for ecosystem interop. */
    inline def eitherT(using MonadThrow[F], TypeTest[Throwable, E]): EitherT[F, E, A] =
      EitherT(reify[F, E, A](self))

    // scalafix:off DisableSyntax.asInstanceOf
    /** Treats the error type as a subtype, for trusted casts. */
    transparent inline def assumeError[E2 <: E]: Eff[F, E2, A] = self.asInstanceOf[Eff[F, E2, A]]

    /** Treats the success channel as a subtype, for trusted casts. */
    transparent inline def assume[B <: A]: Eff[F, E, B] = self.asInstanceOf[Eff[F, E, B]]
    // scalafix:on

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    inline def *>[B](that: => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, B] =
      F.flatMap(self)(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    inline def <*[B](that: => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, A] =
      F.flatMap(self)(a => F.map(that)(_ => a))

    /** Sequences this computation with `that`, discarding the result of `this`. */
    inline def productR[B](that: => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, B] =
      F.flatMap(self)(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    inline def productL[B](that: => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, A] =
      F.flatMap(self)(a => F.map(that)(_ => a))

    /** Combines this computation with `that` into a tuple. */
    inline def product[B](that: Eff[F, E, B])(using F: Monad[F]): Eff[F, E, (A, B)] =
      F.flatMap(self)(a => F.map(that)(b => (a, b)))

    /** Applies an effectful function to the success value, discarding its result. */
    inline def flatTap[B](f: A => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, A] =
      F.flatMap(self)(a => F.map(f(a))(_ => a))

    /** Discards the success value, returning `Unit`. */
    inline def void(using F: Functor[F]): Eff[F, E, Unit] = F.map(self)(_ => ())

    /** Replaces the success value with `b`. */
    inline def as[B](b: B)(using F: Functor[F]): Eff[F, E, B] = F.map(self)(_ => b)

    /** Acquires a resource, uses it, and ensures release even on failure. */
    inline def bracket[B](use: A => Eff[F, E, B])(release: A => F[Unit])(using
      MC: MonadCancel[F, Throwable]
    ): Eff[F, E, B] =
      MC.bracket(self)(a => use(a))(release)

    /** Acquires a resource, uses it, and ensures release with outcome information. */
    inline def bracketCase[B](use: A => Eff[F, E, B])(
      release: (A, Outcome[Of[F, E], Throwable, B]) => F[Unit]
    )(using MC: MonadCancel[F, Throwable]): Eff[F, E, B] =
      MC.bracketCase(self)(a => use(a))((a, oc) => release(a, oc.asInstanceOf[Outcome[Of[F, E], Throwable, B]])) // scalafix:ok DisableSyntax.asInstanceOf

    /** Starts this computation as a fibre, returning immediately. A fibre completing with a typed
      * error is an `Outcome.Errored`.
      */
    inline def start(using S: GenSpawn[F, Throwable]): Eff[F, E, Fiber[Of[F, E], Throwable, A]] =
      S.map(S.start(self))(_.asInstanceOf[Fiber[Of[F, E], Throwable, A]]) // scalafix:ok DisableSyntax.asInstanceOf

    /** Runs this computation as a background fibre, cancelling it on scope exit. */
    inline def background(using
      S: GenSpawn[F, Throwable]
    ): Resource[F, F[Outcome[Of[F, E], Throwable, A]]] =
      S.background(self).asInstanceOf[Resource[F, F[Outcome[Of[F, E], Throwable, A]]]] // scalafix:ok DisableSyntax.asInstanceOf

    /** Races this computation against `that`, returning the winner's result. */
    inline def race[B](that: Eff[F, E, B])(using S: GenSpawn[F, Throwable]): Eff[F, E, Either[A, B]] =
      S.race(self, that)

    /** Runs this computation and `that` concurrently, returning both results. */
    inline def both[B](that: Eff[F, E, B])(using S: GenSpawn[F, Throwable]): Eff[F, E, (A, B)] =
      S.both(self, that)

    /** Runs this computation and `that` in parallel, discarding the result of `this`. */
    @targetName("parProductR")
    inline def &>[B](that: Eff[F, E, B])(using S: GenSpawn[F, Throwable]): Eff[F, E, B] =
      S.map(S.both(self, that))(_._2)

    /** Runs this computation and `that` in parallel, discarding the result of `that`. */
    @targetName("parProductL")
    inline def <&[B](that: Eff[F, E, B])(using S: GenSpawn[F, Throwable]): Eff[F, E, A] =
      S.map(S.both(self, that))(_._1)

    /** Registers a finaliser to run if this computation is cancelled. */
    inline def onCancel(fin: Eff[F, E, Unit])(using MC: MonadCancel[F, Throwable]): Eff[F, E, A] =
      MC.onCancel(self, MC.void(fin))

    /** Ensures `fin` runs after this computation regardless of outcome. */
    inline def guarantee(fin: Eff[F, E, Unit])(using MC: MonadCancel[F, Throwable]): Eff[F, E, A] =
      MC.guarantee(self, MC.void(fin))

    /** Ensures `fin` runs with the completion outcome after this computation. */
    inline def guaranteeCase(
      fin: Outcome[Of[F, E], Throwable, A] => Eff[F, E, Unit]
    )(using MC: MonadCancel[F, Throwable]): Eff[F, E, A] =
      MC.guaranteeCase(self)(oc => MC.void(fin(oc.asInstanceOf[Outcome[Of[F, E], Throwable, A]]))) // scalafix:ok DisableSyntax.asInstanceOf

    /** Delays execution of this computation by `duration`. */
    inline def delayBy(duration: FiniteDuration)(using T: GenTemporal[F, Throwable]): Eff[F, E, A] =
      T.productR(T.sleep(duration))(self)

    /** Executes this computation, then waits for `duration` before returning. */
    inline def andWait(duration: FiniteDuration)(using T: GenTemporal[F, Throwable]): Eff[F, E, A] =
      T.productL(self)(T.sleep(duration))

    /** Returns the result paired with the execution duration. */
    inline def timed(using T: GenTemporal[F, Throwable]): Eff[F, E, (FiniteDuration, A)] =
      T.timed(self)

    /** Fails with `onTimeout` if the computation does not complete within `duration`. */
    inline def timeout(duration: FiniteDuration, onTimeout: => E)(using
      T: GenTemporal[F, Throwable]
    ): Eff[F, E, A] =
      T.timeoutTo(self, duration, T.raiseError(onTimeout))

    /** Returns `fallback` if this computation does not complete within `duration`. */
    inline def timeoutTo[B >: A](duration: FiniteDuration, fallback: => Eff[F, E, B])(using
      T: GenTemporal[F, Throwable]
    ): Eff[F, E, B] =
      T.timeoutTo(T.widen[A, B](self), duration, fallback)
  end extension

  // Channel-observers on the infallible (`Nothing`) channel - the `Eff` counterpart of the
  // `EffIO[Nothing, A]` overloads. The typed error is uninhabited, but the general observers'
  // `TypeTest[Throwable, E]` widens `E` to `Throwable` here (the covariant receiver admits any `E`),
  // turning the test into the identity and capturing defects. These overloads pin `E = Nothing`;
  // each is degenerate and correct by construction - an error handler can never fire, so it is
  // dropped and the effect passes through `self` (defects included; `A` is invariant, so a widened
  // result goes through `F.widen`), while a success observer maps the value. No `TypeTest`, no
  // `reify`.
  extension [F[_], A](self: Eff[F, Nothing, A])
    /** The success reified as `Right`; a defect propagates. */
    inline def either(using F: Functor[F]): F[Either[Nothing, A]] = F.map(self)(Right(_))

    /** Applies `f` to the (always-`Right`) success; a `Left` result fails, a defect propagates. */
    inline def transform[E2 <: Throwable, B](f: Either[Nothing, A] => Either[E2, B])(using F: MonadThrow[F]): Eff[F, E2, B] =
      F.flatMap(self)(a =>
        f(Right(a)) match
          case Right(b) => F.pure(b)
          case Left(e)  => F.raiseError(e)
      )

    /** No typed error to catch; identity. */
    inline def catchAll[E2 <: Throwable, B >: A](@unused f: Nothing => Eff[F, E2, B])(using F: Functor[F]): Eff[F, E2, B] =
      F.widen[A, B](self)

    /** No typed error to catch; identity. */
    inline def catchSome[E2 <: Throwable, B >: A](@unused pf: PartialFunction[Nothing, Eff[F, E2, B]])(using F: Functor[F]): Eff[F, E2, B] =
      F.widen[A, B](self)

    /** No typed error to catch; identity. */
    inline def catchOnly[H, R <: Throwable, B >: A](@unused f: H => Eff[F, R, B])(using F: Functor[F]): Eff[F, R, B] =
      F.widen[A, B](self)

    /** No typed error; `fa` folds the success. */
    inline def redeemAll[E2 <: Throwable, B](@unused fe: Nothing => Eff[F, E2, B], fa: A => Eff[F, E2, B])(using
      F: Monad[F]): Eff[F, E2, B] =
      F.flatMap(self)(a => fa(a))

    /** No typed error; `fa` folds the success. */
    inline def fold[B](@unused fe: Nothing => B, fa: A => B)(using F: Functor[F]): F[B] = F.map(self)(fa)

    /** No typed error; `fa` folds the success. */
    inline def foldF[B](@unused fe: Nothing => F[B], fa: A => F[B])(using F: Monad[F]): F[B] = F.flatMap(self)(fa)

    /** No typed error to map; identity. */
    inline def mapError[E2 <: Throwable](@unused f: Nothing => E2): Eff[F, E2, A] = self

    /** No typed error to map; identity. */
    inline def mapErrorPartial[E2 <: Throwable](@unused pf: PartialFunction[Nothing, E2]): Eff[F, E2, A] = self

    /** Never fails typed; identity. */
    inline def alt[E2 <: Throwable, B >: A](@unused that: => Eff[F, E2, B])(using F: Functor[F]): Eff[F, E2, B] =
      F.widen[A, B](self)

    /** Never fails typed; identity. */
    inline def orElseSucceed[B >: A](@unused value: => B)(using F: Functor[F]): UEff[F, B] = F.widen[A, B](self)

    /** Never fails typed; identity. */
    inline def orElseFail[E2 <: Throwable](@unused error: => E2): Eff[F, E2, A] = self

    /** Never fails typed; identity. */
    inline def valueOr(@unused f: Nothing => A): UEff[F, A] = self

    /** No typed error to observe; identity. */
    inline def tapError(@unused f: Nothing => F[Unit]): Eff[F, Nothing, A] = self

    /** No typed error to observe; identity. */
    inline def flatTapError(@unused f: Nothing => Eff[F, Nothing, Unit]): Eff[F, Nothing, A] = self

    /** The attempt is always `Right`; `f` observes it, then the value passes through. */
    inline def attemptTap(f: Either[Nothing, A] => Eff[F, Nothing, Unit])(using F: Monad[F]): Eff[F, Nothing, A] =
      F.flatMap(self)(a => F.flatMap(f(Right(a)): F[Unit])(_ => F.pure(a)))

    /** The success wrapped as `Some`; a defect propagates. */
    inline def option(using F: Functor[F]): UEff[F, Option[A]] = F.map(self)(Some(_))

    /** The success reified as `Right`; a defect propagates. */
    inline def eitherT(using F: Functor[F]): EitherT[F, Nothing, A] = EitherT(F.map(self)(Right(_)))
  end extension

  /** Lifts a pure `Either` into the effect. */
  inline def from[F[_], E <: Throwable, A](either: Either[E, A])(using F: ApplicativeError[F, Throwable]): Eff[F, E, A] =
    either match
      case Right(a) => F.pure(a)
      case Left(e)  => F.raiseError(e)

  /** Converts an `Option`, supplying an error when empty. */
  inline def from[F[_], E <: Throwable, A](opt: Option[A], ifNone: => E)(using
    F: ApplicativeError[F, Throwable]
  ): Eff[F, E, A] =
    opt match
      case Some(a) => F.pure(a)
      case None    => F.raiseError(ifNone)

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  inline def from[F[_], E <: Throwable, A](result: Try[A], ifFailure: Throwable => E)(using
    F: ApplicativeError[F, Throwable]
  ): Eff[F, E, A] =
    result.fold(th => F.raiseError(ifFailure(th)), a => F.pure(a))

  /** Extracts the underlying computation from `EitherT`. */
  inline def from[F[_], E <: Throwable, A](et: EitherT[F, E, A])(using MonadThrow[F]): Eff[F, E, A] =
    lift(et.value)

  /** Absorbs an existing `F[Either[E, A]]` into the typed channel; a `Left` fails on `F`'s channel. */
  inline def lift[F[_], E <: Throwable, A](fea: F[Either[E, A]])(using F: MonadThrow[F]): Eff[F, E, A] =
    F.flatMap(fea) {
      case Right(a) => F.pure(a)
      case Left(e)  => F.raiseError(e)
    }

  /** Converts an `F[Option]`, supplying an error when empty. */
  inline def lift[F[_], E <: Throwable, A](fo: F[Option[A]], ifNone: => E)(using F: MonadThrow[F]): Eff[F, E, A] =
    F.flatMap(fo) {
      case Some(a) => F.pure(a)
      case None    => F.raiseError(ifNone)
    }

  /** Embeds any `F[A]`, treating values as successes. Identity at runtime; O(0). */
  inline def liftF[F[_], E <: Throwable, A](fa: F[A]): Eff[F, E, A] = fa

  /** Creates a successful computation. */
  inline def succeed[F[_], E <: Throwable, A](a: A)(using F: Applicative[F]): Eff[F, E, A] = F.pure(a)

  /** Creates a failed computation. */
  inline def fail[F[_], E <: Throwable, A](e: E)(using F: ApplicativeError[F, Throwable]): Eff[F, E, A] =
    F.raiseError(e)

  /** Canonical successful unit value. */
  inline def unit[F[_], E <: Throwable](using F: Applicative[F]): Eff[F, E, Unit] = F.pure(())

  /** Captures throwables raised in `F`, translating them via `ifFailure`. */
  inline def attempt[F[_], E <: Throwable, A](fa: F[A], ifFailure: Throwable => E)(using F: MonadThrow[F]): Eff[F, E, A] =
    F.handleErrorWith(fa)(t => F.raiseError(ifFailure(t)))

  /** Captures matching throwables as typed errors; unmatched throwables propagate as defects in
    * `F`'s error channel.
    */
  inline def attempt[F[_], E <: Throwable, A](fa: F[A])(pf: PartialFunction[Throwable, E])(using F: MonadThrow[F]): Eff[F, E, A] =
    F.handleErrorWith(fa)(t => if pf.isDefinedAt(t) then F.raiseError(pf(t)) else F.raiseError(t))

  /** Suspends evaluation until demanded. */
  inline def defer[F[_], E <: Throwable, A](thunk: => Eff[F, E, A])(using D: Defer[F]): Eff[F, E, A] =
    D.defer(thunk)

  /** Suspends a side effect that produces an `Either[E, A]`. */
  inline def delay[F[_], E <: Throwable, A](ea: => Either[E, A])(using F: Sync[F]): Eff[F, E, A] =
    lift(F.delay(ea))

  /** Suspends a synchronous side effect as a success value; for typed errors use [[delay]]. */
  inline def suspend[F[_], E <: Throwable, A](thunk: => A)(using F: Sync[F]): Eff[F, E, A] =
    F.delay(thunk)

  /** As [[delay]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def blocking[F[_], E <: Throwable, A](ea: => Either[E, A])(using F: Sync[F]): Eff[F, E, A] =
    lift(F.blocking(ea))

  /** As [[suspend]], on the blocking thread pool - for synchronous work that blocks a thread. */
  inline def suspendBlocking[F[_], E <: Throwable, A](thunk: => A)(using F: Sync[F]): Eff[F, E, A] =
    F.blocking(thunk)

  /** Suspends execution for the specified duration. */
  inline def sleep[F[_], E <: Throwable](duration: FiniteDuration)(using T: GenTemporal[F, Throwable]): Eff[F, E, Unit] =
    T.sleep(duration)

  /** Returns the current monotonic time as a `FiniteDuration`. */
  inline def monotonic[F[_], E <: Throwable](using C: Clock[F]): Eff[F, E, FiniteDuration] =
    C.monotonic

  /** Returns the current wall-clock time as a `FiniteDuration` since the epoch. */
  inline def realTime[F[_], E <: Throwable](using C: Clock[F]): Eff[F, E, FiniteDuration] =
    C.realTime

  /** Creates a new `Ref` initialised with `a`, operating in the `Eff` context. */
  inline def ref[F[_], E <: Throwable, A](a: A)(using C: GenConcurrent[F, Throwable]): Eff[F, E, Ref[Of[F, E], A]] =
    C.map(C.ref(a))(_.asInstanceOf[Ref[Of[F, E], A]]) // scalafix:ok DisableSyntax.asInstanceOf

  /** Creates an empty `Deferred` operating in the `Eff` context. */
  inline def deferred[F[_], E <: Throwable, A](using C: GenConcurrent[F, Throwable]): Eff[F, E, Deferred[Of[F, E], A]] =
    C.map(C.deferred[A])(_.asInstanceOf[Deferred[Of[F, E], A]]) // scalafix:ok DisableSyntax.asInstanceOf

  /** Introduces a self-cancellation point, immediately cancelling the current fibre. */
  inline def canceled[F[_], E <: Throwable](using S: GenSpawn[F, Throwable]): Eff[F, E, Unit] =
    S.canceled

  /** Introduces a cooperative yielding point. */
  inline def cede[F[_], E <: Throwable](using S: GenSpawn[F, Throwable]): Eff[F, E, Unit] =
    S.cede

  /** A computation that never completes. */
  inline def never[F[_], E <: Throwable, A](using S: GenSpawn[F, Throwable]): Eff[F, E, A] =
    S.never

  /** Converts a `Future` into an `Eff`, translating failures via `ifFailure`. */
  inline def fromFuture[F[_], E <: Throwable, A](future: F[Future[A]], ifFailure: Throwable => E)(using A: Async[F]): Eff[F, E, A] =
    A.handleErrorWith(A.fromFuture(future))(t => A.raiseError(ifFailure(t)))

  /** Converts a `Future` into an `Eff`, catching matching throwables as typed errors; unmatched
    * throwables propagate as defects in `F`'s error channel.
    */
  inline def fromFuture[F[_], E <: Throwable, A](future: F[Future[A]])(pf: PartialFunction[Throwable, E])(using A: Async[F]): Eff[F, E, A] =
    A.handleErrorWith(A.fromFuture(future))(t => if pf.isDefinedAt(t) then A.raiseError(pf(t)) else A.raiseError(t))

  /** Suspends an asynchronous callback-driven computation completing with a typed `Either[E, A]`.
    *
    * The callback is invoked with `Left(e)` for a typed error or `Right(a)` for success. A
    * throwable raised on `F`'s error channel surfaces as a defect; use [[asyncAttempt]] to fold it
    * into a typed error instead. The returned `F[Option[F[Unit]]]` optionally yields a finaliser
    * run on cancellation.
    */
  inline def async[F[_], E <: Throwable, A](k: (Either[E, A] => Unit) => F[Option[F[Unit]]])(using A: Async[F]): Eff[F, E, A] =
    A.async[A](cb => k(ea => cb(ea)))

  /** As [[async]], additionally folding a throwable raised while registering the callback into a
    * typed error via `ifDefect`. A typed error delivered through the callback (`Left(e)`) passes
    * through unchanged, and cancellation is never folded. Needs no `TypeTest`, so it works for an
    * abstract `E` - a registration-time failure is a defect by construction.
    */
  inline def asyncAttempt[F[_], E <: Throwable, A](ifDefect: Throwable => E)(k: (Either[E, A] => Unit) => F[Option[F[Unit]]])(using
    A: Async[F]
  ): Eff[F, E, A] =
    A.async[A](cb => A.handleErrorWith(k(ea => cb(ea)))(t => A.raiseError(ifDefect(t))))

  /** Executes `eff` only when `cond` is true, otherwise succeeds with `Unit`. */
  inline def when[F[_], E <: Throwable](cond: Boolean)(eff: => Eff[F, E, Unit])(using Applicative[F]): Eff[F, E, Unit] =
    if cond then eff else unit[F, E]

  /** Executes `eff` only when `cond` is false, otherwise succeeds with `Unit`. */
  inline def unless[F[_], E <: Throwable](cond: Boolean)(eff: => Eff[F, E, Unit])(using Applicative[F]): Eff[F, E, Unit] =
    if cond then unit[F, E] else eff

  /** Raises an error when `cond` is true, otherwise succeeds with `Unit`. */
  inline def raiseWhen[F[_], E <: Throwable](cond: Boolean)(e: => E)(using ApplicativeError[F, Throwable]): Eff[F, E, Unit] =
    if cond then fail(e) else unit[F, E]

  /** Raises an error when `cond` is false, otherwise succeeds with `Unit`. */
  inline def raiseUnless[F[_], E <: Throwable](cond: Boolean)(e: => E)(using ApplicativeError[F, Throwable]): Eff[F, E, Unit] =
    if cond then unit[F, E] else fail(e)

  /** Lifts a Boolean predicate into a typed-error effect. Both branches are evaluated lazily; the
    * unselected branch is never run.
    */
  inline def cond[F[_], E <: Throwable, A](pred: Boolean, ifTrue: => A, ifFalse: => E)(using ApplicativeError[F, Throwable]): Eff[F, E, A] =
    if pred then succeed(ifTrue) else fail(ifFalse)

  /** Traverses a collection, short-circuiting on first error. */
  inline def traverse[F[_], E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, List[B]] =
    // Prepend then reverse once: `:+` per element would be O(n^2) on `List`.
    F.map(as.foldLeft(succeed[F, E, List[B]](Nil)) { (acc, a) =>
      F.flatMap(acc)(bs => F.map(f(a))(b => b :: bs))
    })(_.reverse)

  /** Sequences a collection of effects, short-circuiting on first error. */
  inline def sequence[F[_], E <: Throwable, A](effs: Iterable[Eff[F, E, A]])(using Monad[F]): Eff[F, E, List[A]] =
    traverse(effs)(identity)

  /** Traverses a collection for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("traverseUnit")
  inline def traverse_[F[_], E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[F, E, B])(using F: Monad[F]): Eff[F, E, Unit] =
    as.foldLeft(unit[F, E])((acc, a) => F.flatMap(acc)(_ => F.void(f(a))))

  /** Runs a collection of effects for effect only, discarding results and short-circuiting on first
    * error.
    */
  @targetName("sequenceUnit")
  inline def sequence_[F[_], E <: Throwable, A](effs: Iterable[Eff[F, E, A]])(using Monad[F]): Eff[F, E, Unit] =
    traverse_(effs)(identity)

  /** Traverses a collection in parallel using `F`'s `Parallel` instance. */
  inline def parTraverse[F[_], E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[F, E, B])(using P: Parallel[F]): Eff[F, E, List[B]] =
    val parF = P.applicative
    val combined: P.F[List[B]] =
      as.toList.foldRight(parF.pure(List.empty[B])) { (a, acc) =>
        parF.map2(P.parallel(f(a)), acc)(_ :: _)
      }
    P.sequential(combined)

  /** Sequences a collection of effects in parallel. */
  inline def parSequence[F[_], E <: Throwable, A](effs: Iterable[Eff[F, E, A]])(using Parallel[F]): Eff[F, E, List[A]] =
    parTraverse(effs)(identity)

  /** Traverses a collection in parallel for effect only, discarding results. */
  @targetName("parTraverseUnit")
  inline def parTraverse_[F[_], E <: Throwable, A, B](as: Iterable[A])(f: A => Eff[F, E, B])(using P: Parallel[F]): Eff[F, E, Unit] =
    val parF = P.applicative
    val combined: P.F[Unit] =
      as.toList.foldRight(parF.pure(())) { (a, acc) =>
        parF.map2(P.parallel(f(a)), acc)((_, _) => ())
      }
    P.sequential(combined)

  /** Sequences a collection of effects in parallel for effect only, discarding results. */
  @targetName("parSequenceUnit")
  inline def parSequence_[F[_], E <: Throwable, A](effs: Iterable[Eff[F, E, A]])(using Parallel[F]): Eff[F, E, Unit] =
    parTraverse_(effs)(identity)

  /** Retries the effect up to `maxRetries` times on a typed failure; a defect propagates. */
  inline def retry[F[_], E <: Throwable, A](eff: Eff[F, E, A], maxRetries: Int)(using
    F: MonadThrow[F],
    tt: TypeTest[Throwable, E]): Eff[F, E, A] =
    if maxRetries <= 0 then eff
    else
      // Not `eff.catchAll`: `E` is abstract here, so the general/`Nothing` observer overloads are
      // ambiguous - inline the typed-vs-defect split directly on `F`.
      F.handleErrorWith(eff) {
        case tt(_) => retry(eff, maxRetries - 1)
        case other => F.raiseError(other)
      }

  /** Retries the effect with exponential backoff, capping each delay at `maxDelay`. */
  inline def retryWithBackoff[F[_], E <: Throwable, A](
    eff: Eff[F, E, A],
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Option[FiniteDuration]
  )(using T: GenTemporal[F, Throwable], tt: TypeTest[Throwable, E]): Eff[F, E, A] =
    def loop(remaining: Int, delay: FiniteDuration): Eff[F, E, A] =
      if remaining <= 0 then eff
      else
        T.handleErrorWith(eff) {
          case tt(_) =>
            val cappedDelay = maxDelay.fold(delay)(d => delay.min(d))
            T.productR(T.sleep(cappedDelay))(loop(remaining - 1, delay * 2))
          case other => T.raiseError(other)
        }
    loop(maxRetries, initialDelay)
  end retryWithBackoff

  /** The identity natural transformation lifting `F[A]` into `Eff[F, E, A]` (treating values as
    * successes). The canonical way to `mapK` `Resource[F, A]` and other primitives into `Eff`.
    */
  inline def functionK[F[_], E <: Throwable]: F ~> Of[F, E] = new IdK[F, E]

  private[effect] class IdK[F[_], E <: Throwable] @publicInBinary private[Eff] () extends FunctionK[F, Of[F, E]]:
    def apply[A](fa: F[A]): Eff[F, E, A] = wrapUnsafe(fa)

  /** Error-widening natural transformation. Identity at runtime - `Eff` is covariant in `E`.
    *
    * Still required in invariant positions: `Resource`, `Stream`, and `Pipe` cannot widen their
    * effect parameter by subtyping.
    */
  inline def widenK[F[_], E1 <: Throwable, E2 >: E1 <: Throwable]: Of[F, E1] ~> Of[F, E2] =
    new WidenK[F, E1, E2]

  private[effect] class WidenK[F[_], E1 <: Throwable, E2 >: E1 <: Throwable] @publicInBinary private[Eff] ()
      extends FunctionK[Of[F, E1], Of[F, E2]]:
    def apply[A](fa: Eff[F, E1, A]): Eff[F, E2, A] = fa

  // scalafix:off DisableSyntax.asInstanceOf
  /** Transforms a `Resource[F, A]` to `Resource[Eff.Of[F, E], A]`. O(0) - `Of[F, E]` is `F`. */
  inline def liftResource[F[_], E <: Throwable, A](resource: Resource[F, A]): Resource[Of[F, E], A] =
    resource.asInstanceOf[Resource[Of[F, E], A]]

  /** Transforms a `Ref[F, A]` to `Ref[Eff.Of[F, E], A]`. */
  inline def liftRef[F[_], E <: Throwable, A](ref: Ref[F, A]): Ref[Of[F, E], A] =
    ref.asInstanceOf[Ref[Of[F, E], A]]

  /** Transforms a `Deferred[F, A]` to `Deferred[Eff.Of[F, E], A]`. */
  inline def liftDeferred[F[_], E <: Throwable, A](deferred: Deferred[F, A]): Deferred[Of[F, E], A] =
    deferred.asInstanceOf[Deferred[Of[F, E], A]]

  /** Transforms a `Queue[F, A]` to `Queue[Eff.Of[F, E], A]`. */
  inline def liftQueue[F[_], E <: Throwable, A](queue: Queue[F, A]): Queue[Of[F, E], A] =
    queue.asInstanceOf[Queue[Of[F, E], A]]

  /** Transforms a `Semaphore[F]` to `Semaphore[Eff.Of[F, E]]`. */
  inline def liftSemaphore[F[_], E <: Throwable](semaphore: Semaphore[F]): Semaphore[Of[F, E]] =
    semaphore.asInstanceOf[Semaphore[Of[F, E]]]

  /** Transforms a `CountDownLatch[F]` to `CountDownLatch[Eff.Of[F, E]]`. */
  inline def liftLatch[F[_], E <: Throwable](latch: CountDownLatch[F]): CountDownLatch[Of[F, E]] =
    latch.asInstanceOf[CountDownLatch[Of[F, E]]]

  /** Transforms a `CyclicBarrier[F]` to `CyclicBarrier[Eff.Of[F, E]]`. */
  inline def liftBarrier[F[_], E <: Throwable](barrier: CyclicBarrier[F]): CyclicBarrier[Of[F, E]] =
    barrier.asInstanceOf[CyclicBarrier[Of[F, E]]]

  /** Transforms an `AtomicCell[F, A]` to `AtomicCell[Eff.Of[F, E], A]`. */
  inline def liftCell[F[_], E <: Throwable, A](cell: AtomicCell[F, A]): AtomicCell[Of[F, E], A] =
    cell.asInstanceOf[AtomicCell[Of[F, E], A]]

  /** Transforms a `Supervisor[F]` to `Supervisor[Eff.Of[F, E]]`. */
  inline def liftSupervisor[F[_], E <: Throwable](supervisor: Supervisor[F]): Supervisor[Of[F, E]] =
    supervisor.asInstanceOf[Supervisor[Of[F, E]]]

  /** Inherits `Functor` from the base effect. */
  given [F[_], E <: Throwable] => (F: Functor[F]) => Functor[Of[F, E]] =
    F.asInstanceOf[Functor[Of[F, E]]]

  /** `Monad` mirroring the base effect; a typed failure short-circuits on `F`'s channel. */
  given [F[_], E <: Throwable] => (F: Monad[F]) => Monad[Of[F, E]] =
    F.asInstanceOf[Monad[Of[F, E]]]

  /** `Parallel` enabling `parMapN`/`parTraverse`, short-circuiting on the first error. */
  given [F[_], E <: Throwable] => (P: Parallel[F]) => Parallel[Of[F, E]] =
    P.asInstanceOf[Parallel[Of[F, E]]]

  /** Defers evaluation until demanded. */
  given [F[_], E <: Throwable] => (D: Defer[F]) => Defer[Of[F, E]] =
    D.asInstanceOf[Defer[Of[F, E]]]

  /** `Clock` delegating to the underlying `Clock[F]`. */
  given [F[_], E <: Throwable] => (C: Clock[F]) => Clock[Of[F, E]] =
    C.asInstanceOf[Clock[Of[F, E]]]

  /** `Unique` delegating to the underlying `Unique[F]`. */
  given [F[_], E <: Throwable] => (U: Unique[F]) => Unique[Of[F, E]] =
    U.asInstanceOf[Unique[Of[F, E]]]

  /** `Show` delegating to the underlying `Show[F[A]]`. */
  given [F[_], E <: Throwable, A] => (S: Show[F[A]]) => Show[Eff[F, E, A]] =
    S.asInstanceOf[Show[Eff[F, E, A]]]

  /** `Eq` delegating to the underlying `Eq[F[A]]`. */
  given [F[_], E <: Throwable, A] => (EQ: Eq[F[A]]) => Eq[Eff[F, E, A]] =
    EQ.asInstanceOf[Eq[Eff[F, E, A]]]

  /** `PartialOrder` delegating to the underlying `PartialOrder[F[A]]`. */
  given [F[_], E <: Throwable, A] => (PO: PartialOrder[F[A]]) => PartialOrder[Eff[F, E, A]] =
    PO.asInstanceOf[PartialOrder[Eff[F, E, A]]]
  // scalafix:on

  /** Canonical `MonadError` for the typed error channel `E`. Higher priority than the
    * `Async`-derived `MonadError[_, Throwable]`; its `handleErrorWith` respects the phantom via
    * `TypeTest`.
    */
  given [F[_], E <: Throwable] => (F0: MonadThrow[F], tt: TypeTest[Throwable, E]) => MonadError[Of[F, E], E]:
    def pure[A](a: A): Eff[F, E, A] = F0.pure(a)
    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      F0.flatMap(fa)(a => f(a))
    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      F0.tailRecM(a)(x => f(x))
    def raiseError[A](e: E): Eff[F, E, A] = F0.raiseError(e)
    def handleErrorWith[A](fa: Eff[F, E, A])(f: E => Eff[F, E, A]): Eff[F, E, A] =
      F0.handleErrorWith(fa) {
        case tt(e) => f(e)
        case other => F0.raiseError(other)
      }
  end given

  /** Choice semantics: `combineK` falls back to the second computation on typed error (`alt`). */
  given [F[_], E <: Throwable] => (F0: MonadThrow[F], tt: TypeTest[Throwable, E]) => SemigroupK[Of[F, E]]:
    def combineK[A](x: Eff[F, E, A], y: Eff[F, E, A]): Eff[F, E, A] =
      F0.handleErrorWith(x) {
        case tt(_) => y
        case other => F0.raiseError(other)
      }

  /** Combines two successful computations using `Semigroup` on their values; a failure
    * short-circuits.
    */
  given [F[_], E <: Throwable, A] => (F0: Monad[F], S: Semigroup[A]) => Semigroup[Eff[F, E, A]]:
    def combine(x: Eff[F, E, A], y: Eff[F, E, A]): Eff[F, E, A] =
      F0.flatMap(x)(a => F0.map(y)(b => S.combine(a, b)))

  /** Combines computations with an identity element from `Monoid`. */
  given [F[_], E <: Throwable, A] => (F0: Monad[F], M: Monoid[A]) => Monoid[Eff[F, E, A]]:
    def empty: Eff[F, E, A] = F0.pure(M.empty)
    def combine(x: Eff[F, E, A], y: Eff[F, E, A]): Eff[F, E, A] =
      F0.flatMap(x)(a => F0.map(y)(b => M.combine(a, b)))
end Eff
