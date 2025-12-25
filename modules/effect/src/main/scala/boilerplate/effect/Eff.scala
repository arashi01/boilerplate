/*
 * Copyright (c) 2025 Boilerplate contributors.
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
import scala.util.Try

import cats.*
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.effect.kernel.Deferred
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Poll
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.syntax.all.*

/** Zero-cost typed error channel abstraction represented as `F[Either[E, A]]`. Refer to
  * [[boilerplate.effect.Eff$ Eff]] for constructors and utilities.
  */
opaque type Eff[F[_], E, A] = F[Either[E, A]]

/** Infallible effect: `Eff` with `Nothing` as the error type. */
type UEff[F[_], A] = Eff[F, Nothing, A]

/** Throwable-errored effect: `Eff` with `Throwable` as the error type. */
type TEff[F[_], A] = Eff[F, Throwable, A]

/** Provides constructors, combinators, and type class instances for [[boilerplate.effect.Eff Eff]]. */
object Eff:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.Eff Eff]]. */
  type Of[F[_], E] = [A] =>> Eff[F, E, A]

  /** Wraps a pre-existing `F[Either[E, A]]` without allocation. */
  inline def apply[F[_], E, A](fa: F[Either[E, A]]): Eff[F, E, A] = fa

  /** Returns a partially-applied constructor fixing the effect type `F`. */
  def apply[F[_]]: EffPartiallyApplied[F] = new EffPartiallyApplied[F]

  /** Partially-applied constructor enabling `Eff[F].succeed(a)` syntax. */
  final class EffPartiallyApplied[F[_]] private[Eff] ():
    /** Creates a successful computation. */
    inline def succeed[A](a: A)(using F: Applicative[F]): UEff[F, A] =
      F.pure(Right(a))

    /** Creates a failed computation. */
    inline def fail[E](e: E)(using F: Applicative[F]): Eff[F, E, Nothing] =
      F.pure(Left(e))

    /** Lifts a pure `Either` into the effect. */
    inline def from[E, A](either: Either[E, A])(using F: Applicative[F]): Eff[F, E, A] =
      F.pure(either)

    /** Embeds any `F[A]`, treating values as successes. */
    inline def liftF[A](fa: F[A])(using F: Functor[F]): UEff[F, A] =
      F.map(fa)(Right(_))

    /** Canonical successful unit value. */
    inline def unit(using F: Applicative[F]): UEff[F, Unit] =
      F.pure(Right(()))
  end EffPartiallyApplied

  extension [F[_], E, A](self: Eff[F, E, A])
    /** Unwraps to the underlying `F[Either[E, A]]`. */
    inline def either: F[Either[E, A]] = self

    /** Maps the success channel while preserving the error type. */
    inline def map[B](f: A => B)(using Functor[F]): Eff[F, E, B] =
      Functor[F].map(self)(_.map(f))

    /** Transforms the error channel, akin to `leftMap`. */
    inline def mapError[E2](f: E => E2)(using Functor[F]): Eff[F, E2, A] =
      Functor[F].map(self)(_.leftMap(f))

    /** Maps both error and success channels simultaneously. */
    inline def bimap[E2, B](fe: E => E2, fb: A => B)(using Functor[F]): Eff[F, E2, B] =
      Functor[F].map(self)(_.bimap(fe, fb))

    /** Sequences computations, widening the error channel on demand. */
    inline def flatMap[E2 >: E, B](f: A => Eff[F, E2, B])(using Monad[F]): Eff[F, E2, B] =
      Monad[F].flatMap(self) {
        case Right(a) => f(a)
        case Left(e)  => Monad[F].pure(Left(e))
      }

    /** Handles any failure by switching to an alternative computation. */
    inline def catchAll[E2, B >: A](f: E => Eff[F, E2, B])(using Monad[F]): Eff[F, E2, B] =
      Monad[F].flatMap(self) {
        case Left(e)  => f(e)
        case Right(a) => Monad[F].pure(Right(a))
      }

    /** Folds over both channels, returning to the base effect. */
    inline def fold[B](fe: E => B, fa: A => B)(using Functor[F]): F[B] =
      Functor[F].map(self)(_.fold(fe, fa))

    /** Effectfully folds both channels, allowing different continuations. */
    inline def foldF[B](fe: E => F[B], fa: A => F[B])(using Monad[F]): F[B] =
      Monad[F].flatMap(self)(_.fold(fe, fa))

    /** Handles both error and success with pure functions, always succeeding. */
    inline def redeem[B](fe: E => B, fa: A => B)(using Functor[F]): UEff[F, B] =
      Functor[F].map(self)(_.fold(fe, fa).asRight)

    /** Handles both error and success with effectful functions, allowing error type change.
      *
      * Unlike cats' `redeemWith` which preserves the error type, this combinator allows
      * transitioning to a new error type `E2` via both handlers.
      */
    inline def redeemAll[E2, B](fe: E => Eff[F, E2, B], fa: A => Eff[F, E2, B])(using Monad[F]): Eff[F, E2, B] =
      Monad[F].flatMap(self) {
        case Left(e)  => fe(e)
        case Right(a) => fa(a)
      }

    /** Observes failures without altering the result. */
    inline def tapError(f: E => F[Unit])(using Monad[F]): Eff[F, E, A] =
      Monad[F].flatMap(self) {
        case Left(e)  => Monad[F].flatMap(f(e))(_ => Monad[F].pure(Left(e)))
        case Right(a) => Monad[F].pure(Right(a))
      }

    /** Fallback to an alternative computation when this one fails. */
    inline def alt[E2, B >: A](that: => Eff[F, E2, B])(using Monad[F]): Eff[F, E2, B] =
      Monad[F].flatMap(self) {
        case Left(_)  => that
        case Right(a) => Monad[F].pure(Right(a))
      }

    /** Maps the success value through an effectful function. */
    inline def semiflatMap[B](f: A => F[B])(using Monad[F]): Eff[F, E, B] =
      Monad[F].flatMap(self) {
        case Right(a) => Monad[F].map(f(a))(Right(_))
        case Left(e)  => Monad[F].pure(Left(e))
      }

    /** Flat-maps the success through a pure `Either`-returning function. */
    inline def subflatMap[E2 >: E, B](f: A => Either[E2, B])(using Functor[F]): Eff[F, E2, B] =
      Functor[F].map(self)(_.flatMap(f))

    /** Transforms the entire `Either` structure. */
    inline def transform[E2, B](f: Either[E, A] => Either[E2, B])(using Functor[F]): Eff[F, E2, B] =
      Functor[F].map(self)(f)

    /** Fails with `onFailure` if the predicate is not satisfied. */
    inline def ensure[E2 >: E](onFailure: => E2)(p: A => Boolean)(using Functor[F]): Eff[F, E2, A] =
      Functor[F].map(self) {
        case r @ Right(a) => if p(a) then r else Left(onFailure)
        case l            => l
      }

    /** Fails with `onFailure(a)` if the predicate is not satisfied. */
    inline def ensureOr[E2 >: E](onFailure: A => E2)(p: A => Boolean)(using Functor[F]): Eff[F, E2, A] =
      Functor[F].map(self) {
        case r @ Right(a) => if p(a) then r else Left(onFailure(a))
        case l            => l
      }

    /** Converts to `EitherT` for ecosystem interop. */
    inline def eitherT: EitherT[F, E, A] = EitherT(self)

    // --- Composition Operators ---

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    inline def *>[B](that: => Eff[F, E, B])(using Monad[F]): Eff[F, E, B] =
      self.flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    inline def <*[B](that: => Eff[F, E, B])(using Monad[F]): Eff[F, E, A] =
      self.flatMap(a => that.map(_ => a))

    /** Sequences this computation with `that`, discarding the result of `this`. Named alternative
      * to `*>`.
      */
    inline def productR[B](that: => Eff[F, E, B])(using Monad[F]): Eff[F, E, B] =
      self *> that

    /** Sequences this computation with `that`, discarding the result of `that`. Named alternative
      * to `<*`.
      */
    inline def productL[B](that: => Eff[F, E, B])(using Monad[F]): Eff[F, E, A] =
      self <* that

    /** Discards the success value, returning `Unit`. */
    inline def void(using Functor[F]): Eff[F, E, Unit] =
      self.map(_ => ())

    /** Replaces the success value with `b`. */
    inline def as[B](b: B)(using Functor[F]): Eff[F, E, B] =
      self.map(_ => b)

    /** Applies an effectful function to the success value, discarding its result. */
    inline def flatTap[B](f: A => Eff[F, E, B])(using Monad[F]): Eff[F, E, A] =
      self.flatMap(a => f(a).map(_ => a))

    /** Combines this computation with `that` into a tuple. */
    inline def product[B](that: Eff[F, E, B])(using Monad[F]): Eff[F, E, (A, B)] =
      self.flatMap(a => that.map(b => (a, b)))

    // --- Error Recovery Operators ---

    /** Recovers from all errors by mapping them to a success value. */
    inline def recover(f: E => A)(using Functor[F]): UEff[F, A] =
      Functor[F].map(self) {
        case Left(e)  => Right(f(e))
        case Right(a) => Right(a)
      }

    /** Recovers from certain errors by mapping them to a success value. */
    inline def recover[A1 >: A](pf: PartialFunction[E, A1])(using Functor[F]): Eff[F, E, A1] =
      Functor[F].map(self) {
        case Left(e) if pf.isDefinedAt(e) => Right(pf(e))
        case Left(e)                      => Left(e)
        case Right(a)                     => Right(a)
      }

    /** Recovers from certain errors by switching to a new computation. */
    inline def recoverWith[E2 >: E](pf: PartialFunction[E, Eff[F, E2, A]])(using Monad[F]): Eff[F, E2, A] =
      Monad[F].flatMap(self) {
        case Left(e) if pf.isDefinedAt(e) => pf(e)
        case Left(e)                      => Monad[F].pure(Left(e))
        case Right(a)                     => Monad[F].pure(Right(a))
      }

    /** Executes an effect when a matching error occurs, then re-raises the error.
      *
      * Aligns with cats' `onError` semantics using `PartialFunction`.
      */
    inline def onError(pf: PartialFunction[E, Eff[F, E, Unit]])(using Monad[F]): Eff[F, E, A] =
      Monad[F].flatMap(self) {
        case Left(e) if pf.isDefinedAt(e) => pf(e).flatMap(_ => Monad[F].pure(Left(e)))
        case Left(e)                      => Monad[F].pure(Left(e))
        case Right(a)                     => Monad[F].pure(Right(a))
      }

    /** Transforms certain errors using `pf` and re-raises them. */
    inline def adaptError(pf: PartialFunction[E, E])(using Functor[F]): Eff[F, E, A] =
      Functor[F].map(self) {
        case Left(e) if pf.isDefinedAt(e) => Left(pf(e))
        case other                        => other
      }

    // --- Conversion Utilities ---

    /** Re-throws the error into `F` when `E <:< Throwable`. */
    inline def rethrow(using ME: MonadError[F, Throwable], ev: E <:< Throwable): F[A] =
      Monad[F].flatMap(self) {
        case Left(e)  => ME.raiseError(ev(e))
        case Right(a) => Monad[F].pure(a)
      }

    /** Absorbs an error into `F` when `E` matches the error type of `F`. */
    inline def absolve[EE](using ME: MonadError[F, EE], ev: E <:< EE): F[A] =
      Monad[F].flatMap(self) {
        case Left(e)  => ME.raiseError(ev(e))
        case Right(a) => Monad[F].pure(a)
      }

    // scalafix:off DisableSyntax.asInstanceOf
    /** Widens only the error type without allocating. */
    transparent inline def widenError[E2 >: E]: Eff[F, E2, A] =
      Eff(self.either.asInstanceOf[F[Either[E2, A]]])

    /** Treats the error type as a subtype, for trusted casts. */
    transparent inline def assumeError[E2 <: E]: Eff[F, E2, A] =
      Eff(self.either.asInstanceOf[F[Either[E2, A]]])

    /** Widens the success channel when the consumer expects a supertype. */
    transparent inline def widen[B >: A]: Eff[F, E, B] =
      Eff(self.either.asInstanceOf[F[Either[E, B]]])

    /** Treats the success channel as a subtype, for trusted casts. */
    transparent inline def assume[B <: A]: Eff[F, E, B] =
      Eff(self.either.asInstanceOf[F[Either[E, B]]])
    // scalafix:on
  end extension

  /** Lifts a pure `Either` into the effect via `pure`. */
  inline def from[F[_]: Applicative, E, A](either: Either[E, A]): Eff[F, E, A] =
    Applicative[F].pure(either)

  /** Wraps an existing `F[Either[E, A]]` without recomputation. */
  inline def lift[F[_], E, A](fea: F[Either[E, A]]): Eff[F, E, A] = fea

  /** Converts an `Option`, supplying an error when empty. */
  inline def from[F[_]: Applicative, E, A](opt: Option[A], ifNone: => E): Eff[F, E, A] =
    Applicative[F].pure(opt.toRight(ifNone))

  /** Converts an `F[Option]`, supplying an error when empty. */
  inline def lift[F[_]: Functor, E, A](fo: F[Option[A]], ifNone: => E): Eff[F, E, A] =
    Functor[F].map(fo)(_.toRight(ifNone))

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  inline def from[F[_]: Applicative, E, A](result: Try[A], ifFailure: Throwable => E): Eff[F, E, A] =
    result.fold(th => fail(ifFailure(th)), succeed(_))

  /** Extracts the underlying computation from `EitherT`. */
  inline def from[F[_], E, A](et: EitherT[F, E, A]): Eff[F, E, A] = et.value

  /** Creates a successful computation. */
  inline def succeed[F[_]: Applicative, E, A](a: A): Eff[F, E, A] =
    Applicative[F].pure(Right(a))

  /** Creates a failed computation. */
  inline def fail[F[_]: Applicative, E, A](e: E): Eff[F, E, A] =
    Applicative[F].pure(Left(e))

  /** Embeds any `F[A]`, treating values as successes. */
  inline def liftF[F[_]: Functor, E, A](fa: F[A]): Eff[F, E, A] =
    Functor[F].map(fa)(Right(_))

  /** Canonical successful unit value. */
  inline def unit[F[_]: Applicative, E]: Eff[F, E, Unit] = succeed(())

  /** Captures throwables raised in `F`, translating them via `ifFailure`. */
  inline def attempt[F[_], E, A](fa: F[A], ifFailure: Throwable => E)(using ME: MonadError[F, Throwable]): Eff[F, E, A] =
    lift(ME.map(ME.attempt(fa))(_.leftMap(ifFailure)))

  /** Suspends evaluation until demanded. */
  inline def defer[F[_]: Defer, E, A](thunk: => Eff[F, E, A]): Eff[F, E, A] =
    Defer[F].defer(thunk)

  // --- Natural Transformation ----------------------------------------------

  /** Creates a `FunctionK` that lifts `F[A]` into `Eff[F, E, A]` treating values as successes.
    *
    * This is the canonical way to transform `Resource[F, A]` and other cats-effect primitives to
    * work with `Eff`.
    */
  def functionK[F[_]: Functor, E]: F ~> Of[F, E] =
    new FunctionK[F, Of[F, E]]:
      def apply[A](fa: F[A]): Eff[F, E, A] = liftF(fa)

  // --- Cats-Effect Primitive Lifts -----------------------------------------

  /** Transforms a `Resource[F, A]` to `Resource[Eff.Of[F, E], A]`. */
  inline def lift[F[_], E, A](resource: Resource[F, A])(using F: MonadCancel[F, Throwable]): Resource[Of[F, E], A] =
    resource.mapK(functionK[F, E])(using F, given_MonadCancel_Of_EE[F, E, Throwable])

  /** Transforms a `Ref[F, A]` to `Ref[Eff.Of[F, E], A]`. */
  inline def lift[F[_]: Functor, E, A](ref: Ref[F, A]): Ref[Of[F, E], A] =
    ref.mapK(functionK[F, E])

  /** Transforms a `Deferred[F, A]` to `Deferred[Eff.Of[F, E], A]`. */
  inline def lift[F[_]: Functor, E, A](deferred: Deferred[F, A]): Deferred[Of[F, E], A] =
    deferred.mapK(functionK[F, E])

  /** Transforms a `Queue[F, A]` to `Queue[Eff.Of[F, E], A]`. */
  inline def lift[F[_]: Functor, E, A](queue: Queue[F, A]): Queue[Of[F, E], A] =
    queue.mapK(functionK[F, E])

  /** Transforms a `Semaphore[F]` to `Semaphore[Eff.Of[F, E]]`. */
  inline def lift[F[_], E](semaphore: Semaphore[F])(using F: MonadCancel[F, Throwable]): Semaphore[Of[F, E]] =
    semaphore.mapK(functionK[F, E])(using given_MonadCancel_Of_EE[F, E, Throwable])

  // --- Typeclass instances -------------------------------------------------

  /** Inherits `Functor` from the base effect, lifting over the error channel. */
  given [F[_]: Functor, E]: Functor[Of[F, E]] with
    def map[A, B](fa: Eff[F, E, A])(f: A => B): Eff[F, E, B] = fa.map(f)

  /** `Monad` instance mirroring the `Either` structure with typed errors. */
  given [F[_]: Monad, E]: Monad[Of[F, E]] with
    def pure[A](a: A): Eff[F, E, A] = succeed(a)

    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      Monad[F].tailRecM(a) { current =>
        Functor[F].map(f(current)) {
          case Left(e)            => Right(Left(e))
          case Right(Left(next))  => Left(next)
          case Right(Right(done)) => Right(Right(done))
        }
      }
  end given

  /** Canonical `MonadError` for the typed error channel. */
  given [F[_]: Monad, E]: MonadError[Of[F, E], E] with
    def pure[A](a: A): Eff[F, E, A] = succeed(a)

    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      summon[Monad[Of[F, E]]].tailRecM(a)(f)

    def raiseError[A](e: E): Eff[F, E, A] = fail(e)

    def handleErrorWith[A](fa: Eff[F, E, A])(f: E => Eff[F, E, A]): Eff[F, E, A] =
      fa.catchAll(f)
  end given

  /** Lifts a `MonadError` from `F` itself, propagating external failures. */
  given [F[_], E, EE](using F0: MonadError[F, EE]): MonadError[Of[F, E], EE] with
    def pure[A](a: A): Eff[F, E, A] = succeed(a)

    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      summon[Monad[Of[F, E]]].tailRecM(a)(f)

    def raiseError[A](e: EE): Eff[F, E, A] =
      F0.raiseError[Either[E, A]](e)

    def handleErrorWith[A](fa: Eff[F, E, A])(f: EE => Eff[F, E, A]): Eff[F, E, A] =
      F0.handleErrorWith(fa)(f)
  end given

  /** Delegates cancellation semantics from `F` whilst retaining typed errors. */
  given [F[_], E0, EE](using MC: MonadCancel[F, EE]): MonadCancel[Of[F, E0], EE] with
    def rootCancelScope = MC.rootCancelScope

    def pure[A](a: A): Eff[F, E0, A] = Eff.succeed(a)

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      summon[Monad[Of[F, E0]]].tailRecM(a)(f)

    def raiseError[A](e: EE): Eff[F, E0, A] =
      MC.raiseError[Either[E0, A]](e)

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: EE => Eff[F, E0, A]): Eff[F, E0, A] =
      MC.handleErrorWith(fa)(f)

    def canceled: Eff[F, E0, Unit] = liftF(MC.canceled)

    private inline def discard(fin: Eff[F, E0, Unit]): F[Unit] =
      MC.map(fin)(_ => ())

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      MC.onCancel(fa, discard(fin))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      MC.forceR(fa)(fb)

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      MC.uncancelable { pollF =>
        val lifted = new Poll[Of[F, E0]]:
          def apply[B](eff: Eff[F, E0, B]): Eff[F, E0, B] = pollF(eff)
        body(lifted)
      }

    override def guaranteeCase[A](fa: Eff[F, E0, A])(fin: Outcome[Of[F, E0], EE, A] => Eff[F, E0, Unit]): Eff[F, E0, A] =
      MC.guaranteeCase(fa) {
        case Outcome.Succeeded(success) =>
          val lifted = Outcome.succeeded[Of[F, E0], EE, A](success)
          fin(lifted).either.void
        case Outcome.Errored(err) =>
          val lifted = Outcome.errored[Of[F, E0], EE, A](err)
          MC.map(fin(lifted).either)(_ => ())
        case Outcome.Canceled() =>
          fin(Outcome.canceled[Of[F, E0], EE, A]).either.void
      }
  end given
end Eff
