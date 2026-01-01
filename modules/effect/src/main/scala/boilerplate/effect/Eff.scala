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
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import cats.*
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.data.Nested
import cats.effect.kernel.Async
import cats.effect.kernel.Clock
import cats.effect.kernel.Cont
import cats.effect.kernel.Deferred
import cats.effect.kernel.Fiber
import cats.effect.kernel.GenConcurrent
import cats.effect.kernel.GenSpawn
import cats.effect.kernel.GenTemporal
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome
import cats.effect.kernel.Poll
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
import cats.syntax.all.*

/** Zero-cost typed error channel abstraction represented as `F[Either[E, A]]`. Refer to
  * [[boilerplate.effect.Eff$ Eff]] for constructors and utilities.
  */
opaque type Eff[F[_], E, A] = F[Either[E, A]]

/** Infallible effect: `Eff` with `Nothing` as the error type. */
type UEff[F[_], A] = Eff[F, Nothing, A]

/** Throwable-errored effect: `Eff` with `Throwable` as the error type. */
type TEff[F[_], A] = Eff[F, Throwable, A]

/** Low-priority `GenSpawn` instance for fibre spawning and racing. */
private[effect] trait EffInstancesLowPriority5:
  import Eff.Of
  import Eff.wrapUnsafe
  import Eff.unwrapUnsafe

  /** Provides fibre spawning and racing for `Eff` computations.
    *
    * Enables `start`, `race`, `racePair`, `background` and related concurrent primitives.
    *
    * ==Typed Error Semantics==
    * A fibre completing with a typed error `E` is considered a ''successful'' `Outcome` from the
    * fibre's perspective — the typed error is carried within `Outcome.Succeeded`. Defects
    * (`Throwable`) propagate via `Outcome.Errored`.
    */
  given [F[_], E0] => (S: GenSpawn[F, Throwable]) => GenSpawn[Of[F, E0], Throwable]:
    // --- MonadCancel delegation ---

    def pure[A](a: A): Eff[F, E0, A] = wrapUnsafe(S.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(
        S.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => S.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      wrapUnsafe(
        S.tailRecM(a) { current =>
          S.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: Throwable): Eff[F, E0, A] =
      wrapUnsafe(S.raiseError[Either[E0, A]](e))

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: Throwable => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(S.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))

    def canceled: Eff[F, E0, Unit] = Eff.liftF(S.canceled)

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      Eff.lift(S.onCancel(unwrapUnsafe(fa), S.void(unwrapUnsafe(fin))))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      Eff.lift(S.forceR(unwrapUnsafe(fa))(unwrapUnsafe(fb)))

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      Eff.lift(S.uncancelable { pollF =>
        unwrapUnsafe(body(Eff.poll(pollF)))
      })

    override def guaranteeCase[A](fa: Eff[F, E0, A])(
      fin: Outcome[Of[F, E0], Throwable, A] => Eff[F, E0, Unit]
    ): Eff[F, E0, A] =
      Eff.lift(S.guaranteeCase(unwrapUnsafe(fa)) {
        case Outcome.Succeeded(fea) =>
          S.void(unwrapUnsafe(fin(Outcome.succeeded[Of[F, E0], Throwable, A](Eff.lift(fea)))))
        case Outcome.Errored(e) =>
          S.handleError(S.void(unwrapUnsafe(fin(Outcome.errored[Of[F, E0], Throwable, A](e)))))(_ => ())
        case Outcome.Canceled() =>
          S.void(unwrapUnsafe(fin(Outcome.canceled[Of[F, E0], Throwable, A])))
      })

    // --- Unique delegation ---

    override def applicative: Applicative[Of[F, E0]] = this

    def unique: Eff[F, E0, Unique.Token] = Eff.liftF(S.unique)

    // --- GenSpawn methods ---

    def start[A](fa: Eff[F, E0, A]): Eff[F, E0, Fiber[Of[F, E0], Throwable, A]] =
      Eff.liftF(S.map(S.start(unwrapUnsafe(fa)))(liftFiber))

    def never[A]: Eff[F, E0, A] = Eff.liftF(S.never)

    def cede: Eff[F, E0, Unit] = Eff.liftF(S.cede)

    def racePair[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[
        (Outcome[Of[F, E0], Throwable, A], Fiber[Of[F, E0], Throwable, B]),
        (Fiber[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
      ]
    ] =
      Eff.liftF(
        S.uncancelable(poll =>
          S.map(poll(S.racePair(unwrapUnsafe(fa), unwrapUnsafe(fb)))) {
            case Left((oc, fib))  => Left((liftOutcome(oc), liftFiber(fib)))
            case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
          }
        )
      )

    override def race[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, Either[A, B]] =
      Eff.lift(S.map(S.race(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bisequence))

    override def both[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, (A, B)] =
      Eff.lift(S.map(S.both(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.tupled))

    override def raceOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B]]
    ] =
      Eff.liftF(S.map(S.raceOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    override def bothOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      (Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
    ] =
      Eff.liftF(S.map(S.bothOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    private def liftOutcome[A](oc: Outcome[F, Throwable, Either[E0, A]]): Outcome[Of[F, E0], Throwable, A] =
      oc match
        case Outcome.Canceled()     => Outcome.Canceled()
        case Outcome.Errored(e)     => Outcome.Errored(e)
        case Outcome.Succeeded(fea) => Outcome.Succeeded(Eff.lift(fea))

    private def liftFiber[A](fib: Fiber[F, Throwable, Either[E0, A]]): Fiber[Of[F, E0], Throwable, A] =
      Eff.fiber(fib, S)
  end given
end EffInstancesLowPriority5

/** Low-priority `GenConcurrent` instance for concurrent primitives. */
private[effect] trait EffInstancesLowPriority4 extends EffInstancesLowPriority5:
  import Eff.Of
  import Eff.wrapUnsafe
  import Eff.unwrapUnsafe

  /** Provides concurrent primitives (`Ref`, `Deferred`, `memoize`) for `Eff` computations.
    *
    * Extends `GenSpawn` with the ability to create concurrent state primitives.
    */
  given [F[_], E0] => (C: GenConcurrent[F, Throwable]) => GenConcurrent[Of[F, E0], Throwable]:
    // --- Inherit all GenSpawn behaviour ---

    def pure[A](a: A): Eff[F, E0, A] = wrapUnsafe(C.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(
        C.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => C.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      wrapUnsafe(
        C.tailRecM(a) { current =>
          C.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: Throwable): Eff[F, E0, A] =
      wrapUnsafe(C.raiseError[Either[E0, A]](e))

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: Throwable => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(C.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))

    def canceled: Eff[F, E0, Unit] = Eff.liftF(C.canceled)

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      Eff.lift(C.onCancel(unwrapUnsafe(fa), C.void(unwrapUnsafe(fin))))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      Eff.lift(C.forceR(unwrapUnsafe(fa))(unwrapUnsafe(fb)))

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      Eff.lift(C.uncancelable { pollF =>
        unwrapUnsafe(body(Eff.poll(pollF)))
      })

    override def guaranteeCase[A](fa: Eff[F, E0, A])(
      fin: Outcome[Of[F, E0], Throwable, A] => Eff[F, E0, Unit]
    ): Eff[F, E0, A] =
      Eff.lift(C.guaranteeCase(unwrapUnsafe(fa)) {
        case Outcome.Succeeded(fea) =>
          C.void(unwrapUnsafe(fin(Outcome.succeeded[Of[F, E0], Throwable, A](Eff.lift(fea)))))
        case Outcome.Errored(e) =>
          C.handleError(C.void(unwrapUnsafe(fin(Outcome.errored[Of[F, E0], Throwable, A](e)))))(_ => ())
        case Outcome.Canceled() =>
          C.void(unwrapUnsafe(fin(Outcome.canceled[Of[F, E0], Throwable, A])))
      })

    override def applicative: Applicative[Of[F, E0]] = this

    def unique: Eff[F, E0, Unique.Token] = Eff.liftF(C.unique)

    // --- GenSpawn methods ---

    def start[A](fa: Eff[F, E0, A]): Eff[F, E0, Fiber[Of[F, E0], Throwable, A]] =
      Eff.liftF(C.map(C.start(unwrapUnsafe(fa)))(liftFiber))

    def never[A]: Eff[F, E0, A] = Eff.liftF(C.never)

    def cede: Eff[F, E0, Unit] = Eff.liftF(C.cede)

    override def racePair[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[
        (Outcome[Of[F, E0], Throwable, A], Fiber[Of[F, E0], Throwable, B]),
        (Fiber[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
      ]
    ] =
      Eff.liftF(
        C.uncancelable(poll =>
          C.map(poll(C.racePair(unwrapUnsafe(fa), unwrapUnsafe(fb)))) {
            case Left((oc, fib))  => Left((liftOutcome(oc), liftFiber(fib)))
            case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
          }
        )
      )

    override def race[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, Either[A, B]] =
      Eff.lift(C.map(C.race(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bisequence))

    override def both[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, (A, B)] =
      Eff.lift(C.map(C.both(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.tupled))

    override def raceOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B]]
    ] =
      Eff.liftF(C.map(C.raceOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    override def bothOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      (Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
    ] =
      Eff.liftF(C.map(C.bothOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    // --- GenConcurrent methods ---

    def ref[A](a: A): Eff[F, E0, Ref[Of[F, E0], A]] =
      Eff.liftF(C.map(C.ref(a))(_.mapK(Eff.functionK[F, E0])))

    def deferred[A]: Eff[F, E0, Deferred[Of[F, E0], A]] =
      Eff.liftF(C.map(C.deferred[A])(_.mapK(Eff.functionK[F, E0])))

    private def liftOutcome[A](oc: Outcome[F, Throwable, Either[E0, A]]): Outcome[Of[F, E0], Throwable, A] =
      oc match
        case Outcome.Canceled()     => Outcome.Canceled()
        case Outcome.Errored(e)     => Outcome.Errored(e)
        case Outcome.Succeeded(fea) => Outcome.Succeeded(Eff.lift(fea))

    private def liftFiber[A](fib: Fiber[F, Throwable, Either[E0, A]]): Fiber[Of[F, E0], Throwable, A] =
      Eff.fiber(fib, C)
  end given
end EffInstancesLowPriority4

/** Low-priority `GenTemporal` instance for temporal operations. */
private[effect] trait EffInstancesLowPriority3 extends EffInstancesLowPriority4:
  import Eff.Of
  import Eff.wrapUnsafe
  import Eff.unwrapUnsafe

  /** Provides temporal primitives (`sleep`, `timeout`) for `Eff` computations.
    *
    * Extends `GenConcurrent` with time-based operations.
    */
  given [F[_], E0] => (T: GenTemporal[F, Throwable]) => GenTemporal[Of[F, E0], Throwable]:
    // --- Inherit all GenConcurrent behaviour ---

    def pure[A](a: A): Eff[F, E0, A] = wrapUnsafe(T.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(
        T.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => T.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      wrapUnsafe(
        T.tailRecM(a) { current =>
          T.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: Throwable): Eff[F, E0, A] =
      wrapUnsafe(T.raiseError[Either[E0, A]](e))

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: Throwable => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(T.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))

    def canceled: Eff[F, E0, Unit] = Eff.liftF(T.canceled)

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      Eff.lift(T.onCancel(unwrapUnsafe(fa), T.void(unwrapUnsafe(fin))))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      Eff.lift(T.forceR(unwrapUnsafe(fa))(unwrapUnsafe(fb)))

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      Eff.lift(T.uncancelable { pollF =>
        unwrapUnsafe(body(Eff.poll(pollF)))
      })

    override def guaranteeCase[A](fa: Eff[F, E0, A])(
      fin: Outcome[Of[F, E0], Throwable, A] => Eff[F, E0, Unit]
    ): Eff[F, E0, A] =
      Eff.lift(T.guaranteeCase(unwrapUnsafe(fa)) {
        case Outcome.Succeeded(fea) =>
          T.void(unwrapUnsafe(fin(Outcome.succeeded[Of[F, E0], Throwable, A](Eff.lift(fea)))))
        case Outcome.Errored(e) =>
          T.handleError(T.void(unwrapUnsafe(fin(Outcome.errored[Of[F, E0], Throwable, A](e)))))(_ => ())
        case Outcome.Canceled() =>
          T.void(unwrapUnsafe(fin(Outcome.canceled[Of[F, E0], Throwable, A])))
      })

    override def applicative: Applicative[Of[F, E0]] = this

    def unique: Eff[F, E0, Unique.Token] = Eff.liftF(T.unique)

    // --- GenSpawn methods ---

    def start[A](fa: Eff[F, E0, A]): Eff[F, E0, Fiber[Of[F, E0], Throwable, A]] =
      Eff.liftF(T.map(T.start(unwrapUnsafe(fa)))(liftFiber))

    def never[A]: Eff[F, E0, A] = Eff.liftF(T.never)

    def cede: Eff[F, E0, Unit] = Eff.liftF(T.cede)

    override def racePair[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[
        (Outcome[Of[F, E0], Throwable, A], Fiber[Of[F, E0], Throwable, B]),
        (Fiber[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
      ]
    ] =
      Eff.liftF(
        T.uncancelable(poll =>
          T.map(poll(T.racePair(unwrapUnsafe(fa), unwrapUnsafe(fb)))) {
            case Left((oc, fib))  => Left((liftOutcome(oc), liftFiber(fib)))
            case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
          }
        )
      )

    override def race[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, Either[A, B]] =
      Eff.lift(T.map(T.race(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bisequence))

    override def both[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, (A, B)] =
      Eff.lift(T.map(T.both(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.tupled))

    override def raceOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B]]
    ] =
      Eff.liftF(T.map(T.raceOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    override def bothOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      (Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
    ] =
      Eff.liftF(T.map(T.bothOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    // --- GenConcurrent methods ---

    def ref[A](a: A): Eff[F, E0, Ref[Of[F, E0], A]] =
      Eff.liftF(T.map(T.ref(a))(_.mapK(Eff.functionK[F, E0])))

    def deferred[A]: Eff[F, E0, Deferred[Of[F, E0], A]] =
      Eff.liftF(T.map(T.deferred[A])(_.mapK(Eff.functionK[F, E0])))

    // --- Clock methods ---

    def monotonic: Eff[F, E0, FiniteDuration] = Eff.liftF(T.monotonic)

    def realTime: Eff[F, E0, FiniteDuration] = Eff.liftF(T.realTime)

    // --- GenTemporal methods ---

    protected def sleep(time: FiniteDuration): Eff[F, E0, Unit] =
      Eff.liftF(T.sleep(time))

    private def liftOutcome[A](oc: Outcome[F, Throwable, Either[E0, A]]): Outcome[Of[F, E0], Throwable, A] =
      oc match
        case Outcome.Canceled()     => Outcome.Canceled()
        case Outcome.Errored(e)     => Outcome.Errored(e)
        case Outcome.Succeeded(fea) => Outcome.Succeeded(Eff.lift(fea))

    private def liftFiber[A](fib: Fiber[F, Throwable, Either[E0, A]]): Fiber[Of[F, E0], Throwable, A] =
      Eff.fiber(fib, T)
  end given
end EffInstancesLowPriority3

/** Low-priority `Sync` instance for synchronous effect suspension. */
private[effect] trait EffInstancesLowPriority2 extends EffInstancesLowPriority3:
  import Eff.Of
  import Eff.wrapUnsafe
  import Eff.unwrapUnsafe

  /** Provides synchronous effect suspension for `Eff` computations.
    *
    * Enables `delay`, `blocking`, `interruptible` for synchronous side effects.
    */
  given [F[_], E0] => (S: Sync[F]) => Sync[Of[F, E0]]:
    // --- MonadCancel delegation ---

    def pure[A](a: A): Eff[F, E0, A] = wrapUnsafe(S.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(
        S.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => S.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      wrapUnsafe(
        S.tailRecM(a) { current =>
          S.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: Throwable): Eff[F, E0, A] =
      wrapUnsafe(S.raiseError[Either[E0, A]](e))

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: Throwable => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(S.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))

    def canceled: Eff[F, E0, Unit] = Eff.liftF(S.canceled)

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      Eff.lift(S.onCancel(unwrapUnsafe(fa), S.void(unwrapUnsafe(fin))))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      Eff.lift(S.forceR(unwrapUnsafe(fa))(unwrapUnsafe(fb)))

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      Eff.lift(S.uncancelable { pollF =>
        unwrapUnsafe(body(Eff.poll(pollF)))
      })

    override def guaranteeCase[A](fa: Eff[F, E0, A])(
      fin: Outcome[Of[F, E0], Throwable, A] => Eff[F, E0, Unit]
    ): Eff[F, E0, A] =
      Eff.lift(S.guaranteeCase(unwrapUnsafe(fa)) {
        case Outcome.Succeeded(fea) =>
          S.void(unwrapUnsafe(fin(Outcome.succeeded[Of[F, E0], Throwable, A](Eff.lift(fea)))))
        case Outcome.Errored(e) =>
          S.handleError(S.void(unwrapUnsafe(fin(Outcome.errored[Of[F, E0], Throwable, A](e)))))(_ => ())
        case Outcome.Canceled() =>
          S.void(unwrapUnsafe(fin(Outcome.canceled[Of[F, E0], Throwable, A])))
      })

    def rootCancelScope = S.rootCancelScope

    // --- Clock methods ---

    def monotonic: Eff[F, E0, FiniteDuration] = Eff.liftF(S.monotonic)

    def realTime: Eff[F, E0, FiniteDuration] = Eff.liftF(S.realTime)

    // --- Unique methods ---

    override def unique: Eff[F, E0, Unique.Token] = Eff.liftF(S.unique)

    // --- Sync methods ---

    def suspend[A](hint: Sync.Type)(thunk: => A): Eff[F, E0, A] =
      Eff.liftF(S.suspend(hint)(thunk))
  end given
end EffInstancesLowPriority2

/** Low-priority `Async` instance for asynchronous effects. */
private[effect] trait EffInstancesLowPriority1 extends EffInstancesLowPriority2:
  import Eff.Of
  import Eff.wrapUnsafe
  import Eff.unwrapUnsafe

  /** Provides asynchronous effect capabilities for `Eff` computations.
    *
    * Enables `async`, `async_`, `fromFuture`, and evaluation on different execution contexts.
    */
  given [F[_], E0] => (A: Async[F]) => Async[Of[F, E0]]:
    // --- MonadCancel delegation ---

    def pure[A](a: A): Eff[F, E0, A] = wrapUnsafe(A.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(
        A.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => A.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      wrapUnsafe(
        A.tailRecM(a) { current =>
          A.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: Throwable): Eff[F, E0, A] =
      wrapUnsafe(A.raiseError[Either[E0, A]](e))

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: Throwable => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(A.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))

    def canceled: Eff[F, E0, Unit] = Eff.liftF(A.canceled)

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      Eff.lift(A.onCancel(unwrapUnsafe(fa), A.void(unwrapUnsafe(fin))))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      Eff.lift(A.forceR(unwrapUnsafe(fa))(unwrapUnsafe(fb)))

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      Eff.lift(A.uncancelable { pollF =>
        unwrapUnsafe(body(Eff.poll(pollF)))
      })

    override def guaranteeCase[A](fa: Eff[F, E0, A])(
      fin: Outcome[Of[F, E0], Throwable, A] => Eff[F, E0, Unit]
    ): Eff[F, E0, A] =
      Eff.lift(A.guaranteeCase(unwrapUnsafe(fa)) {
        case Outcome.Succeeded(fea) =>
          A.void(unwrapUnsafe(fin(Outcome.succeeded[Of[F, E0], Throwable, A](Eff.lift(fea)))))
        case Outcome.Errored(e) =>
          A.handleError(A.void(unwrapUnsafe(fin(Outcome.errored[Of[F, E0], Throwable, A](e)))))(_ => ())
        case Outcome.Canceled() =>
          A.void(unwrapUnsafe(fin(Outcome.canceled[Of[F, E0], Throwable, A])))
      })

    // --- Unique methods ---

    override def applicative: Applicative[Of[F, E0]] = this

    override def unique: Eff[F, E0, Unique.Token] = Eff.liftF(A.unique)

    // --- GenSpawn methods ---

    def start[A](fa: Eff[F, E0, A]): Eff[F, E0, Fiber[Of[F, E0], Throwable, A]] =
      Eff.liftF(A.map(A.start(unwrapUnsafe(fa)))(liftFiber))

    override def never[A]: Eff[F, E0, A] = Eff.liftF(A.never)

    def cede: Eff[F, E0, Unit] = Eff.liftF(A.cede)

    override def racePair[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[
        (Outcome[Of[F, E0], Throwable, A], Fiber[Of[F, E0], Throwable, B]),
        (Fiber[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
      ]
    ] =
      Eff.liftF(
        A.uncancelable(poll =>
          A.map(poll(A.racePair(unwrapUnsafe(fa), unwrapUnsafe(fb)))) {
            case Left((oc, fib))  => Left((liftOutcome(oc), liftFiber(fib)))
            case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
          }
        )
      )

    override def race[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, Either[A, B]] =
      Eff.lift(A.map(A.race(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bisequence))

    override def both[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[F, E0, (A, B)] =
      Eff.lift(A.map(A.both(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.tupled))

    override def raceOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      Either[Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B]]
    ] =
      Eff.liftF(A.map(A.raceOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    override def bothOutcome[A, B](fa: Eff[F, E0, A], fb: Eff[F, E0, B]): Eff[
      F,
      E0,
      (Outcome[Of[F, E0], Throwable, A], Outcome[Of[F, E0], Throwable, B])
    ] =
      Eff.liftF(A.map(A.bothOutcome(unwrapUnsafe(fa), unwrapUnsafe(fb)))(_.bimap(liftOutcome, liftOutcome)))

    // --- GenConcurrent methods ---

    def ref[A](a: A): Eff[F, E0, Ref[Of[F, E0], A]] =
      Eff.liftF(A.map(A.ref(a))(_.mapK(Eff.functionK[F, E0])))

    def deferred[A]: Eff[F, E0, Deferred[Of[F, E0], A]] =
      Eff.liftF(A.map(A.deferred[A])(_.mapK(Eff.functionK[F, E0])))

    // --- Clock methods ---

    def monotonic: Eff[F, E0, FiniteDuration] = Eff.liftF(A.monotonic)

    def realTime: Eff[F, E0, FiniteDuration] = Eff.liftF(A.realTime)

    // --- GenTemporal methods ---

    def sleep(time: FiniteDuration): Eff[F, E0, Unit] =
      Eff.liftF(A.sleep(time))

    // --- Sync methods ---

    def suspend[A](hint: Sync.Type)(thunk: => A): Eff[F, E0, A] =
      Eff.liftF(A.suspend(hint)(thunk))

    // --- Async methods ---

    def cont[K, R](body: Cont[Of[F, E0], K, R]): Eff[F, E0, R] =
      Eff.lift(A.cont(Eff.contImpl(body)))

    def evalOn[A](fa: Eff[F, E0, A], ec: ExecutionContext): Eff[F, E0, A] =
      Eff.lift(A.evalOn(unwrapUnsafe(fa), ec))

    def executionContext: Eff[F, E0, ExecutionContext] =
      Eff.liftF(A.executionContext)

    private def liftOutcome[A](oc: Outcome[F, Throwable, Either[E0, A]]): Outcome[Of[F, E0], Throwable, A] =
      oc match
        case Outcome.Canceled()     => Outcome.Canceled()
        case Outcome.Errored(e)     => Outcome.Errored(e)
        case Outcome.Succeeded(fea) => Outcome.Succeeded(Eff.lift(fea))

    private def liftFiber[A](fib: Fiber[F, Throwable, Either[E0, A]]): Fiber[Of[F, E0], Throwable, A] =
      Eff.fiber(fib, A)
  end given
end EffInstancesLowPriority1

/** Low-priority defect-channel `MonadCancel` and `MonadError` instances. */
private[effect] trait EffInstancesLowPriority0 extends EffInstancesLowPriority1:
  import Eff.Of
  import Eff.wrapUnsafe
  import Eff.unwrapUnsafe

  /** Lifts a `MonadError` from `F` itself, propagating external failures (defects). */
  given [F[_], E, EE] => (F0: MonadError[F, EE]) => MonadError[Of[F, E], EE]:
    def pure[A](a: A): Eff[F, E, A] = wrapUnsafe(F0.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      wrapUnsafe(
        F0.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => F0.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      wrapUnsafe(
        F0.tailRecM(a) { current =>
          F0.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: EE): Eff[F, E, A] =
      wrapUnsafe(F0.raiseError[Either[E, A]](e))

    def handleErrorWith[A](fa: Eff[F, E, A])(f: EE => Eff[F, E, A]): Eff[F, E, A] =
      wrapUnsafe(F0.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))
  end given

  /** Delegates cancellation semantics from `F` whilst retaining typed errors. */
  given [F[_], E0, EE] => (MC: MonadCancel[F, EE]) => MonadCancel[Of[F, E0], EE]:
    def rootCancelScope = MC.rootCancelScope

    def pure[A](a: A): Eff[F, E0, A] = wrapUnsafe(MC.pure(Right(a)))

    def flatMap[A, B](fa: Eff[F, E0, A])(f: A => Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(
        MC.flatMap(unwrapUnsafe(fa)) {
          case Right(a) => unwrapUnsafe(f(a))
          case Left(e)  => MC.pure(Left(e))
        }
      )

    def tailRecM[A, B](a: A)(f: A => Eff[F, E0, Either[A, B]]): Eff[F, E0, B] =
      wrapUnsafe(
        MC.tailRecM(a) { current =>
          MC.map(unwrapUnsafe(f(current))) {
            case Left(e)            => Right(Left(e))
            case Right(Left(next))  => Left(next)
            case Right(Right(done)) => Right(Right(done))
          }
        }
      )

    def raiseError[A](e: EE): Eff[F, E0, A] =
      wrapUnsafe(MC.raiseError[Either[E0, A]](e))

    def handleErrorWith[A](fa: Eff[F, E0, A])(f: EE => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(MC.handleErrorWith(unwrapUnsafe(fa))(e => unwrapUnsafe(f(e))))

    def canceled: Eff[F, E0, Unit] = Eff.liftF(MC.canceled)

    def onCancel[A](fa: Eff[F, E0, A], fin: Eff[F, E0, Unit]): Eff[F, E0, A] =
      wrapUnsafe(MC.onCancel(unwrapUnsafe(fa), MC.void(unwrapUnsafe(fin))))

    def forceR[A, B](fa: Eff[F, E0, A])(fb: Eff[F, E0, B]): Eff[F, E0, B] =
      wrapUnsafe(MC.forceR(unwrapUnsafe(fa))(unwrapUnsafe(fb)))

    def uncancelable[A](body: Poll[Of[F, E0]] => Eff[F, E0, A]): Eff[F, E0, A] =
      wrapUnsafe(MC.uncancelable { pollF =>
        unwrapUnsafe(body(Eff.poll(pollF)))
      })

    override def guaranteeCase[A](fa: Eff[F, E0, A])(fin: Outcome[Of[F, E0], EE, A] => Eff[F, E0, Unit]): Eff[F, E0, A] =
      wrapUnsafe(MC.guaranteeCase(unwrapUnsafe(fa)) {
        case Outcome.Succeeded(success) =>
          val lifted = Outcome.succeeded[Of[F, E0], EE, A](wrapUnsafe(success))
          MC.void(unwrapUnsafe(fin(lifted)))
        case Outcome.Errored(err) =>
          val lifted = Outcome.errored[Of[F, E0], EE, A](err)
          MC.void(unwrapUnsafe(fin(lifted)))
        case Outcome.Canceled() =>
          MC.void(unwrapUnsafe(fin(Outcome.canceled[Of[F, E0], EE, A])))
      })
  end given
end EffInstancesLowPriority0

/** Provides constructors, combinators, and type class instances for [[boilerplate.effect.Eff Eff]]. */
object Eff extends EffInstancesLowPriority0:
  /** Partially applied alias enabling higher-kinded usage of [[boilerplate.effect.Eff Eff]]. */
  type Of[F[_], E] = [A] =>> Eff[F, E, A]

  // ===========================================================================
  // Internal Unsafe Conversion Utilities
  // ===========================================================================
  // These provide direct access to the opaque type's underlying representation
  // for use within boilerplate's implementation where the opaque boundary would
  // otherwise require inefficient wrapping/unwrapping.
  // ===========================================================================

  /** Wraps an `F[Either[E, A]]` as `Eff[F, E, A]` without any transformation.
    *
    * This is an identity operation at runtime - the opaque type IS the underlying type. For
    * internal use only where the opaque boundary would otherwise be problematic.
    */
  private[boilerplate] inline def wrapUnsafe[F[_], E, A](fea: F[Either[E, A]]): Eff[F, E, A] = fea

  /** Unwraps an `Eff[F, E, A]` to its underlying `F[Either[E, A]]` without transformation.
    *
    * This is an identity operation at runtime - the opaque type IS the underlying type. For
    * internal use only where the opaque boundary would otherwise be problematic.
    */
  private[boilerplate] inline def unwrapUnsafe[F[_], E, A](eff: Eff[F, E, A]): F[Either[E, A]] = eff

  /** Wraps a pre-existing `F[Either[E, A]]` without allocation. */
  inline def apply[F[_], E, A](fa: F[Either[E, A]]): Eff[F, E, A] = fa

  /** Returns a partially-applied constructor fixing the effect type `F`. */
  inline def apply[F[_]]: EffPartiallyApplied[F] = new EffPartiallyApplied[F]

  /** Partially-applied constructor enabling `Eff[F].succeed(a)` syntax. */
  final class EffPartiallyApplied[F[_]] @publicInBinary private[Eff]:
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

    /** Handles both error and success with effectful functions, allowing error type change.
      *
      * Named `redeemAll` to distinguish from cats' `redeemWith` which preserves error type. This
      * combinator allows transitioning to a new error type `E2` via both handlers.
      */
    inline def redeemAll[E2, B](fe: E => Eff[F, E2, B], fa: A => Eff[F, E2, B])(using Monad[F]): Eff[F, E2, B] =
      Monad[F].flatMap(self) {
        case Left(e)  => fe(e)
        case Right(a) => fa(a)
      }

    /** Observes failures without altering the result.
      *
      * The side effect is a raw `F[Unit]` that cannot itself produce typed errors. For fallible
      * side effects, use [[flatTapError]].
      */
    inline def tapError(f: E => F[Unit])(using Monad[F]): Eff[F, E, A] =
      Monad[F].flatMap(self) {
        case Left(e)  => Monad[F].flatMap(f(e))(_ => Monad[F].pure(Left(e)))
        case Right(a) => Monad[F].pure(Right(a))
      }

    /** Observes failures via an effectful action that can also fail.
      *
      * If the side-effect fails, that failure propagates and replaces the original error. For
      * infallible side effects, use [[tapError]].
      */
    inline def flatTapError(f: E => Eff[F, E, Unit])(using Monad[F]): Eff[F, E, A] =
      Monad[F].flatMap(self) {
        case Left(e)  => f(e).flatMap(_ => Monad[F].pure(Left(e)))
        case Right(a) => Monad[F].pure(Right(a))
      }

    /** Observes success values without altering the result. */
    inline def tap(f: A => F[Unit])(using Monad[F]): Eff[F, E, A] =
      Monad[F].flatMap(self) {
        case Right(a) => Monad[F].map(f(a))(_ => Right(a))
        case Left(e)  => Monad[F].pure(Left(e))
      }

    /** Fallback to an alternative computation when this one fails. */
    inline def alt[E2, B >: A](that: => Eff[F, E2, B])(using Monad[F]): Eff[F, E2, B] =
      Monad[F].flatMap(self) {
        case Left(_)  => that
        case Right(a) => Monad[F].pure(Right(a))
      }

    /** Recovers from any failure with a constant success value. */
    inline def orElseSucceed[B >: A](value: => B)(using Functor[F]): UEff[F, B] =
      Functor[F].map(self) {
        case Left(_)  => Right(value)
        case Right(a) => Right(a)
      }

    /** Replaces any failure with a different error. */
    inline def orElseFail[E2](error: => E2)(using Functor[F]): Eff[F, E2, A] =
      Functor[F].map(self) {
        case Left(_)  => Left(error)
        case Right(a) => Right(a)
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

    /** Sequences this computation with `that`, discarding the result of `this`. */
    inline def productR[B](that: => Eff[F, E, B])(using Monad[F]): Eff[F, E, B] =
      self.flatMap(_ => that)

    /** Sequences this computation with `that`, discarding the result of `that`. */
    inline def productL[B](that: => Eff[F, E, B])(using Monad[F]): Eff[F, E, A] =
      self.flatMap(a => that.map(_ => a))

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

    /** Recovers from all errors by mapping them to a success value.
      *
      * Similar to `getOrElse` for `Option` or `Validated.valueOr`. Named `valueOr` to avoid
      * collision with cats' `recover` which takes `PartialFunction`.
      */
    inline def valueOr(f: E => A)(using Functor[F]): UEff[F, A] =
      Functor[F].map(self) {
        case Left(e)  => Right(f(e))
        case Right(a) => Right(a)
      }

    // --- Conversion Utilities ---

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

    /** Converts to an infallible effect returning `Option[A]`, treating errors as `None`. */
    inline def option(using Functor[F]): UEff[F, Option[A]] =
      Functor[F].map(self)(_.toOption.asRight)

    /** Extracts an inner `Option[B]` value, failing with `ifNone` when absent. */
    inline def collectSome[B](ifNone: => E)(using F: Functor[F])(using ev: A <:< Option[B]): Eff[F, E, B] =
      F.map(self) {
        case Right(a) => ev(a).toRight(ifNone)
        case Left(e)  => Left(e)
      }

    /** Extracts an inner `Either[L, B]` value, mapping left to error via `ifLeft`. */
    inline def collectRight[L, B](ifLeft: L => E)(using F: Functor[F])(using ev: A <:< Either[L, B]): Eff[F, E, B] =
      F.map(self) {
        case Right(a) => ev(a).leftMap(ifLeft)
        case Left(e)  => Left(e)
      }

    // --- Bracket Combinators ---

    /** Acquires a resource, uses it, and ensures release even on failure.
      *
      * The `release` function receives the acquired resource and always executes.
      */
    inline def bracket[B](use: A => Eff[F, E, B])(release: A => F[Unit])(using MC: MonadCancel[F, Throwable]): Eff[F, E, B] =
      MC.bracketCase(self) {
        case Right(a) => use(a)
        case Left(e)  => MC.pure(Left(e))
      } {
        case (Right(a), _) => release(a)
        case (Left(_), _)  => MC.unit
      }

    /** Acquires a resource, uses it, and ensures release with outcome information. */
    inline def bracketCase[B](use: A => Eff[F, E, B])(release: (A, Outcome[F, Throwable, Either[E, B]]) => F[Unit])(using
      MC: MonadCancel[F, Throwable]
    ): Eff[F, E, B] =
      MC.bracketCase(self) {
        case Right(a) => use(a)
        case Left(e)  => MC.pure(Left(e))
      } {
        case (Right(a), oc) => release(a, oc)
        case (Left(_), _)   => MC.unit
      }

    // --- Timeout Combinator ---

    /** Fails with `onTimeout` if the computation does not complete within `duration`. */
    inline def timeout(duration: FiniteDuration, onTimeout: => E)(using T: GenTemporal[F, Throwable]): Eff[F, E, A] =
      Eff.lift(
        T.timeoutTo(
          self.either,
          duration,
          T.pure(Left(onTimeout))
        )
      )
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

  /** Suspends a side effect that produces an `Either[E, A]`.
    *
    * Use this for synchronous side-effecting code that returns typed errors:
    * {{{
    * Eff.delay[IO, MyError, Int](nativeCall.register())
    * }}}
    */
  inline def delay[F[_], E, A](ea: => Either[E, A])(using F: Sync[F]): Eff[F, E, A] =
    F.delay(ea)

  // --- Conditional Execution ---

  /** Executes `eff` only when `cond` is true, otherwise succeeds with `Unit`. */
  inline def when[F[_]: Applicative, E](cond: Boolean)(eff: => Eff[F, E, Unit]): Eff[F, E, Unit] =
    if cond then eff else unit[F, E]

  /** Executes `eff` only when `cond` is false, otherwise succeeds with `Unit`. */
  inline def unless[F[_]: Applicative, E](cond: Boolean)(eff: => Eff[F, E, Unit]): Eff[F, E, Unit] =
    if cond then unit[F, E] else eff

  // --- Collection Operations ---

  /** Traverses a collection, short-circuiting on first error. */
  inline def traverse[F[_]: Monad, E, A, B](as: Iterable[A])(f: A => Eff[F, E, B]): Eff[F, E, List[B]] =
    as.foldLeft(succeed[F, E, List[B]](Nil)) { (acc, a) =>
      acc.flatMap(bs => f(a).map(b => bs :+ b))
    }

  /** Sequences a collection of effects, short-circuiting on first error. */
  inline def sequence[F[_]: Monad, E, A](effs: Iterable[Eff[F, E, A]]): Eff[F, E, List[A]] =
    traverse(effs)(identity)

  /** Traverses a collection in parallel using `F`'s `Parallel` instance. */
  inline def parTraverse[F[_], E, A, B](as: Iterable[A])(f: A => Eff[F, E, B])(using P: Parallel[F]): Eff[F, E, List[B]] =
    val parF = P.applicative
    val results: P.F[List[Either[E, B]]] =
      as.toList.foldRight(parF.pure(List.empty[Either[E, B]])) { (a, acc) =>
        parF.map2(P.parallel(f(a).either), acc)(_ :: _)
      }
    Eff.lift(
      P.monad.map(P.sequential(results)) { eithers =>
        eithers.foldRight[Either[E, List[B]]](Right(Nil)) { (e, acc) =>
          for
            bs <- acc
            b <- e
          yield b :: bs
        }
      }
    )
  end parTraverse

  /** Sequences a collection of effects in parallel. */
  inline def parSequence[F[_]: Parallel, E, A](effs: Iterable[Eff[F, E, A]]): Eff[F, E, List[A]] =
    parTraverse(effs)(identity)

  // --- Retry Utilities ---

  /** Retries the effect up to `maxRetries` times on failure. */
  inline def retry[F[_]: Monad, E, A](eff: Eff[F, E, A], maxRetries: Int): Eff[F, E, A] =
    if maxRetries <= 0 then eff
    else eff.catchAll(_ => retry(eff, maxRetries - 1))

  /** Retries the effect with exponential backoff between attempts.
    *
    * @param eff the effect to retry
    * @param maxRetries maximum number of retry attempts
    * @param initialDelay delay before first retry
    * @param maxDelay optional cap on delay duration
    */
  inline def retryWithBackoff[F[_], E, A](
    eff: Eff[F, E, A],
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Option[FiniteDuration]
  )(using T: GenTemporal[F, Throwable]): Eff[F, E, A] =
    def loop(remaining: Int, delay: FiniteDuration): Eff[F, E, A] =
      if remaining <= 0 then eff
      else
        eff.catchAll { _ =>
          val cappedDelay = maxDelay.fold(delay)(d => delay.min(d))
          Eff.liftF[F, E, Unit](T.sleep(cappedDelay)) *> loop(remaining - 1, delay * 2)
        }
    loop(maxRetries, initialDelay)
  end retryWithBackoff

  // --- Natural Transformation ----------------------------------------------

  /** Creates a `FunctionK` that lifts `F[A]` into `Eff[F, E, A]` treating values as successes.
    *
    * This is the canonical way to transform `Resource[F, A]` and other cats-effect primitives to
    * work with `Eff`.
    */
  inline def functionK[F[_]: Functor, E]: F ~> Of[F, E] =
    FunctionKImpl[F, E](summon[Functor[F]])

  private[effect] class FunctionKImpl[F[_], E] @publicInBinary private[Eff] (
    F: Functor[F]
  ) extends FunctionK[F, Of[F, E]]:
    def apply[A](fa: F[A]): Eff[F, E, A] = liftF(fa)(using F)

  // --- Cats-Effect Primitive Lifts -----------------------------------------

  /** Transforms a `Resource[F, A]` to `Resource[Eff.Of[F, E], A]`. */
  inline def liftResource[F[_], E, A](resource: Resource[F, A])(using
    F: MonadCancel[F, Throwable],
    G: MonadCancel[Of[F, E], Throwable]
  ): Resource[Of[F, E], A] =
    resource.mapK(functionK[F, E])

  /** Transforms a `Ref[F, A]` to `Ref[Eff.Of[F, E], A]`. */
  inline def liftRef[F[_], E, A](ref: Ref[F, A])(using F: Functor[F], G: Functor[Of[F, E]]): Ref[Of[F, E], A] =
    ref.mapK(functionK[F, E])

  /** Transforms a `Deferred[F, A]` to `Deferred[Eff.Of[F, E], A]`. */
  inline def liftDeferred[F[_], E, A](deferred: Deferred[F, A])(using F: Functor[F]): Deferred[Of[F, E], A] =
    deferred.mapK(functionK[F, E])

  /** Transforms a `Queue[F, A]` to `Queue[Eff.Of[F, E], A]`. */
  inline def liftQueue[F[_], E, A](queue: Queue[F, A])(using F: Functor[F]): Queue[Of[F, E], A] =
    queue.mapK(functionK[F, E])

  /** Transforms a `Semaphore[F]` to `Semaphore[Eff.Of[F, E]]`. */
  inline def liftSemaphore[F[_], E](semaphore: Semaphore[F])(using
    F: MonadCancel[F, Throwable],
    G: MonadCancel[Of[F, E], Throwable]
  ): Semaphore[Of[F, E]] =
    semaphore.mapK(functionK[F, E])

  /** Transforms a `CountDownLatch[F]` to `CountDownLatch[Eff.Of[F, E]]`. */
  inline def liftLatch[F[_], E](latch: CountDownLatch[F])(using F: Functor[F]): CountDownLatch[Of[F, E]] =
    latch.mapK(functionK[F, E])

  /** Transforms a `CyclicBarrier[F]` to `CyclicBarrier[Eff.Of[F, E]]`. */
  inline def liftBarrier[F[_], E](barrier: CyclicBarrier[F])(using F: Functor[F]): CyclicBarrier[Of[F, E]] =
    barrier.mapK(functionK[F, E])

  /** Transforms an `AtomicCell[F, A]` to `AtomicCell[Eff.Of[F, E], A]`.
    *
    * Note: `evalModify` operations that result in typed errors will propagate as typed errors in
    * the result, but the cell state remains unchanged when this occurs.
    */
  inline def liftCell[F[_], E, A](cell: AtomicCell[F, A])(using F: Monad[F]): AtomicCell[Of[F, E], A] =
    AtomicCellImpl(cell, F)

  private[effect] class AtomicCellImpl[F[_], E, A] @publicInBinary private[Eff] (
    cell: AtomicCell[F, A],
    F: Monad[F]
  ) extends AtomicCell[Of[F, E], A]:
    def get: Eff[F, E, A] = liftF(cell.get)(using F)
    def set(a: A): Eff[F, E, Unit] = liftF(cell.set(a))(using F)
    def modify[B](f: A => (A, B)): Eff[F, E, B] = liftF(cell.modify(f))(using F)
    def evalModify[B](f: A => Eff[F, E, (A, B)]): Eff[F, E, B] =
      // Use get + conditional set to handle typed errors properly
      Eff.lift(
        F.flatMap(cell.get) { a =>
          F.flatMap(f(a).either) {
            case Right((newA, b)) => F.as(cell.set(newA), Right(b))
            case Left(e)          => F.pure(Left(e))
          }
        }
      )
    def evalUpdate(f: A => Eff[F, E, A]): Eff[F, E, Unit] =
      evalModify(a => f(a).map(aa => (aa, ()))(using F)).void(using F)
    def evalGetAndUpdate(f: A => Eff[F, E, A]): Eff[F, E, A] =
      evalModify(a => f(a).map(aa => (aa, a))(using F))
    def evalUpdateAndGet(f: A => Eff[F, E, A]): Eff[F, E, A] =
      evalModify(a => f(a).map(aa => (aa, aa))(using F))
  end AtomicCellImpl

  /** Transforms a `Supervisor[F]` to `Supervisor[Eff.Of[F, E]]`. */
  inline def liftSupervisor[F[_], E](supervisor: Supervisor[F])(using F: Functor[F]): Supervisor[Of[F, E]] =
    SupervisorImpl(supervisor, F)

  import cats.effect.kernel.Fiber

  private[effect] class FiberImpl[F[_], E, A] @publicInBinary private[Eff] (
    fiber: Fiber[F, Throwable, Either[E, A]],
    F: Functor[F]
  ) extends Fiber[Of[F, E], Throwable, A]:
    def cancel: Eff[F, E, Unit] = liftF(fiber.cancel)(using F)
    def join: Eff[F, E, Outcome[Of[F, E], Throwable, A]] =
      liftF(F.map(fiber.join) {
        case Outcome.Succeeded(fea) =>
          Outcome.succeeded[Of[F, E], Throwable, A](Eff.lift(fea))
        case Outcome.Errored(e) =>
          Outcome.errored[Of[F, E], Throwable, A](e)
        case Outcome.Canceled() =>
          Outcome.canceled[Of[F, E], Throwable, A]
      })(using F)
  end FiberImpl

  private[effect] inline def fiber[F[_], E, A](fib: Fiber[F, Throwable, Either[E, A]], F: Functor[F]): Fiber[Of[F, E], Throwable, A] =
    FiberImpl(fib, F)

  private[effect] class SupervisorImpl[F[_], E] @publicInBinary private[Eff] (
    supervisor: Supervisor[F],
    F: Functor[F]
  ) extends Supervisor[Of[F, E]]:
    def supervise[A](fa: Eff[F, E, A]): Eff[F, E, Fiber[Of[F, E], Throwable, A]] =
      liftF(F.map(supervisor.supervise(fa.either)) { fib =>
        Eff.fiber(fib, F)
      })(using F)

  private[effect] class PollImpl[F[_], E] @publicInBinary private[Eff] (
    pollF: Poll[F]
  ) extends Poll[Of[F, E]]:
    def apply[A](eff: Eff[F, E, A]): Eff[F, E, A] = lift(pollF(unwrapUnsafe(eff)))

  private[effect] inline def poll[F[_], E](pollF: Poll[F]): Poll[Of[F, E]] = PollImpl(pollF)

  private[effect] class NatEffFunctionKImpl[F[_], G[_], E] @publicInBinary private[Eff] (
    lift: F ~> G
  ) extends FunctionK[Of[F, E], Of[G, E]]:
    def apply[X](eff: Eff[F, E, X]): Eff[G, E, X] =
      Eff.lift(lift(unwrapUnsafe(eff)))

  private[effect] inline def natEffFunctionK[F[_], G[_], E](lift: F ~> G): Of[F, E] ~> Of[G, E] =
    NatEffFunctionKImpl(lift)

  private[effect] class ContImpl[F[_], E0, K, R] @publicInBinary private[Eff] (
    body: Cont[Of[F, E0], K, R]
  ) extends Cont[F, K, Either[E0, R]]:
    def apply[G[_]](using G: MonadCancel[G, Throwable]): (Either[Throwable, K] => Unit, G[K], F ~> G) => G[Either[E0, R]] =
      (resume, get, lift) => Eff.unwrapUnsafe(body[Of[G, E0]].apply(resume, Eff.liftF(get)(using G), natEffFunctionK(lift)))

  private[effect] inline def contImpl[F[_], E0, K, R](body: Cont[Of[F, E0], K, R]): Cont[F, K, Either[E0, R]] =
    ContImpl(body)

  private[effect] class ParallelSequentialImpl[M[_], E, PF[_]] @publicInBinary private[Eff] (
    seq: PF ~> M
  ) extends FunctionK[Nested[PF, Either[E, *], *], Of[M, E]]:
    def apply[A](nested: Nested[PF, Either[E, *], A]): Eff[M, E, A] =
      lift(seq(nested.value))

  private[effect] class ParallelParImpl[M[_], E, PF[_]] @publicInBinary private[Eff] (
    par: M ~> PF
  ) extends FunctionK[Of[M, E], Nested[PF, Either[E, *], *]]:
    def apply[A](eff: Eff[M, E, A]): Nested[PF, Either[E, *], A] =
      Nested(par(eff.either))

  // ---------------------------------------------------------------------------
  // Typeclass Instances
  // ---------------------------------------------------------------------------

  /** Inherits `Functor` from the base effect, lifting over the error channel. */
  given [F[_]: Functor, E] => Functor[Of[F, E]]:
    def map[A, B](fa: Eff[F, E, A])(f: A => B): Eff[F, E, B] = fa.map(f)

  /** `Bifunctor` instance enabling `bimap` and `leftMap` on both error and success channels.
    *
    * Provides the canonical bifunctor operations via cats syntax.
    */
  given [F[_]: Functor] => Bifunctor[[E, A] =>> Eff[F, E, A]]:
    def bimap[A, B, C, D](fab: Eff[F, A, B])(f: A => C, g: B => D): Eff[F, C, D] =
      Functor[F].map(fab)(_.bimap(f, g))

  /** `Monad` instance mirroring the `Either` structure with typed errors. */
  given [F[_]: Monad, E] => Monad[Of[F, E]]:
    def pure[A](a: A): Eff[F, E, A] = succeed(a)

    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      Monad[F].tailRecM(a) { current =>
        Functor[F].map(f(current).either) {
          case Left(e)            => Right(Left(e))
          case Right(Left(next))  => Left(next)
          case Right(Right(done)) => Right(Right(done))
        }
      }
  end given

  /** Canonical `MonadError` for the typed error channel.
    *
    * This instance handles the typed error channel `E`, providing `recover`, `ensure`, etc.
    */
  given [F[_]: Monad, E] => MonadError[Of[F, E], E]:
    def pure[A](a: A): Eff[F, E, A] = succeed(a)

    def flatMap[A, B](fa: Eff[F, E, A])(f: A => Eff[F, E, B]): Eff[F, E, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[F, E, Either[A, B]]): Eff[F, E, B] =
      summon[Monad[Of[F, E]]].tailRecM(a)(f)

    def raiseError[A](e: E): Eff[F, E, A] = fail(e)

    def handleErrorWith[A](fa: Eff[F, E, A])(f: E => Eff[F, E, A]): Eff[F, E, A] =
      fa.catchAll(f)
  end given

  /** `Parallel` instance enabling `parMapN`, `parTraverse`, etc.
    *
    * Derives parallel behaviour from `F`'s `Parallel` instance. The parallel applicative uses
    * `Nested[P.F, Either[E, *], *]` which applies `F`'s parallelism whilst preserving short-circuit
    * semantics on the first error encountered.
    */
  given [M[_], E] => (P: Parallel[M]) => Parallel[Of[M, E]]:
    type F[x] = Nested[P.F, Either[E, *], x]

    private val eitherApplicative: Applicative[Either[E, *]] =
      cats.instances.either.catsStdInstancesForEither[E]

    val applicative: Applicative[F] =
      Nested.catsDataApplicativeForNested[P.F, Either[E, *]](using P.applicative, eitherApplicative)

    val monad: Monad[Of[M, E]] =
      given Monad[M] = P.monad
      summon[Monad[Of[M, E]]]

    val sequential: F ~> Of[M, E] =
      ParallelSequentialImpl(P.sequential)

    val parallel: Of[M, E] ~> F =
      ParallelParImpl(P.parallel)
  end given

  /** Defers evaluation of `Eff` computations until demanded.
    *
    * Enables safe recursive definitions and lazy evaluation patterns.
    */
  given [F[_], E] => (D: Defer[F]) => Defer[Of[F, E]]:
    def defer[A](fa: => Eff[F, E, A]): Eff[F, E, A] =
      lift(D.defer(fa.either))

  /** Provides time measurement capabilities for `Eff` computations.
    *
    * Delegates to the underlying `Clock[F]` instance, lifting results as successes.
    */
  given [F[_], E] => (C: Clock[F], F: Monad[F]) => Clock[Of[F, E]]:
    val applicative: Applicative[Of[F, E]] = summon[Monad[Of[F, E]]]

    def monotonic: Eff[F, E, FiniteDuration] = liftF(C.monotonic)

    def realTime: Eff[F, E, FiniteDuration] = liftF(C.realTime)

  /** Provides unique token generation for `Eff` computations.
    *
    * Useful for fibre identification and other concurrency primitives.
    */
  given [F[_], E] => (U: Unique[F], F: Monad[F]) => Unique[Of[F, E]]:
    val applicative: Applicative[Of[F, E]] = summon[Monad[Of[F, E]]]

    def unique: Eff[F, E, Unique.Token] = liftF(U.unique)

  /** Provides choice/alternative semantics for `Eff` computations.
    *
    * `combineK` tries the first computation; if it fails with a typed error, falls back to the
    * second. This is equivalent to the `alt` extension method but via the `SemigroupK` typeclass.
    */
  given [F[_]: Monad, E] => SemigroupK[Of[F, E]]:
    def combineK[A](x: Eff[F, E, A], y: Eff[F, E, A]): Eff[F, E, A] =
      x.alt(y)

  /** Combines two `Eff` computations when both succeed, using `Semigroup` to combine values.
    *
    * If either computation fails, the error propagates. Both computations must succeed for the
    * combination to occur.
    */
  given [F[_]: Monad, E, A] => (S: Semigroup[A]) => Semigroup[Eff[F, E, A]]:
    def combine(x: Eff[F, E, A], y: Eff[F, E, A]): Eff[F, E, A] =
      x.flatMap(a => y.map(b => S.combine(a, b)))

  /** Combines `Eff` computations with an identity element.
    *
    * `empty` produces a successful computation with `Monoid[A].empty`. Combining follows
    * `Semigroup` semantics where both must succeed for values to be combined.
    */
  given [F[_]: Monad, E, A] => (M: Monoid[A]) => Monoid[Eff[F, E, A]]:
    def empty: Eff[F, E, A] = succeed(M.empty)

    def combine(x: Eff[F, E, A], y: Eff[F, E, A]): Eff[F, E, A] =
      x.flatMap(a => y.map(b => M.combine(a, b)))

  // ---------------------------------------------------------------------------
  // Data Typeclass Instances
  // ---------------------------------------------------------------------------

  /** `Show` instance for `Eff` delegating to the underlying `Show[F[Either[E, A]]]`.
    *
    * This enables textual representation of `Eff` values via the `show` method.
    */
  given [F[_], E, A] => (S: Show[F[Either[E, A]]]) => Show[Eff[F, E, A]]:
    def show(fa: Eff[F, E, A]): String = S.show(fa.either)

  /** `Eq` instance for `Eff` delegating to the underlying `Eq[F[Either[E, A]]]`.
    *
    * Equality is determined by the underlying effectful `Either` representation.
    */
  given [F[_], E, A] => (EQ: Eq[F[Either[E, A]]]) => Eq[Eff[F, E, A]]:
    def eqv(x: Eff[F, E, A], y: Eff[F, E, A]): Boolean =
      EQ.eqv(x.either, y.either)

  /** `PartialOrder` instance for `Eff` delegating to the underlying
    * `PartialOrder[F[Either[E, A]]]`.
    *
    * Ordering is determined by the underlying effectful `Either` representation.
    */
  given [F[_], E, A] => (PO: PartialOrder[F[Either[E, A]]]) => PartialOrder[Eff[F, E, A]]:
    def partialCompare(x: Eff[F, E, A], y: Eff[F, E, A]): Double =
      PO.partialCompare(x.either, y.either)

  /** `Foldable` instance for `Eff` when `F` is `Foldable`.
    *
    * Folds over the success channel only, treating errors as empty.
    */
  given [F[_], E] => (FO: Foldable[F]) => Foldable[Of[F, E]]:
    def foldLeft[A, B](fa: Eff[F, E, A], b: B)(f: (B, A) => B): B =
      FO.foldLeft(fa.either, b) { (acc, eea) =>
        eea.fold(_ => acc, a => f(acc, a))
      }

    def foldRight[A, B](fa: Eff[F, E, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      FO.foldRight(fa.either, lb) { (eea, acc) =>
        eea.fold(_ => acc, a => f(a, acc))
      }

  /** `Traverse` instance for `Eff` when `F` is `Traverse`.
    *
    * Traverses over the success channel, passing errors through unchanged.
    */
  given [F[_], E] => (TR: Traverse[F]) => Traverse[Of[F, E]]:
    def foldLeft[A, B](fa: Eff[F, E, A], b: B)(f: (B, A) => B): B =
      TR.foldLeft(fa.either, b) { (acc, eea) =>
        eea.fold(_ => acc, a => f(acc, a))
      }

    def foldRight[A, B](fa: Eff[F, E, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      TR.foldRight(fa.either, lb) { (eea, acc) =>
        eea.fold(_ => acc, a => f(a, acc))
      }

    def traverse[G[_]: Applicative, A, B](fa: Eff[F, E, A])(f: A => G[B]): G[Eff[F, E, B]] =
      Applicative[G].map(
        TR.traverse(fa.either)(_.traverse(f))
      )(Eff.lift)
  end given

  /** `Bifoldable` instance for `Eff` when `F` is `Foldable`.
    *
    * Folds over both error and success channels using `Bifoldable[Either]`.
    */
  given [F[_]] => (FO: Foldable[F]) => Bifoldable[[E, A] =>> Eff[F, E, A]]:
    def bifoldLeft[A, B, C](fab: Eff[F, A, B], c: C)(f: (C, A) => C, g: (C, B) => C): C =
      FO.foldLeft(fab.either, c) { (acc, eab) =>
        Bifoldable[Either].bifoldLeft(eab, acc)(f, g)
      }

    def bifoldRight[A, B, C](fab: Eff[F, A, B], c: Eval[C])(
      f: (A, Eval[C]) => Eval[C],
      g: (B, Eval[C]) => Eval[C]
    ): Eval[C] =
      FO.foldRight(fab.either, c) { (eab, acc) =>
        Bifoldable[Either].bifoldRight(eab, acc)(f, g)
      }
  end given

  /** `Bitraverse` instance for `Eff` when `F` is `Traverse`.
    *
    * Traverses over both error and success channels using `Bitraverse[Either]`.
    */
  given [F[_]] => (TR: Traverse[F]) => Bitraverse[[E, A] =>> Eff[F, E, A]]:
    def bifoldLeft[A, B, C](fab: Eff[F, A, B], c: C)(f: (C, A) => C, g: (C, B) => C): C =
      TR.foldLeft(fab.either, c) { (acc, eab) =>
        Bifoldable[Either].bifoldLeft(eab, acc)(f, g)
      }

    def bifoldRight[A, B, C](fab: Eff[F, A, B], c: Eval[C])(
      f: (A, Eval[C]) => Eval[C],
      g: (B, Eval[C]) => Eval[C]
    ): Eval[C] =
      TR.foldRight(fab.either, c) { (eab, acc) =>
        Bifoldable[Either].bifoldRight(eab, acc)(f, g)
      }

    def bitraverse[G[_]: Applicative, A, B, C, D](fab: Eff[F, A, B])(
      f: A => G[C],
      g: B => G[D]
    ): G[Eff[F, C, D]] =
      Applicative[G].map(
        TR.traverse(fab.either)(Bitraverse[Either].bitraverse(_)(f, g))
      )(Eff.lift)
  end given
end Eff
