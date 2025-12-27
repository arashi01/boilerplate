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

import scala.annotation.publicInBinary
import scala.annotation.targetName
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import cats.Applicative
import cats.Defer
import cats.Functor
import cats.Monad
import cats.MonadError
import cats.Parallel
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.data.Kleisli
import cats.effect.kernel.GenTemporal
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Poll
import cats.effect.kernel.Sync
import cats.syntax.all.*
import cats.~>

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
    inline def ask[E](using Applicative[F]): EffR[F, R, E, R] =
      EffR.ask[F, R, E]

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

  /** Converts a `Kleisli[Eff.Of[F, E], R, A]` to `EffR`. */
  inline def from[F[_], R, E, A](k: Kleisli[Eff.Of[F, E], R, A]): EffR[F, R, E, A] =
    (r: R) => k.run(r)

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

  /** Retrieves the current environment as a value. */
  inline def ask[F[_]: Applicative, R, E]: EffR[F, R, E, R] =
    (r: R) => Eff.succeed[F, E, R](r)

  /** Suspends evaluation until demanded. */
  inline def defer[F[_]: Defer, R, E, A](thunk: => EffR[F, R, E, A]): EffR[F, R, E, A] =
    (r: R) => Eff.defer(thunk(r))

  /** Suspends a side effect that produces an `Either[E, A]`, ignoring the environment.
    *
    * Use this for synchronous side-effecting code that returns typed errors:
    * {{{
    * EffR.delay[IO, Config, MyError, Int](nativeCall.register())
    * }}}
    */
  inline def delay[F[_], R, E, A](ea: => Either[E, A])(using F: Sync[F]): EffR[F, R, E, A] =
    (_: R) => Eff.delay(ea)

  extension [F[_], R, E, A](self: EffR[F, R, E, A])
    /** Supplies an environment and yields the underlying `Eff`. */
    inline def run(env: R): Eff[F, E, A] = self(env)

    /** Maps the success channel whilst threading the environment. */
    inline def map[B](f: A => B)(using Functor[F]): EffR[F, R, E, B] =
      (r: R) => self(r).map(f)

    /** Sequences environment-dependent computations. */
    inline def flatMap[B](f: A => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, B] =
      (r: R) => self(r).flatMap(a => f(a).run(r))

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

    /** Sequences this computation with `that`, discarding the result of `this`. */
    inline def productR[B](that: => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, B] =
      (r: R) => self(r).productR(that.run(r))

    /** Sequences this computation with `that`, discarding the result of `that`. */
    inline def productL[B](that: => EffR[F, R, E, B])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r).productL(that.run(r))

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

    /** Recovers from all errors by mapping them to a success value.
      *
      * Similar to `getOrElse` for `Option` or `Validated.valueOr`. Named `valueOr` to avoid
      * collision with cats' `recover` which takes `PartialFunction`.
      */
    inline def valueOr(f: E => A)(using Functor[F]): UEffR[F, R, A] =
      (r: R) => self(r).valueOr(f)

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
      * Named `redeemAll` to distinguish from cats' `redeemWith` which preserves error type. This
      * combinator allows transitioning to a new error type `E2` via both handlers.
      */
    inline def redeemAll[E2, B](fe: E => EffR[F, R, E2, B], fa: A => EffR[F, R, E2, B])(using Monad[F]): EffR[F, R, E2, B] =
      (r: R) =>
        Eff.lift(
          Monad[F].flatMap(self(r).either) {
            case Left(e)  => fe(e).run(r).either
            case Right(a) => fa(a).run(r).either
          }
        )

    /** Observes failures in the underlying effect without altering the result.
      *
      * The side effect is a raw `F[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    inline def tapError(f: E => F[Unit])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r).tapError(f)

    /** Observes success values via an effectful function.
      *
      * Unlike `flatTap`, this uses a plain `F[Unit]` effect and does not participate in the error
      * channel. Useful for logging or metrics.
      */
    inline def tap(f: A => F[Unit])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) => self(r).tap(f)

    /** Observes failures using an `EffR` side effect, propagating side effect failure.
      *
      * If the original computation fails with `e` and the side effect `f(e)` also fails, the
      * resulting error is from the side effect. For infallible side effects, use [[tapError]].
      */
    inline def flatTapError(f: E => EffR[F, R, E, Unit])(using Monad[F]): EffR[F, R, E, A] =
      (r: R) =>
        Eff.lift(
          Monad[F].flatMap(self(r).either) {
            case Left(e)  => Monad[F].map(f(e).run(r).either)(_.fold(_ => Left(e), _ => Left(e)))
            case Right(a) => Monad[F].pure(Right(a))
          }
        )

    /** Recovers from any failure by returning the provided success value. */
    inline def orElseSucceed[B >: A](fallback: => B)(using Functor[F]): UEffR[F, R, B] =
      (r: R) => self(r).orElseSucceed(fallback)

    /** Recovers from any failure by failing with a different error. */
    inline def orElseFail[E2](newError: => E2)(using Functor[F]): EffR[F, R, E2, A] =
      (r: R) => self(r).orElseFail(newError)

    /** Discards the error channel, returning `None` for failures and `Some(a)` for success.
      *
      * This is useful when you want to handle failures by absence rather than by error values,
      * similar to converting `Either` to `Option`.
      */
    inline def option(using Functor[F]): UEffR[F, R, Option[A]] =
      (r: R) => self(r).option

    /** Extracts the inner value from `Option[B]` success, failing with `ifNone` if empty.
      *
      * Useful for sequencing optional results where `None` indicates an expected failure.
      */
    inline def collectSome[B](ifNone: => E)(using F: Functor[F])(using ev: A <:< Option[B]): EffR[F, R, E, B] =
      (r: R) => self(r).collectSome(ifNone)

    /** Extracts the `Right` value from `Either[L, B]` success, failing with `ifLeft` if `Left`.
      *
      * Useful for integrating with APIs that return `Either` for validation.
      */
    inline def collectRight[L, B](ifLeft: L => E)(using F: Functor[F])(using ev: A <:< Either[L, B]): EffR[F, R, E, B] =
      (r: R) => self(r).collectRight(ifLeft)

    /** Ensures resource cleanup regardless of outcome (success, failure, or cancellation).
      *
      * The `release` function receives the acquired resource and is guaranteed to run even if `use`
      * fails or is canceled. This is the reader-aware variant of `bracket`.
      *
      * @param use The computation that uses the acquired resource.
      * @param release The cleanup function, executed unconditionally.
      * @return A computation that safely manages the resource lifecycle.
      */
    inline def bracket[B](use: A => EffR[F, R, E, B])(release: A => F[Unit])(using MC: MonadCancel[F, Throwable]): EffR[F, R, E, B] =
      (r: R) => self(r).bracket(a => use(a).run(r))(release)

    /** Variant of `bracket` that provides the outcome to the release function.
      *
      * The `release` function receives both the acquired resource and the outcome of the `use`
      * computation, enabling conditional cleanup logic.
      *
      * @param use The computation that uses the acquired resource.
      * @param release The cleanup function, receiving resource and outcome.
      * @return A computation that safely manages the resource lifecycle.
      */
    inline def bracketCase[B](use: A => EffR[F, R, E, B])(release: (A, Outcome[F, Throwable, Either[E, B]]) => F[Unit])(using
      MC: MonadCancel[F, Throwable]
    ): EffR[F, R, E, B] =
      (r: R) => self(r).bracketCase(a => use(a).run(r))(release)

    /** Fails with `onTimeout` if this computation does not complete within `duration`.
      *
      * Uses `GenTemporal` from cats-effect for time-based operations. On timeout, the original
      * computation is canceled and the error value is returned.
      *
      * @param duration Maximum time to wait for completion.
      * @param onTimeout Error value to return on timeout.
      * @return The original result or the timeout error.
      */
    inline def timeout(duration: FiniteDuration, onTimeout: => E)(using GT: GenTemporal[F, Throwable]): EffR[F, R, E, A] =
      (r: R) => self(r).timeout(duration, onTimeout)
  end extension

  /** Creates a natural transformation from `EffR.Of[F, R, E]` to any `G[_]`.
    *
    * This is useful for interoperating with APIs that require `FunctionK`, such as `Resource.mapK`
    * or http4s' `HttpRoutes.translate`.
    *
    * @param env The environment to provide to each `EffR` computation.
    * @param f The transformation applied to the resulting `Eff[F, E, *]` values.
    * @return A `FunctionK` suitable for natural transformation pipelines.
    */
  inline def functionK[F[_], R, E, G[_]](env: R)(f: FunctionK[Eff.Of[F, E], G]): FunctionK[Of[F, R, E], G] =
    new FunctionKImpl(env, f)

  private[effect] class FunctionKImpl[F[_], R, E, G[_]] @publicInBinary() (
    env: R,
    f: FunctionK[Eff.Of[F, E], G]
  ) extends FunctionK[Of[F, R, E], G]:
    def apply[A](fa: EffR[F, R, E, A]): G[A] = f(fa.run(env))

  // ---------------------------------------------------------------------------
  // Typeclass Instances
  // ---------------------------------------------------------------------------
  // These are implemented directly on EffR without Kleisli delegation to maintain
  // zero-allocation semantics. The pattern `(r: R) => ...` is inlined at compile time.

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Derives a `Monad` instance directly for environment-aware programs. */
  given given_Monad_Of[F[_], R, E](using M: Monad[Base[F, E]]): Monad[Of[F, R, E]] with
    def pure[A](a: A): EffR[F, R, E, A] =
      (_: R) => M.pure(a)

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      (r: R) => M.flatMap(fa(r))(a => f(a)(r))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      (r: R) => M.tailRecM(a)(a0 => f(a0)(r))

  /** Provides `MonadError` directly for the typed error channel. */
  given given_MonadError_Of[F[_], R, E](using ME: MonadError[Base[F, E], E]): MonadError[Of[F, R, E], E] with
    def pure[A](a: A): EffR[F, R, E, A] =
      (_: R) => ME.pure(a)

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      (r: R) => ME.flatMap(fa(r))(a => f(a)(r))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      (r: R) => ME.tailRecM(a)(a0 => f(a0)(r))

    def raiseError[A](e: E): EffR[F, R, E, A] =
      (_: R) => ME.raiseError(e)

    def handleErrorWith[A](fa: EffR[F, R, E, A])(f: E => EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) => ME.handleErrorWith(fa(r))(e => f(e)(r))
  end given_MonadError_Of

  /** Extends cancellation support from `Eff` into the reader layer. */
  given given_MonadCancel_Of[F[_], R, E, EE](using MC: MonadCancel[Base[F, E], EE]): MonadCancel[Of[F, R, E], EE] with
    def rootCancelScope = MC.rootCancelScope

    def pure[A](a: A): EffR[F, R, E, A] =
      (_: R) => MC.pure(a)

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      (r: R) => MC.flatMap(fa(r))(a => f(a)(r))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      (r: R) => MC.tailRecM(a)(a0 => f(a0)(r))

    def raiseError[A](e: EE): EffR[F, R, E, A] =
      (_: R) => MC.raiseError(e)

    def handleErrorWith[A](fa: EffR[F, R, E, A])(f: EE => EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) => MC.handleErrorWith(fa(r))(e => f(e)(r))

    def canceled: EffR[F, R, E, Unit] =
      (_: R) => MC.canceled

    def onCancel[A](fa: EffR[F, R, E, A], fin: EffR[F, R, E, Unit]): EffR[F, R, E, A] =
      (r: R) => MC.onCancel(fa(r), MC.void(fin(r)))

    def forceR[A, B](fa: EffR[F, R, E, A])(fb: EffR[F, R, E, B]): EffR[F, R, E, B] =
      (r: R) => MC.forceR(fa(r))(fb(r))

    def uncancelable[A](body: Poll[Of[F, R, E]] => EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) =>
        MC.uncancelable { pollF =>
          val lifted = new Poll[Of[F, R, E]]:
            def apply[B](er: EffR[F, R, E, B]): EffR[F, R, E, B] =
              (r2: R) => pollF(er(r2))
          body(lifted)(r)
        }

    override def guaranteeCase[A](fa: EffR[F, R, E, A])(fin: Outcome[Of[F, R, E], EE, A] => EffR[F, R, E, Unit]): EffR[F, R, E, A] =
      (r: R) =>
        MC.guaranteeCase(fa(r)) { outcome =>
          val liftedOutcome: Outcome[Of[F, R, E], EE, A] = outcome match
            case Outcome.Succeeded(success) =>
              Outcome.succeeded[Of[F, R, E], EE, A]((_: R) => success)
            case Outcome.Errored(err) =>
              Outcome.errored[Of[F, R, E], EE, A](err)
            case Outcome.Canceled() =>
              Outcome.canceled[Of[F, R, E], EE, A]
          MC.void(fin(liftedOutcome)(r))
        }
  end given_MonadCancel_Of

  /** `Parallel` instance for `EffR.Of[F, R, E]` derived from `Eff`'s `Parallel`.
    *
    * The parallel applicative type is `R => P.F[A]` where `P.F` is the parallel applicative from
    * `Parallel[Eff.Of[F, E]]`. This threads the environment through parallel composition without
    * introducing external dependencies.
    *
    * ==Instance Priority==
    *   - `Monad[EffR.Of[F, R, E]]` is the primary sequential instance
    *   - `Parallel[EffR.Of[F, R, E]]` enables `.parXxx` operations when desired
    */
  given given_Parallel_Of[F0[_], R, E](using P: Parallel[Base[F0, E]]): Parallel[Of[F0, R, E]] with
    // The parallel applicative is a reader over Eff's parallel applicative: R => P.F[A]
    type F[x] = R => P.F[x]

    val applicative: Applicative[F] = new Applicative[F]:
      def pure[A](a: A): F[A] =
        (_: R) => P.applicative.pure(a)

      def ap[A, B](ff: F[A => B])(fa: F[A]): F[B] =
        (r: R) => P.applicative.ap(ff(r))(fa(r))

      override def map[A, B](fa: F[A])(f: A => B): F[B] =
        (r: R) => P.applicative.map(fa(r))(f)

      override def product[A, B](fa: F[A], fb: F[B]): F[(A, B)] =
        (r: R) => P.applicative.product(fa(r), fb(r))

    val monad: Monad[Of[F0, R, E]] = given_Monad_Of[F0, R, E](using P.monad)

    val sequential: F ~> Of[F0, R, E] =
      new (F ~> Of[F0, R, E]):
        def apply[A](fa: F[A]): EffR[F0, R, E, A] =
          (r: R) => P.sequential(fa(r))

    val parallel: Of[F0, R, E] ~> F =
      new (Of[F0, R, E] ~> F):
        def apply[A](fa: EffR[F0, R, E, A]): F[A] =
          (r: R) => P.parallel(fa(r))
  end given_Parallel_Of
end EffR
