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

import cats.ApplicativeError
import cats.MonadThrow
import cats.effect.IO
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

import boilerplate.Slice

extension [F[_], A](resource: Resource[F, A])
  /** Transforms this `Resource[F, A]` to `Resource[Eff.Of[F, E], A]`. */
  inline def eff[E <: Throwable]: Resource[Eff.Of[F, E], A] =
    Eff.liftResource(resource)

  /** `use` with an `Eff`-returning body, keeping the `Resource` itself `E`-agnostic; release runs
    * on success, typed error, and defect alike.
    */
  inline def useEff[E <: Throwable, B](f: A => Eff[F, E, B])(using MonadCancel[F, Throwable]): Eff[F, E, B] =
    Eff.liftF(resource.use(a => f(a).absolve))

extension [F[_], A](ref: Ref[F, A])
  /** Returns a `Ref` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: Ref[Eff.Of[F, E], A] =
    Eff.liftRef(ref)

extension [F[_], A](deferred: Deferred[F, A])
  /** Returns a `Deferred` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: Deferred[Eff.Of[F, E], A] =
    Eff.liftDeferred(deferred)

extension [F[_], A](queue: Queue[F, A])
  /** Returns a `Queue` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: Queue[Eff.Of[F, E], A] =
    Eff.liftQueue(queue)

extension [F[_]](semaphore: Semaphore[F])
  /** Returns a `Semaphore` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: Semaphore[Eff.Of[F, E]] =
    Eff.liftSemaphore(semaphore)

extension [F[_]](latch: CountDownLatch[F])
  /** Returns a `CountDownLatch` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: CountDownLatch[Eff.Of[F, E]] =
    Eff.liftLatch(latch)

extension [F[_]](barrier: CyclicBarrier[F])
  /** Returns a `CyclicBarrier` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: CyclicBarrier[Eff.Of[F, E]] =
    Eff.liftBarrier(barrier)

extension [F[_], A](cell: AtomicCell[F, A])
  /** Returns an `AtomicCell` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: AtomicCell[Eff.Of[F, E], A] =
    Eff.liftCell(cell)

extension [F[_]](supervisor: Supervisor[F])
  /** Returns a `Supervisor` operating in the `Eff` context. */
  inline def eff[E <: Throwable]: Supervisor[Eff.Of[F, E]] =
    Eff.liftSupervisor(supervisor)

extension [E <: Throwable, A](either: Either[E, A])
  /** Converts this `Either` into [[boilerplate.effect.Eff Eff]]. */
  inline def eff[F[_]](using ApplicativeError[F, Throwable]): Eff[F, E, A] =
    Eff.from(either)

extension [F[_], E <: Throwable, A](fea: F[Either[E, A]])
  /** Wraps an `F[Either]` as [[boilerplate.effect.Eff Eff]]. */
  inline def eff(using MonadThrow[F]): Eff[F, E, A] =
    Eff.lift(fea)

extension [A](opt: Option[A])
  /** Elevates an `Option` into [[boilerplate.effect.Eff Eff]], supplying an error when empty. */
  inline def eff[F[_], E <: Throwable](ifNone: => E)(using ApplicativeError[F, Throwable]): Eff[F, E, A] =
    Eff.from(opt, ifNone)

extension [F[_], A](fo: F[Option[A]])
  /** Elevates an `F[Option]` into [[boilerplate.effect.Eff Eff]]. */
  inline def eff[E <: Throwable](ifNone: => E)(using MonadThrow[F]): Eff[F, E, A] =
    Eff.lift(fo, ifNone)

extension [A](result: Try[A])
  /** Converts a `Try` into [[boilerplate.effect.Eff Eff]], translating failures. */
  inline def eff[F[_], E <: Throwable](ifFailure: Throwable => E)(using ApplicativeError[F, Throwable]): Eff[F, E, A] =
    Eff.from(result, ifFailure)

extension [F[_], A](fa: F[A])
  /** Captures throwable failures in `F` into [[boilerplate.effect.Eff Eff]]. */
  inline def eff[E <: Throwable](ifFailure: Throwable => E)(using MonadThrow[F]): Eff[F, E, A] =
    Eff.attempt(fa, ifFailure)

  /** Lifts an infallible `F[A]` into [[boilerplate.effect.Eff Eff]], treating values as successes. */
  inline def eff: UEff[F, A] =
    Eff.liftF(fa)

extension [F[_], E <: Throwable, A](fiber: Fiber[Eff.Of[F, E], Throwable, A])

  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation never
    * completes. Unlike cats-effect's `joinWithNever`, this needs only `GenSpawn[F, Throwable]`.
    */
  inline def joinNever(using F: GenSpawn[F, Throwable]): Eff[F, E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => Eff.liftF[F, E, A](F.raiseError(e))
      case Outcome.Canceled()    => Eff.liftF[F, E, A](F.never[A])
    }(using F)

  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation fails
    * with `onCanceled`.
    */
  inline def joinOrFail(onCanceled: => E)(using F: MonadCancel[F, Throwable]): Eff[F, E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => Eff.liftF[F, E, A](F.raiseError(e))
      case Outcome.Canceled()    => Eff.fail(onCanceled)
    }(using F)
end extension

extension [E <: Throwable, A](fiber: Fiber[EffIO.Of[E], Throwable, A])

  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation never
    * completes. The `EffIO` sibling of the `Eff`-context `joinNever`.
    */
  inline def joinNever: EffIO[E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => EffIO.liftF(IO.raiseError[A](e))
      case Outcome.Canceled()    => EffIO.liftF(IO.never[A])
    }

  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation fails
    * with `onCanceled`.
    */
  inline def joinOrFail(onCanceled: => E): EffIO[E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => EffIO.liftF(IO.raiseError[A](e))
      case Outcome.Canceled()    => EffIO.fail(onCanceled)
    }
end extension

extension [A](io: IO[A])
  /** Captures throwable failures in `IO` into [[boilerplate.effect.EffIO EffIO]]. */
  inline def effIO[E <: Throwable](ifFailure: Throwable => E): EffIO[E, A] =
    EffIO.attempt(io, ifFailure)

  /** Lifts an infallible `IO` into [[boilerplate.effect.EffIO EffIO]]. */
  inline def effIO: UEffIO[A] =
    EffIO.liftF(io)

extension [E <: Throwable, A](io: IO[Either[E, A]])
  /** Wraps an `IO[Either]` as [[boilerplate.effect.EffIO EffIO]]. */
  inline def effIO: EffIO[E, A] =
    EffIO.lift(io)

extension [E <: Throwable, A](either: Either[E, A])
  /** Converts this `Either` into [[boilerplate.effect.EffIO EffIO]]. */
  inline def effIO: EffIO[E, A] =
    EffIO.from(either)

extension [A](opt: Option[A])
  /** Elevates an `Option` into [[boilerplate.effect.EffIO EffIO]], supplying an error when empty. */
  inline def effIO[E <: Throwable](ifNone: => E): EffIO[E, A] =
    EffIO.from(opt, ifNone)

extension [A](io: IO[Option[A]])
  /** Elevates an `IO[Option]` into [[boilerplate.effect.EffIO EffIO]], supplying an error when
    * empty.
    */
  inline def effIO[E <: Throwable](ifNone: => E): EffIO[E, A] =
    EffIO.lift(io, ifNone)

extension [A](result: Try[A])
  /** Converts a `Try` into [[boilerplate.effect.EffIO EffIO]], translating failures. */
  inline def effIO[E <: Throwable](ifFailure: Throwable => E): EffIO[E, A] =
    EffIO.from(result, ifFailure)

extension [A](resource: Resource[IO, A])
  /** Transforms this `Resource[IO, A]` to `Resource[EffIO.Of[E], A]`. */
  inline def effIO[E <: Throwable]: Resource[EffIO.Of[E], A] =
    EffIO.liftResource(resource)

  /** `use` with an `EffIO`-returning body, keeping the `Resource` itself `E`-agnostic; release runs
    * on success, typed error, and defect alike.
    */
  inline def useEffIO[E <: Throwable, B](f: A => EffIO[E, B]): EffIO[E, B] =
    EffIO.liftF(resource.use(a => f(a).absolve))

extension (acquire: IO[Slice])
  /** A `Resource` that acquires a secret slice through `acquire` and wipes it on release - on
    * success, error, or cancellation of the using effect. Keep the working-copy allocation inside
    * `acquire` so the slice is erased from the moment it exists; consume with [[useEffIO]] or
    * `use`, and do not let the slice escape the use.
    */
  inline def wiping: Resource[IO, Slice] =
    Resource.make(acquire)(s => IO(s.wipe()))

extension [A](ref: Ref[IO, A])
  /** Returns a `Ref` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: Ref[EffIO.Of[E], A] =
    EffIO.liftRef(ref)

extension [A](deferred: Deferred[IO, A])
  /** Returns a `Deferred` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: Deferred[EffIO.Of[E], A] =
    EffIO.liftDeferred(deferred)

extension [A](queue: Queue[IO, A])
  /** Returns a `Queue` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: Queue[EffIO.Of[E], A] =
    EffIO.liftQueue(queue)

extension (semaphore: Semaphore[IO])
  /** Returns a `Semaphore` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: Semaphore[EffIO.Of[E]] =
    EffIO.liftSemaphore(semaphore)

extension (latch: CountDownLatch[IO])
  /** Returns a `CountDownLatch` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: CountDownLatch[EffIO.Of[E]] =
    EffIO.liftLatch(latch)

extension (barrier: CyclicBarrier[IO])
  /** Returns a `CyclicBarrier` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: CyclicBarrier[EffIO.Of[E]] =
    EffIO.liftBarrier(barrier)

extension [A](cell: AtomicCell[IO, A])
  /** Returns an `AtomicCell` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: AtomicCell[EffIO.Of[E], A] =
    EffIO.liftCell(cell)

extension (supervisor: Supervisor[IO])
  /** Returns a `Supervisor` operating in the `EffIO` context. */
  inline def effIO[E <: Throwable]: Supervisor[EffIO.Of[E]] =
    EffIO.liftSupervisor(supervisor)
