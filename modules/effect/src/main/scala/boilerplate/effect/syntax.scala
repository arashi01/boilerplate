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

import scala.util.Try

import cats.Applicative
import cats.Functor
import cats.Monad
import cats.MonadError
import cats.data.Kleisli
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

// ============================================================================
// Cats-Effect Primitive Interop (delegates to Eff companion)
// ============================================================================

extension [F[_], A](resource: Resource[F, A])
  /** Transforms this `Resource[F, A]` to `Resource[Eff.Of[F, E], A]`.
    *
    * The resulting resource operates in the `Eff` context, treating all values from `F` as
    * successes in the typed error channel.
    */
  inline def eff[E](using MonadCancel[F, Throwable]): Resource[Eff.Of[F, E], A] =
    Eff.liftResource(resource)

extension [F[_], A](ref: Ref[F, A])
  /** Returns a `Ref` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Functor[F]): Ref[Eff.Of[F, E], A] =
    Eff.liftRef(ref)

extension [F[_], A](deferred: Deferred[F, A])
  /** Returns a `Deferred` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Functor[F]): Deferred[Eff.Of[F, E], A] =
    Eff.liftDeferred(deferred)

extension [F[_], A](queue: Queue[F, A])
  /** Returns a `Queue` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Functor[F]): Queue[Eff.Of[F, E], A] =
    Eff.liftQueue(queue)

extension [F[_]](semaphore: Semaphore[F])
  /** Returns a `Semaphore` operating in the `Eff` context. */
  inline def eff[E](using MonadCancel[F, Throwable]): Semaphore[Eff.Of[F, E]] =
    Eff.liftSemaphore(semaphore)

extension [F[_]](latch: CountDownLatch[F])
  /** Returns a `CountDownLatch` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Functor[F]): CountDownLatch[Eff.Of[F, E]] =
    Eff.liftLatch(latch)

extension [F[_]](barrier: CyclicBarrier[F])
  /** Returns a `CyclicBarrier` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Functor[F]): CyclicBarrier[Eff.Of[F, E]] =
    Eff.liftBarrier(barrier)

extension [F[_], A](cell: AtomicCell[F, A])
  /** Returns an `AtomicCell` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Monad[F]): AtomicCell[Eff.Of[F, E], A] =
    Eff.liftCell(cell)

extension [F[_]](supervisor: Supervisor[F])
  /** Returns a `Supervisor` operating in the `Eff` context.
    *
    * The transformation is pure; no effects are executed.
    */
  inline def eff[E](using Functor[F]): Supervisor[Eff.Of[F, E]] =
    Eff.liftSupervisor(supervisor)

extension [F[_], R, E, A](k: Kleisli[Eff.Of[F, E], R, A])
  /** Converts this `Kleisli[Eff.Of[F, E], R, A]` to [[boilerplate.effect.EffR EffR]]. */
  inline def effR: EffR[F, R, E, A] =
    EffR.from(k)

extension [E, A](either: Either[E, A])
  /** Converts this `Either` into [[boilerplate.effect.Eff Eff]]. */
  inline def eff[F[_]: Applicative]: Eff[F, E, A] =
    Eff.from(either)

  /** Converts this `Either` into [[boilerplate.effect.EffR EffR]]. */
  inline def effR[F[_]: Applicative, R]: EffR[F, R, E, A] =
    EffR.from(either)

extension [F[_], E, A](fea: F[Either[E, A]])
  /** Wraps an `F[Either]` as [[boilerplate.effect.Eff Eff]]. */
  inline def eff: Eff[F, E, A] =
    Eff.lift(fea)

  /** Wraps an `F[Either]` as [[boilerplate.effect.EffR EffR]]. */
  inline def effR[R]: EffR[F, R, E, A] =
    EffR.lift(fea)

extension [A](opt: Option[A])
  /** Elevates an `Option` into [[boilerplate.effect.Eff Eff]], supplying an error when empty. */
  inline def eff[F[_]: Applicative, E](ifNone: => E): Eff[F, E, A] =
    Eff.from(opt, ifNone)

  /** Elevates an `Option` into [[boilerplate.effect.EffR EffR]], supplying an error when empty. */
  inline def effR[F[_]: Applicative, R, E](ifNone: => E): EffR[F, R, E, A] =
    EffR.from(opt, ifNone)

extension [F[_]: Functor, A](fo: F[Option[A]])
  /** Elevates an `F[Option]` into [[boilerplate.effect.Eff Eff]]. */
  inline def eff[E](ifNone: => E): Eff[F, E, A] =
    Eff.lift(fo, ifNone)

  /** Elevates an `F[Option]` into [[boilerplate.effect.EffR EffR]]. */
  inline def effR[R, E](ifNone: => E): EffR[F, R, E, A] =
    EffR.lift(fo, ifNone)

extension [A](result: Try[A])
  /** Converts a `Try` into [[boilerplate.effect.Eff Eff]], translating failures. */
  inline def eff[F[_]: Applicative, E](ifFailure: Throwable => E): Eff[F, E, A] =
    Eff.from(result, ifFailure)

  /** Converts a `Try` into [[boilerplate.effect.EffR EffR]], translating failures. */
  inline def effR[F[_]: Applicative, R, E](ifFailure: Throwable => E): EffR[F, R, E, A] =
    EffR.from(result, ifFailure)

extension [F[_], A](fa: F[A])
  /** Captures throwable failures in `F` into [[boilerplate.effect.Eff Eff]]. */
  inline def eff[E](ifFailure: Throwable => E)(using MonadError[F, Throwable]): Eff[F, E, A] =
    Eff.attempt(fa, ifFailure)

  /** Captures throwable failures in `F` into [[boilerplate.effect.EffR EffR]]. */
  inline def effR[R, E](ifFailure: Throwable => E)(using MonadError[F, Throwable]): EffR[F, R, E, A] =
    EffR.attempt(fa, ifFailure)

// ============================================================================
// Fiber Join Extensions
// ============================================================================

extension [F[_], E, A](fiber: Fiber[Eff.Of[F, E], Throwable, A])

  /** Joins the fibre and returns its result, never completing if the fibre was cancelled.
    *
    * If the fibre completes with a success, returns the value. If it completes with an error in the
    * typed channel `E`, that error propagates. If the fibre was cancelled, this never completes.
    *
    * This differs from cats-effect's `joinWithNever` in that it requires only
    * `GenSpawn[F, Throwable]` rather than `GenSpawn[Eff.Of[F, E], Throwable]`.
    */
  inline def joinNever(using F: GenSpawn[F, Throwable]): Eff[F, E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => Eff.liftF(F.raiseError(e))
      case Outcome.Canceled()    => Eff.lift(F.never[Either[E, A]])
    }(using F)

  /** Joins the fibre and returns its result, failing with a typed error if cancelled.
    *
    * If the fibre completes with a success, returns the value. If it completes with an error in the
    * typed channel `E`, that error propagates. If the fibre was cancelled, fails with `onCanceled`.
    */
  inline def joinOrFail(onCanceled: => E)(using F: MonadCancel[F, Throwable]): Eff[F, E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => Eff.liftF(F.raiseError(e))
      case Outcome.Canceled()    => Eff.fail(onCanceled)
    }(using F)
end extension
