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

import cats.Applicative
import cats.Defer
import cats.Functor
import cats.Monad
import cats.MonadError
import cats.data.EitherT
import cats.data.Kleisli
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Poll
import cats.syntax.all.*

/** Reader-style wrapper represented as `R => Eff[F, E, A]`.
  *
  * Equivalent to `Kleisli[Eff.Of[F, E], R, A]` but with ZIO-style naming. Refer to
  * [[boilerplate.effect.EffR$ EffR]]'s companion for constructors and syntax.
  */
opaque type EffR[F[_], R, E, A] = R => Eff[F, E, A]

/** Infallible reader effect with `Nothing` error channel. */
type UEffR[F[_], R, A] = EffR[F, R, Nothing, A]

/** Reader effect with `Throwable` error channel. */
type TEffR[F[_], R, A] = EffR[F, R, Throwable, A]

/** Lifts services, manages environments, and exposes type class instances for
  * [[boilerplate.effect.EffR EffR]].
  */
object EffR:
  /** Higher-kinded alias for working with `EffR` in type class derivations. */
  type Of[F[_], R, E] = [A] =>> EffR[F, R, E, A]

  /** Partially-applied constructor pinning effect and environment. Use via `EffR[IO, Config]` for
    * ergonomic value creation.
    */
  def apply[F[_], R]: EffRPartiallyApplied[F, R] = EffRPartiallyApplied[F, R]()

  /** Builder providing convenient constructors with effect and environment fixed. Refer to
    * [[boilerplate.effect.EffR$ EffR]] for full API.
    */
  final class EffRPartiallyApplied[F[_], R] private[EffR] ():
    /** Creates a successful computation ignoring the environment. */
    inline def succeed[E, A](a: A)(using Applicative[F]): EffR[F, R, E, A] =
      EffR.succeed[F, R, E, A](a)

    /** Creates a failed computation ignoring the environment. */
    inline def fail[E, A](e: E)(using Applicative[F]): EffR[F, R, E, A] =
      EffR.fail[F, R, E, A](e)

    /** Lifts a pure `Either` into the reader layer. */
    inline def from[E, A](either: Either[E, A])(using Applicative[F]): EffR[F, R, E, A] =
      EffR.from[F, R, E, A](either)

    /** Lifts an `Eff` by discarding the environment. */
    inline def lift[E, A](eff: Eff[F, E, A]): EffR[F, R, E, A] =
      EffR.lift[F, R, E, A](eff)

    /** Retrieves the environment as a value. */
    inline def service[E](using Applicative[F]): EffR[F, R, E, R] =
      EffR.service[F, R, E]

    /** Canonical successful unit value. */
    inline def unit[E](using Applicative[F]): EffR[F, R, E, Unit] =
      EffR.unit[F, R, E]
  end EffRPartiallyApplied

  /** Wraps an environment function without allocation. */
  inline def wrap[F[_], R, E, A](run: R => Eff[F, E, A]): EffR[F, R, E, A] = run

  /** Captures an implicit `R` using context functions. */
  inline def fromContext[F[_], R, E, A](run: R ?=> Eff[F, E, A]): EffR[F, R, E, A] =
    (r: R) => run(using r)

  /** Discards the environment when it is unnecessary. */
  inline def lift[F[_], R, E, A](eff: Eff[F, E, A]): EffR[F, R, E, A] =
    (_: R) => eff

  /** Lifts a pure `Either` into the reader layer. */
  inline def from[F[_]: Applicative, R, E, A](either: Either[E, A]): EffR[F, R, E, A] =
    (_: R) => Eff.from(either)

  /** Wraps an existing `F[Either]` result without recomputation. */
  @targetName("liftFEither")
  inline def lift[F[_], R, E, A](fea: F[Either[E, A]]): EffR[F, R, E, A] =
    (_: R) => Eff.lift(fea)

  /** Converts an `Option`, supplying an error when empty. */
  inline def from[F[_]: Applicative, R, E, A](opt: Option[A], ifNone: => E): EffR[F, R, E, A] =
    (_: R) => Eff.from(opt, ifNone)

  /** Converts `F[Option]`, supplying an error when empty. */
  inline def lift[F[_]: Functor, R, E, A](fo: F[Option[A]], ifNone: => E): EffR[F, R, E, A] =
    (_: R) => Eff.lift(fo, ifNone)

  /** Converts `Try`, mapping throwables into the domain-specific error. */
  inline def from[F[_]: Applicative, R, E, A](result: Try[A], ifFailure: Throwable => E): EffR[F, R, E, A] =
    (_: R) => Eff.from(result, ifFailure)

  /** Extracts the computation from an `EitherT`. */
  inline def from[F[_], R, E, A](et: EitherT[F, E, A]): EffR[F, R, E, A] =
    (_: R) => Eff.from(et)

  /** Successful computation that ignores the environment. */
  inline def succeed[F[_]: Applicative, R, E, A](a: A): EffR[F, R, E, A] =
    (_: R) => Eff.succeed[F, E, A](a)

  /** Failed computation that ignores the environment. */
  inline def fail[F[_]: Applicative, R, E, A](e: E): EffR[F, R, E, A] =
    (_: R) => Eff.fail[F, E, A](e)

  /** Canonical successful unit value. */
  inline def unit[F[_]: Applicative, R, E]: EffR[F, R, E, Unit] =
    (_: R) => Eff.unit[F, E]

  /** Captures throwables raised in `F`, translating them via `ifFailure`. */
  inline def attempt[F[_], R, E, A](fa: F[A], ifFailure: Throwable => E)(using ME: MonadError[F, Throwable]): EffR[F, R, E, A] =
    (_: R) => Eff.attempt(fa, ifFailure)

  /** Retrieves the current environment as a value. Alias: [[ask]]. */
  inline def service[F[_]: Applicative, R, E]: EffR[F, R, E, R] =
    (r: R) => Eff.succeed[F, E, R](r)

  /** Retrieves the current environment as a value. Alias for [[service]]. */
  inline def ask[F[_]: Applicative, R, E]: EffR[F, R, E, R] =
    service[F, R, E]

  /** Suspends evaluation until demanded. */
  inline def defer[F[_]: Defer, R, E, A](thunk: => EffR[F, R, E, A]): EffR[F, R, E, A] =
    (r: R) => Eff.defer(thunk(r))

  extension [F[_], R, E, A](self: EffR[F, R, E, A])
    /** Supplies an environment and yields the underlying `Eff`. */
    inline def run(env: R): Eff[F, E, A] = self(env)

    /** Maps the success channel whilst threading the environment. */
    inline def map[B](f: A => B)(using Functor[F]): EffR[F, R, E, B] =
      (r: R) => self(r).map(f)

    /** Sequences environment-dependent computations. */
    inline def flatMap[B](f: A => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, B] =
      (r: R) => self(r).flatMap(a => f(a).run(r))

    /** Supplies a concrete environment immediately. */
    inline def provide(env: R): Eff[F, E, A] = run(env)

    /** Supplies an environment built effectfully from another layer. */
    inline def provide[R0](layer: EffR[F, R0, E, R])(using Monad[F]): EffR[F, R0, E, A] =
      (r0: R0) => layer(r0).flatMap(r => self(r))

    /** Contramaps the environment. Kleisli's `local`. */
    inline def contramap[R0](f: R0 => R): EffR[F, R0, E, A] =
      (r0: R0) => self(f(r0))

    /** Converts to `Kleisli` for Cats integrations. */
    inline def kleisli: Kleisli[Eff.Of[F, E], R, A] =
      Kleisli(run)

    /** Widens the error channel without recomputation. */
    transparent inline def widenError[E2 >: E]: EffR[F, R, E2, A] =
      (r: R) => self(r).widenError[E2]

    /** Narrows the error type when statically safe. */
    transparent inline def assumeError[E2 <: E]: EffR[F, R, E2, A] =
      (r: R) => self(r).assumeError[E2]

    /** Widens the success channel covariantly. */
    transparent inline def widen[B >: A]: EffR[F, R, E, B] =
      (r: R) => self(r).widen[B]

    /** Narrows the success channel when warranted. */
    transparent inline def assume[B <: A]: EffR[F, R, E, B] =
      (r: R) => self(r).assume[B]

    /** Marks the environment as contravariantly smaller. */
    transparent inline def widenEnv[R0 <: R]: EffR[F, R0, E, A] =
      (r0: R0) => self(r0)

    // scalafix:off DisableSyntax.asInstanceOf
    /** Treats the environment as a supertype for consumers needing more context. */
    transparent inline def assumeEnv[R0 >: R]: EffR[F, R0, E, A] =
      (r0: R0) => self(r0.asInstanceOf[R])
    // scalafix:on

    /** Composes with another reader that consumes output as environment. */
    inline def andThen[B](next: EffR[F, A, E, B])(using Monad[F]): EffR[F, R, E, B] =
      (r: R) => self(r).flatMap(a => next.run(a))

    /** Maps success value through an effectful function. */
    inline def semiflatMap[B](f: A => F[B])(using Monad[F]): EffR[F, R, E, B] =
      (r: R) => self(r).semiflatMap(f)

    /** Chains a pure `Either`-returning function over success. */
    inline def subflatMap[E2 >: E, B](f: A => Either[E2, B])(using Functor[F]): EffR[F, R, E2, B] =
      (r: R) => self(r).subflatMap(f)

    /** Applies a function to the underlying `Either`. */
    inline def transform[E2, B](f: Either[E, A] => Either[E2, B])(using Functor[F]): EffR[F, R, E2, B] =
      (r: R) => self(r).transform(f)

    /** Fails with `onFailure` if `predicate` is false on success. */
    inline def ensure(onFailure: => E)(predicate: A => Boolean)(using Functor[F]): EffR[F, R, E, A] =
      (r: R) => self(r).ensure(onFailure)(predicate)

    /** Fails with error computed from value if `predicate` is false on success. */
    inline def ensureOr(onFailure: A => E)(predicate: A => Boolean)(using Functor[F]): EffR[F, R, E, A] =
      (r: R) => self(r).ensureOr(onFailure)(predicate)

    // --- Composition Operators ---

    /** Sequences this computation with `that`, discarding the result of `this`. */
    @targetName("productR")
    inline def *>[B](that: => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, B] =
      (r: R) => self(r) *> that.run(r)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    @targetName("productL")
    inline def <*[B](that: => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r) <* that.run(r)

    /** Sequences this computation with `that`, discarding the result of `this`. Named alternative
      * to `*>`.
      */
    inline def productR[B](that: => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, B] =
      self *> that

    /** Sequences this computation with `that`, discarding the result of `that`. Named alternative
      * to `<*`.
      */
    inline def productL[B](that: => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, A] =
      self <* that

    /** Discards the success value, returning `Unit`. */
    inline def void(using Functor[F]): EffR[F, R, E, Unit] =
      (r: R) => self(r).void

    /** Replaces the success value with `b`. */
    inline def as[B](b: B)(using Functor[F]): EffR[F, R, E, B] =
      (r: R) => self(r).as(b)

    /** Applies an effectful function to the success value, discarding its result. */
    inline def flatTap[B](f: A => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r).flatTap(a => f(a).run(r))

    /** Combines this computation with `that` into a tuple. */
    inline def product[B](that: EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, (A, B)] =
      (r: R) => self(r).product(that.run(r))

    // --- Error Recovery Operators ---

    /** Recovers from all errors by mapping them to a success value. */
    inline def recover(f: E => A)(using Functor[F]): UEffR[F, R, A] =
      (r: R) => self(r).recover(f)

    /** Handles any failure by switching to an alternative computation. */
    inline def catchAll[E2, B >: A](f: E => EffR[F, R, E2, B])(using Monad[F]): EffR[F, R, E2, B] =
      (r: R) => self(r).catchAll(e => f(e).run(r))

    /** Recovers from certain errors by mapping them to a success value. */
    inline def recover[A1 >: A](pf: PartialFunction[E, A1])(using Functor[F]): EffR[F, R, E, A1] =
      (r: R) => self(r).recover(pf)

    /** Recovers from certain errors by switching to a new computation. */
    inline def recoverWith[E2 >: E](pf: PartialFunction[E, EffR[F, R, E2, A]])(using Monad[F]): EffR[F, R, E2, A] =
      (r: R) =>
        Eff.lift(
          Monad[F].flatMap(self(r).either) {
            case Left(e) if pf.isDefinedAt(e) => pf(e).run(r).either
            case Left(e)                      => Monad[F].pure(Left(e))
            case Right(a)                     => Monad[F].pure(Right(a))
          }
        )

    /** Executes an effect when a matching error occurs, then re-raises the error.
      *
      * Aligns with cats' `onError` semantics using `PartialFunction`.
      */
    inline def onError(pf: PartialFunction[E, EffR[F, R, E, Unit]])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r).onError { case e if pf.isDefinedAt(e) => pf(e).run(r) }

    /** Transforms certain errors using `pf` and re-raises them. */
    inline def adaptError(pf: PartialFunction[E, E])(using Functor[F]): EffR[F, R, E, A] =
      (r: R) => self(r).adaptError(pf)

    /** Fallback to an alternative computation when this one fails. */
    inline def alt[E2, B >: A](that: => EffR[F, R, E2, B])(using Monad[F]): EffR[F, R, E2, B] =
      (r: R) =>
        Eff.lift(
          Monad[F].flatMap(self(r).either) {
            case Left(_)  => that.run(r).either
            case Right(a) => Monad[F].pure(Right(a))
          }
        )

    // --- Conversion Utilities ---

    /** Unwraps to the underlying `R => F[Either[E, A]]`. */
    inline def either: R => F[Either[E, A]] =
      (r: R) => self(r).either

    /** Re-throws the error into `F` when `E <:< Throwable`. */
    inline def rethrow(using ME: MonadError[F, Throwable], ev: E <:< Throwable): R => F[A] =
      (r: R) => self(r).rethrow

    /** Absorbs an error into `F` when `E` matches the error type of `F`. */
    inline def absolve[EE](using ME: MonadError[F, EE], ev: E <:< EE): R => F[A] =
      (r: R) => self(r).absolve

    /** Folds over both channels, returning to the base effect. */
    inline def fold[B](fe: E => B, fa: A => B)(using Functor[F]): R => F[B] =
      (r: R) => self(r).fold(fe, fa)

    /** Effectfully folds both channels, allowing different continuations. */
    inline def foldF[B](fe: E => F[B], fa: A => F[B])(using Monad[F]): R => F[B] =
      (r: R) => self(r).foldF(fe, fa)

    /** Maps both error and success channels simultaneously. */
    inline def bimap[E2, B](fe: E => E2, fb: A => B)(using Functor[F]): EffR[F, R, E2, B] =
      (r: R) => self(r).bimap(fe, fb)

    /** Transforms the error channel, akin to `leftMap`. */
    inline def mapError[E2](f: E => E2)(using Functor[F]): EffR[F, R, E2, A] =
      (r: R) => self(r).mapError(f)

    /** Handles both error and success with pure functions, always succeeding. */
    inline def redeem[B](fe: E => B, fa: A => B)(using Functor[F]): UEffR[F, R, B] =
      (r: R) => self(r).redeem(fe, fa)

    /** Handles both error and success with effectful functions, allowing error type change.
      *
      * Unlike cats' `redeemWith` which preserves the error type, this combinator allows
      * transitioning to a new error type `E2` via both handlers.
      */
    inline def redeemAll[E2, B](fe: E => EffR[F, R, E2, B], fa: A => EffR[F, R, E2, B])(using Monad[F]): EffR[F, R, E2, B] =
      (r: R) =>
        Eff.lift(
          Monad[F].flatMap(self(r).either) {
            case Left(e)  => fe(e).run(r).either
            case Right(a) => fa(a).run(r).either
          }
        )

    /** Observes failures in the underlying effect without altering the result. */
    inline def tapError(f: E => F[Unit])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r).tapError(f)
  end extension

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]
  private type Kle[F[_], R, E] = [A] =>> Kleisli[Base[F, E], R, A]

  private inline def toK[F[_], R, E, A](er: EffR[F, R, E, A]): Kleisli[Base[F, E], R, A] =
    Kleisli(er.run)

  private inline def fromK[F[_], R, E, A](k: Kleisli[Base[F, E], R, A]): EffR[F, R, E, A] =
    (r: R) => k.run(r)

  /** Derives a `Monad` instance via `Kleisli` for environment-aware programs. */
  given [F[_], R, E](using Monad[Base[F, E]]): Monad[Of[F, R, E]] with
    private val delegate = summon[Monad[Kle[F, R, E]]]

    def pure[A](a: A): EffR[F, R, E, A] = fromK(delegate.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      fromK(delegate.flatMap(toK(fa))(a => toK(f(a))))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      fromK(delegate.tailRecM(a)(a0 => toK(f(a0))))

  /** Provides `MonadError` by delegating to the `Kleisli` instance. */
  given [F[_], R, E](using ME: MonadError[Base[F, E], E]): MonadError[Of[F, R, E], E] with
    private val delegate = summon[MonadError[Kle[F, R, E], E]]

    def pure[A](a: A): EffR[F, R, E, A] = fromK(delegate.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      fromK(delegate.flatMap(toK(fa))(a => toK(f(a))))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      fromK(delegate.tailRecM(a)(a0 => toK(f(a0))))

    def raiseError[A](e: E): EffR[F, R, E, A] = fromK(delegate.raiseError(e))

    def handleErrorWith[A](fa: EffR[F, R, E, A])(f: E => EffR[F, R, E, A]): EffR[F, R, E, A] =
      fromK(delegate.handleErrorWith(toK(fa))(e => toK(f(e))))
  end given

  /** Extends cancellation support from `Eff` into the reader layer. */
  given [F[_], R, E, EE](using MC: MonadCancel[Base[F, E], EE]): MonadCancel[Of[F, R, E], EE] with
    private val delegate =
      MonadCancel.monadCancelForKleisli[Base[F, E], R, EE]

    def rootCancelScope = delegate.rootCancelScope

    def pure[A](a: A): EffR[F, R, E, A] = fromK(delegate.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      fromK(delegate.flatMap(toK(fa))(a => toK(f(a))))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      fromK(delegate.tailRecM(a)(a0 => toK(f(a0))))

    def raiseError[A](e: EE): EffR[F, R, E, A] = fromK(delegate.raiseError(e))

    def handleErrorWith[A](fa: EffR[F, R, E, A])(f: EE => EffR[F, R, E, A]): EffR[F, R, E, A] =
      fromK(delegate.handleErrorWith(toK(fa))(e => toK(f(e))))

    def canceled: EffR[F, R, E, Unit] = fromK(delegate.canceled)

    def onCancel[A](fa: EffR[F, R, E, A], fin: EffR[F, R, E, Unit]): EffR[F, R, E, A] =
      fromK(delegate.onCancel(toK(fa), toK(fin)))

    def forceR[A, B](fa: EffR[F, R, E, A])(fb: EffR[F, R, E, B]): EffR[F, R, E, B] =
      fromK(delegate.forceR(toK(fa))(toK(fb)))

    def uncancelable[A](body: Poll[Of[F, R, E]] => EffR[F, R, E, A]): EffR[F, R, E, A] =
      fromK(delegate.uncancelable { poll =>
        val lifted = new Poll[Of[F, R, E]]:
          def apply[B](er: EffR[F, R, E, B]): EffR[F, R, E, B] =
            fromK(poll(toK(er)))
        toK(body(lifted))
      })

    override def guaranteeCase[A](fa: EffR[F, R, E, A])(fin: Outcome[Of[F, R, E], EE, A] => EffR[F, R, E, Unit]): EffR[F, R, E, A] =
      fromK(delegate.guaranteeCase(toK(fa)) { out =>
        val liftedOutcome: Outcome[Of[F, R, E], EE, A] = out match
          case Outcome.Succeeded(success) =>
            Outcome.succeeded[Of[F, R, E], EE, A](fromK(success))
          case Outcome.Errored(err) =>
            Outcome.errored[Of[F, R, E], EE, A](err)
          case Outcome.Canceled() =>
            Outcome.canceled[Of[F, R, E], EE, A]
        toK(fin(liftedOutcome))
      })
  end given
end EffR
