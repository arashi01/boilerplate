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

import cats.Applicative
import cats.Bifunctor
import cats.Defer
import cats.Functor
import cats.Monad
import cats.MonadError
import cats.Monoid
import cats.Parallel
import cats.Semigroup
import cats.SemigroupK
import cats.arrow.FunctionK
import cats.data.EitherT
import cats.data.Kleisli
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
import cats.effect.kernel.Sync
import cats.effect.kernel.Unique
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

/** Low-priority `GenSpawn` instance for fibre spawning and racing. */
private[effect] trait EffRInstancesLowPriority5:
  import EffR.Of
  import EffR.wrapUnsafe
  import EffR.unwrapUnsafe

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Provides fiber spawning and racing for `EffR` computations. */
  given [F[_], R, E0] => (S: GenSpawn[Base[F, E0], Throwable]) => GenSpawn[Of[F, R, E0], Throwable]:
    def pure[A](a: A): EffR[F, R, E0, A] = wrapUnsafe((_: R) => S.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E0, A])(f: A => EffR[F, R, E0, B]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => S.flatMap(unwrapUnsafe(fa)(r))(a => unwrapUnsafe(f(a))(r)))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E0, Either[A, B]]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => S.tailRecM(a)(a0 => unwrapUnsafe(f(a0))(r)))

    def raiseError[A](e: Throwable): EffR[F, R, E0, A] =
      wrapUnsafe((_: R) => S.raiseError(e))

    def handleErrorWith[A](fa: EffR[F, R, E0, A])(f: Throwable => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => S.handleErrorWith(unwrapUnsafe(fa)(r))(e => unwrapUnsafe(f(e))(r)))

    def canceled: EffR[F, R, E0, Unit] = wrapUnsafe((_: R) => S.canceled)

    def onCancel[A](fa: EffR[F, R, E0, A], fin: EffR[F, R, E0, Unit]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => S.onCancel(unwrapUnsafe(fa)(r), S.void(unwrapUnsafe(fin)(r))))

    def forceR[A, B](fa: EffR[F, R, E0, A])(fb: EffR[F, R, E0, B]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => S.forceR(unwrapUnsafe(fa)(r))(unwrapUnsafe(fb)(r)))

    def uncancelable[A](body: Poll[Of[F, R, E0]] => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) =>
        S.uncancelable { pollF =>
          unwrapUnsafe(body(EffR.pollEffR(pollF)))(r)
        }
      )

    override def guaranteeCase[A](fa: EffR[F, R, E0, A])(
      fin: Outcome[Of[F, R, E0], Throwable, A] => EffR[F, R, E0, Unit]
    ): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) =>
        S.guaranteeCase(unwrapUnsafe(fa)(r)) { outcome =>
          val liftedOutcome: Outcome[Of[F, R, E0], Throwable, A] = outcome match
            case Outcome.Succeeded(success) =>
              Outcome.succeeded[Of[F, R, E0], Throwable, A](wrapUnsafe((_: R) => success))
            case Outcome.Errored(err) =>
              Outcome.errored[Of[F, R, E0], Throwable, A](err)
            case Outcome.Canceled() =>
              Outcome.canceled[Of[F, R, E0], Throwable, A]
          S.void(unwrapUnsafe(fin(liftedOutcome))(r))
        }
      )

    override def applicative: Applicative[Of[F, R, E0]] = this

    def unique: EffR[F, R, E0, Unique.Token] = wrapUnsafe((_: R) => S.unique)

    def start[A](fa: EffR[F, R, E0, A]): EffR[F, R, E0, Fiber[Of[F, R, E0], Throwable, A]] =
      wrapUnsafe((r: R) => S.map(S.start(unwrapUnsafe(fa)(r)))(liftFiber))

    def never[A]: EffR[F, R, E0, A] = wrapUnsafe((_: R) => S.never)

    def cede: EffR[F, R, E0, Unit] = wrapUnsafe((_: R) => S.cede)

    def racePair[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[
        (Outcome[Of[F, R, E0], Throwable, A], Fiber[Of[F, R, E0], Throwable, B]),
        (Fiber[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
      ]
    ] =
      wrapUnsafe((r: R) =>
        S.uncancelable(poll =>
          S.map(poll(S.racePair(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))) {
            case Left((oc, fib))  => Left((liftOutcome(oc), liftFiber(fib)))
            case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
          }
        )
      )

    override def race[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, Either[A, B]] =
      wrapUnsafe((r: R) => S.race(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))

    override def both[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, (A, B)] =
      wrapUnsafe((r: R) => S.both(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))

    override def raceOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B]]
    ] =
      wrapUnsafe((r: R) => S.map(S.raceOutcome(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))(_.bimap(liftOutcome, liftOutcome)))

    override def bothOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      (Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
    ] =
      wrapUnsafe((r: R) => S.map(S.bothOutcome(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))(_.bimap(liftOutcome, liftOutcome)))

    private def liftOutcome[A](oc: Outcome[Base[F, E0], Throwable, A]): Outcome[Of[F, R, E0], Throwable, A] =
      oc match
        case Outcome.Canceled()    => Outcome.Canceled()
        case Outcome.Errored(e)    => Outcome.Errored(e)
        case Outcome.Succeeded(fa) => Outcome.Succeeded(wrapUnsafe((_: R) => fa))

    private def liftFiber[A](fib: Fiber[Base[F, E0], Throwable, A]): Fiber[Of[F, R, E0], Throwable, A] =
      EffR.fiberEffR(fib, liftOutcome, S)
  end given
end EffRInstancesLowPriority5

/** Low-priority `GenConcurrent` instance for concurrent primitives. */
private[effect] trait EffRInstancesLowPriority4 extends EffRInstancesLowPriority5:
  import EffR.Of
  import EffR.wrapUnsafe

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Provides concurrent primitives for `EffR` computations. */
  given [F[_], R, E0] => (C: GenConcurrent[Base[F, E0], Throwable]) => GenConcurrent[Of[F, R, E0], Throwable]:
    private val spawn = summon[GenSpawn[Of[F, R, E0], Throwable]]

    def pure[A](a: A): EffR[F, R, E0, A] = spawn.pure(a)
    def flatMap[A, B](fa: EffR[F, R, E0, A])(f: A => EffR[F, R, E0, B]): EffR[F, R, E0, B] = spawn.flatMap(fa)(f)
    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E0, Either[A, B]]): EffR[F, R, E0, B] = spawn.tailRecM(a)(f)
    def raiseError[A](e: Throwable): EffR[F, R, E0, A] = spawn.raiseError(e)
    def handleErrorWith[A](fa: EffR[F, R, E0, A])(f: Throwable => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      spawn.handleErrorWith(fa)(f)
    def canceled: EffR[F, R, E0, Unit] = spawn.canceled
    def onCancel[A](fa: EffR[F, R, E0, A], fin: EffR[F, R, E0, Unit]): EffR[F, R, E0, A] = spawn.onCancel(fa, fin)
    def forceR[A, B](fa: EffR[F, R, E0, A])(fb: EffR[F, R, E0, B]): EffR[F, R, E0, B] = spawn.forceR(fa)(fb)
    def uncancelable[A](body: Poll[Of[F, R, E0]] => EffR[F, R, E0, A]): EffR[F, R, E0, A] = spawn.uncancelable(body)
    override def guaranteeCase[A](fa: EffR[F, R, E0, A])(
      fin: Outcome[Of[F, R, E0], Throwable, A] => EffR[F, R, E0, Unit]
    ): EffR[F, R, E0, A] = spawn.guaranteeCase(fa)(fin)
    override def applicative: Applicative[Of[F, R, E0]] = this
    def unique: EffR[F, R, E0, Unique.Token] = spawn.unique
    def start[A](fa: EffR[F, R, E0, A]): EffR[F, R, E0, Fiber[Of[F, R, E0], Throwable, A]] = spawn.start(fa)
    def never[A]: EffR[F, R, E0, A] = spawn.never
    def cede: EffR[F, R, E0, Unit] = spawn.cede
    override def racePair[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[
        (Outcome[Of[F, R, E0], Throwable, A], Fiber[Of[F, R, E0], Throwable, B]),
        (Fiber[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
      ]
    ] = spawn.racePair(fa, fb)
    override def race[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, Either[A, B]] =
      spawn.race(fa, fb)
    override def both[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, (A, B)] =
      spawn.both(fa, fb)
    override def raceOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B]]
    ] = spawn.raceOutcome(fa, fb)
    override def bothOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      (Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
    ] = spawn.bothOutcome(fa, fb)

    def ref[A](a: A): EffR[F, R, E0, Ref[Of[F, R, E0], A]] =
      wrapUnsafe((_: R) => C.map(C.ref(a))(_.mapK(EffR.readerFunctionK)))

    def deferred[A]: EffR[F, R, E0, Deferred[Of[F, R, E0], A]] =
      wrapUnsafe((_: R) => C.map(C.deferred[A])(_.mapK(EffR.readerFunctionK)))
  end given
end EffRInstancesLowPriority4

/** Low-priority `GenTemporal` instance for temporal operations. */
private[effect] trait EffRInstancesLowPriority3 extends EffRInstancesLowPriority4:
  import EffR.Of
  import EffR.wrapUnsafe

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Provides temporal primitives for `EffR` computations. */
  given [F[_], R, E0] => (T: GenTemporal[Base[F, E0], Throwable]) => GenTemporal[Of[F, R, E0], Throwable]:
    private val concurrent = summon[GenConcurrent[Of[F, R, E0], Throwable]]

    def pure[A](a: A): EffR[F, R, E0, A] = concurrent.pure(a)
    def flatMap[A, B](fa: EffR[F, R, E0, A])(f: A => EffR[F, R, E0, B]): EffR[F, R, E0, B] = concurrent.flatMap(fa)(f)
    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E0, Either[A, B]]): EffR[F, R, E0, B] = concurrent.tailRecM(a)(f)
    def raiseError[A](e: Throwable): EffR[F, R, E0, A] = concurrent.raiseError(e)
    def handleErrorWith[A](fa: EffR[F, R, E0, A])(f: Throwable => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      concurrent.handleErrorWith(fa)(f)
    def canceled: EffR[F, R, E0, Unit] = concurrent.canceled
    def onCancel[A](fa: EffR[F, R, E0, A], fin: EffR[F, R, E0, Unit]): EffR[F, R, E0, A] = concurrent.onCancel(fa, fin)
    def forceR[A, B](fa: EffR[F, R, E0, A])(fb: EffR[F, R, E0, B]): EffR[F, R, E0, B] = concurrent.forceR(fa)(fb)
    def uncancelable[A](body: Poll[Of[F, R, E0]] => EffR[F, R, E0, A]): EffR[F, R, E0, A] = concurrent.uncancelable(body)
    override def guaranteeCase[A](fa: EffR[F, R, E0, A])(
      fin: Outcome[Of[F, R, E0], Throwable, A] => EffR[F, R, E0, Unit]
    ): EffR[F, R, E0, A] = concurrent.guaranteeCase(fa)(fin)
    override def applicative: Applicative[Of[F, R, E0]] = this
    def unique: EffR[F, R, E0, Unique.Token] = concurrent.unique
    def start[A](fa: EffR[F, R, E0, A]): EffR[F, R, E0, Fiber[Of[F, R, E0], Throwable, A]] = concurrent.start(fa)
    override def never[A]: EffR[F, R, E0, A] = concurrent.never
    def cede: EffR[F, R, E0, Unit] = concurrent.cede
    override def racePair[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[
        (Outcome[Of[F, R, E0], Throwable, A], Fiber[Of[F, R, E0], Throwable, B]),
        (Fiber[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
      ]
    ] = concurrent.racePair(fa, fb)
    override def race[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, Either[A, B]] =
      concurrent.race(fa, fb)
    override def both[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, (A, B)] =
      concurrent.both(fa, fb)
    override def raceOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B]]
    ] = concurrent.raceOutcome(fa, fb)
    override def bothOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      (Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
    ] = concurrent.bothOutcome(fa, fb)
    def ref[A](a: A): EffR[F, R, E0, Ref[Of[F, R, E0], A]] = concurrent.ref(a)
    def deferred[A]: EffR[F, R, E0, Deferred[Of[F, R, E0], A]] = concurrent.deferred

    def monotonic: EffR[F, R, E0, FiniteDuration] = wrapUnsafe((_: R) => T.monotonic)

    def realTime: EffR[F, R, E0, FiniteDuration] = wrapUnsafe((_: R) => T.realTime)

    protected def sleep(time: FiniteDuration): EffR[F, R, E0, Unit] =
      wrapUnsafe((_: R) => T.sleep(time))
  end given
end EffRInstancesLowPriority3

/** Low-priority `Sync` instance for synchronous effect suspension. */
private[effect] trait EffRInstancesLowPriority2 extends EffRInstancesLowPriority3:
  import EffR.Of
  import EffR.wrapUnsafe
  import EffR.unwrapUnsafe

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Provides synchronous effect suspension for `EffR` computations. */
  given [F[_], R, E0] => (S: Sync[Base[F, E0]]) => Sync[Of[F, R, E0]]:
    def pure[A](a: A): EffR[F, R, E0, A] = wrapUnsafe((_: R) => S.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E0, A])(f: A => EffR[F, R, E0, B]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => S.flatMap(unwrapUnsafe(fa)(r))(a => unwrapUnsafe(f(a))(r)))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E0, Either[A, B]]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => S.tailRecM(a)(a0 => unwrapUnsafe(f(a0))(r)))

    def raiseError[A](e: Throwable): EffR[F, R, E0, A] =
      wrapUnsafe((_: R) => S.raiseError(e))

    def handleErrorWith[A](fa: EffR[F, R, E0, A])(f: Throwable => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => S.handleErrorWith(unwrapUnsafe(fa)(r))(e => unwrapUnsafe(f(e))(r)))

    def canceled: EffR[F, R, E0, Unit] = wrapUnsafe((_: R) => S.canceled)

    def onCancel[A](fa: EffR[F, R, E0, A], fin: EffR[F, R, E0, Unit]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => S.onCancel(unwrapUnsafe(fa)(r), S.void(unwrapUnsafe(fin)(r))))

    def forceR[A, B](fa: EffR[F, R, E0, A])(fb: EffR[F, R, E0, B]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => S.forceR(unwrapUnsafe(fa)(r))(unwrapUnsafe(fb)(r)))

    def uncancelable[A](body: Poll[Of[F, R, E0]] => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) =>
        S.uncancelable { pollF =>
          unwrapUnsafe(body(EffR.pollEffR(pollF)))(r)
        }
      )

    override def guaranteeCase[A](fa: EffR[F, R, E0, A])(
      fin: Outcome[Of[F, R, E0], Throwable, A] => EffR[F, R, E0, Unit]
    ): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) =>
        S.guaranteeCase(unwrapUnsafe(fa)(r)) { outcome =>
          val liftedOutcome: Outcome[Of[F, R, E0], Throwable, A] = outcome match
            case Outcome.Succeeded(success) =>
              Outcome.succeeded[Of[F, R, E0], Throwable, A](wrapUnsafe((_: R) => success))
            case Outcome.Errored(err) =>
              Outcome.errored[Of[F, R, E0], Throwable, A](err)
            case Outcome.Canceled() =>
              Outcome.canceled[Of[F, R, E0], Throwable, A]
          S.void(unwrapUnsafe(fin(liftedOutcome))(r))
        }
      )

    def rootCancelScope = S.rootCancelScope

    def monotonic: EffR[F, R, E0, FiniteDuration] = wrapUnsafe((_: R) => S.monotonic)

    def realTime: EffR[F, R, E0, FiniteDuration] = wrapUnsafe((_: R) => S.realTime)

    override def unique: EffR[F, R, E0, Unique.Token] = wrapUnsafe((_: R) => S.unique)

    def suspend[A](hint: Sync.Type)(thunk: => A): EffR[F, R, E0, A] =
      wrapUnsafe((_: R) => S.suspend(hint)(thunk))
  end given
end EffRInstancesLowPriority2

/** Low-priority `Async` instance for asynchronous effects. */
private[effect] trait EffRInstancesLowPriority1 extends EffRInstancesLowPriority2:
  import EffR.Of
  import EffR.wrapUnsafe
  import EffR.unwrapUnsafe

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Provides asynchronous effect capabilities for `EffR` computations.
    *
    * Implements all methods directly using the base `Async[Eff.Of[F, E0]]` instance, lifting
    * through the reader with `wrapUnsafe`/`unwrapUnsafe`.
    */
  given [F[_], R, E0] => (A: Async[Base[F, E0]]) => Async[Of[F, R, E0]]:
    // --- MonadCancel delegation ---

    def pure[A](a: A): EffR[F, R, E0, A] = wrapUnsafe((_: R) => A.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E0, A])(f: A => EffR[F, R, E0, B]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => A.flatMap(unwrapUnsafe(fa)(r))(a => unwrapUnsafe(f(a))(r)))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E0, Either[A, B]]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => A.tailRecM(a)(a0 => unwrapUnsafe(f(a0))(r)))

    def raiseError[A](e: Throwable): EffR[F, R, E0, A] =
      wrapUnsafe((_: R) => A.raiseError(e))

    def handleErrorWith[A](fa: EffR[F, R, E0, A])(f: Throwable => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => A.handleErrorWith(unwrapUnsafe(fa)(r))(e => unwrapUnsafe(f(e))(r)))

    def canceled: EffR[F, R, E0, Unit] = wrapUnsafe((_: R) => A.canceled)

    def onCancel[A](fa: EffR[F, R, E0, A], fin: EffR[F, R, E0, Unit]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => A.onCancel(unwrapUnsafe(fa)(r), A.void(unwrapUnsafe(fin)(r))))

    def forceR[A, B](fa: EffR[F, R, E0, A])(fb: EffR[F, R, E0, B]): EffR[F, R, E0, B] =
      wrapUnsafe((r: R) => A.forceR(unwrapUnsafe(fa)(r))(unwrapUnsafe(fb)(r)))

    def uncancelable[A](body: Poll[Of[F, R, E0]] => EffR[F, R, E0, A]): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) =>
        A.uncancelable { pollF =>
          unwrapUnsafe(body(EffR.pollEffR(pollF)))(r)
        }
      )

    override def guaranteeCase[A](fa: EffR[F, R, E0, A])(
      fin: Outcome[Of[F, R, E0], Throwable, A] => EffR[F, R, E0, Unit]
    ): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) =>
        A.guaranteeCase(unwrapUnsafe(fa)(r)) { outcome =>
          val liftedOutcome: Outcome[Of[F, R, E0], Throwable, A] = outcome match
            case Outcome.Succeeded(success) =>
              Outcome.succeeded[Of[F, R, E0], Throwable, A](wrapUnsafe((_: R) => success))
            case Outcome.Errored(err) =>
              Outcome.errored[Of[F, R, E0], Throwable, A](err)
            case Outcome.Canceled() =>
              Outcome.canceled[Of[F, R, E0], Throwable, A]
          A.void(unwrapUnsafe(fin(liftedOutcome))(r))
        }
      )

    // --- Unique methods ---

    override def applicative: Applicative[Of[F, R, E0]] = this

    override def unique: EffR[F, R, E0, Unique.Token] = wrapUnsafe((_: R) => A.unique)

    // --- GenSpawn methods ---

    def start[A](fa: EffR[F, R, E0, A]): EffR[F, R, E0, Fiber[Of[F, R, E0], Throwable, A]] =
      wrapUnsafe((r: R) => A.map(A.start(unwrapUnsafe(fa)(r)))(liftFiber))

    override def never[A]: EffR[F, R, E0, A] = wrapUnsafe((_: R) => A.never)

    def cede: EffR[F, R, E0, Unit] = wrapUnsafe((_: R) => A.cede)

    override def racePair[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[
        (Outcome[Of[F, R, E0], Throwable, A], Fiber[Of[F, R, E0], Throwable, B]),
        (Fiber[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
      ]
    ] =
      wrapUnsafe((r: R) =>
        A.uncancelable(poll =>
          A.map(poll(A.racePair(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))) {
            case Left((oc, fib))  => Left((liftOutcome(oc), liftFiber(fib)))
            case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
          }
        )
      )

    override def race[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, Either[A, B]] =
      wrapUnsafe((r: R) => A.race(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))

    override def both[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[F, R, E0, (A, B)] =
      wrapUnsafe((r: R) => A.both(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))

    override def raceOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      Either[Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B]]
    ] =
      wrapUnsafe((r: R) => A.map(A.raceOutcome(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))(_.bimap(liftOutcome, liftOutcome)))

    override def bothOutcome[A, B](fa: EffR[F, R, E0, A], fb: EffR[F, R, E0, B]): EffR[
      F,
      R,
      E0,
      (Outcome[Of[F, R, E0], Throwable, A], Outcome[Of[F, R, E0], Throwable, B])
    ] =
      wrapUnsafe((r: R) => A.map(A.bothOutcome(unwrapUnsafe(fa)(r), unwrapUnsafe(fb)(r)))(_.bimap(liftOutcome, liftOutcome)))

    // --- GenConcurrent methods ---

    def ref[A](a: A): EffR[F, R, E0, Ref[Of[F, R, E0], A]] =
      wrapUnsafe((_: R) => A.map(A.ref(a))(_.mapK(EffR.readerFunctionK)))

    def deferred[A]: EffR[F, R, E0, Deferred[Of[F, R, E0], A]] =
      wrapUnsafe((_: R) => A.map(A.deferred[A])(_.mapK(EffR.readerFunctionK)))

    // --- Clock methods ---

    def monotonic: EffR[F, R, E0, FiniteDuration] = wrapUnsafe((_: R) => A.monotonic)

    def realTime: EffR[F, R, E0, FiniteDuration] = wrapUnsafe((_: R) => A.realTime)

    // --- GenTemporal methods ---

    def sleep(time: FiniteDuration): EffR[F, R, E0, Unit] = wrapUnsafe((_: R) => A.sleep(time))

    // --- Sync methods ---

    def suspend[A](hint: Sync.Type)(thunk: => A): EffR[F, R, E0, A] =
      wrapUnsafe((_: R) => A.suspend(hint)(thunk))

    // --- Async methods ---

    def cont[K, V](body: Cont[Of[F, R, E0], K, V]): EffR[F, R, E0, V] =
      wrapUnsafe((r: R) => A.cont(EffR.contEffR(body, r)))

    def evalOn[A](fa: EffR[F, R, E0, A], ec: ExecutionContext): EffR[F, R, E0, A] =
      wrapUnsafe((r: R) => A.evalOn(unwrapUnsafe(fa)(r), ec))

    def executionContext: EffR[F, R, E0, ExecutionContext] =
      wrapUnsafe((_: R) => A.executionContext)

    // --- Private helpers ---

    private def liftOutcome[A](oc: Outcome[Base[F, E0], Throwable, A]): Outcome[Of[F, R, E0], Throwable, A] =
      oc match
        case Outcome.Canceled()    => Outcome.Canceled()
        case Outcome.Errored(e)    => Outcome.Errored(e)
        case Outcome.Succeeded(fa) => Outcome.Succeeded(wrapUnsafe((_: R) => fa))

    private def liftFiber[A](fib: Fiber[Base[F, E0], Throwable, A]): Fiber[Of[F, R, E0], Throwable, A] =
      EffR.fiberEffR(fib, liftOutcome, A)
  end given
end EffRInstancesLowPriority1

/** Low-priority defect-channel `MonadCancel` instance. */
private[effect] trait EffRInstancesLowPriority0 extends EffRInstancesLowPriority1:
  import EffR.Of
  import EffR.wrapUnsafe
  import EffR.unwrapUnsafe

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Extends cancellation support from `Eff` into the reader layer. */
  given [F[_], R, E, EE] => (MC: MonadCancel[Base[F, E], EE]) => MonadCancel[Of[F, R, E], EE]:
    def rootCancelScope = MC.rootCancelScope

    def pure[A](a: A): EffR[F, R, E, A] =
      wrapUnsafe((_: R) => MC.pure(a))

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      wrapUnsafe((r: R) => MC.flatMap(unwrapUnsafe(fa)(r))(a => unwrapUnsafe(f(a))(r)))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      wrapUnsafe((r: R) => MC.tailRecM(a)(a0 => unwrapUnsafe(f(a0))(r)))

    def raiseError[A](e: EE): EffR[F, R, E, A] =
      wrapUnsafe((_: R) => MC.raiseError(e))

    def handleErrorWith[A](fa: EffR[F, R, E, A])(f: EE => EffR[F, R, E, A]): EffR[F, R, E, A] =
      wrapUnsafe((r: R) => MC.handleErrorWith(unwrapUnsafe(fa)(r))(e => unwrapUnsafe(f(e))(r)))

    def canceled: EffR[F, R, E, Unit] =
      wrapUnsafe((_: R) => MC.canceled)

    def onCancel[A](fa: EffR[F, R, E, A], fin: EffR[F, R, E, Unit]): EffR[F, R, E, A] =
      wrapUnsafe((r: R) => MC.onCancel(unwrapUnsafe(fa)(r), MC.void(unwrapUnsafe(fin)(r))))

    def forceR[A, B](fa: EffR[F, R, E, A])(fb: EffR[F, R, E, B]): EffR[F, R, E, B] =
      wrapUnsafe((r: R) => MC.forceR(unwrapUnsafe(fa)(r))(unwrapUnsafe(fb)(r)))

    def uncancelable[A](body: Poll[Of[F, R, E]] => EffR[F, R, E, A]): EffR[F, R, E, A] =
      wrapUnsafe((r: R) =>
        MC.uncancelable { pollF =>
          unwrapUnsafe(body(EffR.pollEffR(pollF)))(r)
        }
      )

    override def guaranteeCase[A](fa: EffR[F, R, E, A])(fin: Outcome[Of[F, R, E], EE, A] => EffR[F, R, E, Unit]): EffR[F, R, E, A] =
      wrapUnsafe((r: R) =>
        MC.guaranteeCase(unwrapUnsafe(fa)(r)) { outcome =>
          val liftedOutcome: Outcome[Of[F, R, E], EE, A] = outcome match
            case Outcome.Succeeded(success) =>
              Outcome.succeeded[Of[F, R, E], EE, A](wrapUnsafe((_: R) => success))
            case Outcome.Errored(err) =>
              Outcome.errored[Of[F, R, E], EE, A](err)
            case Outcome.Canceled() =>
              Outcome.canceled[Of[F, R, E], EE, A]
          MC.void(unwrapUnsafe(fin(liftedOutcome))(r))
        }
      )
  end given
end EffRInstancesLowPriority0

/** Lifts services, manages environments, and exposes type class instances for
  * [[boilerplate.effect.EffR EffR]].
  */
object EffR extends EffRInstancesLowPriority0:
  /** Higher-kinded alias for working with `EffR` in type class derivations. */
  type Of[F[_], R, E] = [A] =>> EffR[F, R, E, A]

  // ===========================================================================
  // Internal Unsafe Conversion Utilities
  // ===========================================================================
  // These provide direct access to the opaque type's underlying representation
  // for use within boilerplate's implementation where the opaque boundary would
  // otherwise require inefficient wrapping/unwrapping.
  // ===========================================================================

  /** Wraps an `R => Eff[F, E, A]` as `EffR[F, R, E, A]` without any transformation.
    *
    * This is an identity operation at runtime - the opaque type IS the underlying type. For
    * internal use only where the opaque boundary would otherwise be problematic.
    */
  private[boilerplate] inline def wrapUnsafe[F[_], R, E, A](f: R => Eff[F, E, A]): EffR[F, R, E, A] = f

  /** Unwraps an `EffR[F, R, E, A]` to its underlying `R => Eff[F, E, A]` without transformation.
    *
    * This is an identity operation at runtime - the opaque type IS the underlying type. For
    * internal use only where the opaque boundary would otherwise be problematic.
    */
  private[boilerplate] inline def unwrapUnsafe[F[_], R, E, A](effr: EffR[F, R, E, A]): R => Eff[F, E, A] = effr

  /** Partially-applied constructor pinning effect and environment. Use via `EffR[IO, Config]` for
    * ergonomic value creation.
    */
  inline def apply[F[_], R]: EffRPartiallyApplied[F, R] = new EffRPartiallyApplied[F, R]

  /** Builder providing convenient constructors with effect and environment fixed. Refer to
    * [[boilerplate.effect.EffR$ EffR]] for full API.
    */
  final class EffRPartiallyApplied[F[_], R] @publicInBinary private[EffR]:
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

    /** Absorbs an error into `F` when `E` matches the error type of `F`. */
    inline def absolve[EE](using ME: MonadError[F, EE], ev: E <:< EE): R => F[A] =
      (r: R) => self(r).absolve

    /** Folds over both channels, returning to the base effect. */
    inline def fold[B](fe: E => B, fa: A => B)(using Functor[F]): R => F[B] =
      (r: R) => self(r).fold(fe, fa)

    /** Effectfully folds both channels, allowing different continuations. */
    inline def foldF[B](fe: E => F[B], fa: A => F[B])(using Monad[F]): R => F[B] =
      (r: R) => self(r).foldF(fe, fa)

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
    FunctionKImpl(env, f)

  private[effect] class FunctionKImpl[F[_], R, E, G[_]] @publicInBinary private[EffR] (
    env: R,
    f: FunctionK[Eff.Of[F, E], G]
  ) extends FunctionK[Of[F, R, E], G]:
    def apply[A](fa: EffR[F, R, E, A]): G[A] = f(fa.run(env))

  private[effect] class PollEffRImpl[F[_], R, E] @publicInBinary private[EffR] (
    pollF: Poll[Eff.Of[F, E]]
  ) extends Poll[Of[F, R, E]]:
    def apply[A](er: EffR[F, R, E, A]): EffR[F, R, E, A] =
      wrapUnsafe((r: R) => pollF(unwrapUnsafe(er)(r)))

  private[effect] inline def pollEffR[F[_], R, E](pollF: Poll[Eff.Of[F, E]]): Poll[Of[F, R, E]] =
    PollEffRImpl(pollF)

  private[effect] class FiberEffRImpl[F[_], R, E, A] @publicInBinary private[EffR] (
    fib: Fiber[Eff.Of[F, E], Throwable, A],
    liftOutcome: Outcome[Eff.Of[F, E], Throwable, A] => Outcome[Of[F, R, E], Throwable, A],
    S: Functor[Eff.Of[F, E]]
  ) extends Fiber[Of[F, R, E], Throwable, A]:
    def cancel: EffR[F, R, E, Unit] = wrapUnsafe((_: R) => fib.cancel)
    def join: EffR[F, R, E, Outcome[Of[F, R, E], Throwable, A]] =
      wrapUnsafe((_: R) => S.map(fib.join)(liftOutcome))

  private[effect] inline def fiberEffR[F[_], R, E, A](
    fib: Fiber[Eff.Of[F, E], Throwable, A],
    liftOutcome: Outcome[Eff.Of[F, E], Throwable, A] => Outcome[Of[F, R, E], Throwable, A],
    S: Functor[Eff.Of[F, E]]
  ): Fiber[Of[F, R, E], Throwable, A] =
    FiberEffRImpl(fib, liftOutcome, S)

  private[effect] class ReaderFunctionKImpl[F[_], R, E] @publicInBinary private[EffR] extends FunctionK[Eff.Of[F, E], Of[F, R, E]]:
    def apply[X](fa: Eff[F, E, X]): EffR[F, R, E, X] = wrapUnsafe((_: R) => fa)

  private[effect] inline def readerFunctionK[F[_], R, E]: Eff.Of[F, E] ~> Of[F, R, E] =
    new ReaderFunctionKImpl

  private[effect] class NatEffRFunctionKImpl[F[_], R, E, G[_]] @publicInBinary private[EffR] (
    r: R,
    lift: Eff.Of[F, E] ~> G
  ) extends FunctionK[Of[F, R, E], G]:
    def apply[X](effr: EffR[F, R, E, X]): G[X] = lift(unwrapUnsafe(effr)(r))

  private[effect] class ContEffRImpl[F[_], R, E0, K, V] @publicInBinary private[EffR] (
    body: Cont[Of[F, R, E0], K, V],
    r: R
  ) extends Cont[Eff.Of[F, E0], K, V]:
    def apply[G[_]](using G: MonadCancel[G, Throwable]): (Either[Throwable, K] => Unit, G[K], Eff.Of[F, E0] ~> G) => G[V] =
      (resume, get, lift) =>
        val natEffR: Of[F, R, E0] ~> G = NatEffRFunctionKImpl(r, lift)
        body[G].apply(resume, get, natEffR)

  private[effect] inline def contEffR[F[_], R, E0, K, V](
    body: Cont[Of[F, R, E0], K, V],
    r: R
  ): Cont[Eff.Of[F, E0], K, V] =
    ContEffRImpl(body, r)

  private[effect] class ParallelApplicativeImpl[R, PF[_]] @publicInBinary private[EffR] (
    parApplicative: Applicative[PF]
  ) extends Applicative[[x] =>> R => PF[x]]:
    def pure[A](a: A): R => PF[A] =
      (_: R) => parApplicative.pure(a)

    def ap[A, B](ff: R => PF[A => B])(fa: R => PF[A]): R => PF[B] =
      (r: R) => parApplicative.ap(ff(r))(fa(r))

    override def map[A, B](fa: R => PF[A])(f: A => B): R => PF[B] =
      (r: R) => parApplicative.map(fa(r))(f)

    override def product[A, B](fa: R => PF[A], fb: R => PF[B]): R => PF[(A, B)] =
      (r: R) => parApplicative.product(fa(r), fb(r))
  end ParallelApplicativeImpl

  private[effect] class ParallelSequentialEffRImpl[F0[_], R, E, PF[_]] @publicInBinary private[EffR] (
    seq: PF ~> Eff.Of[F0, E]
  ) extends FunctionK[[x] =>> R => PF[x], Of[F0, R, E]]:
    def apply[A](fa: R => PF[A]): EffR[F0, R, E, A] =
      (r: R) => seq(fa(r))

  private[effect] class ParallelParEffRImpl[F0[_], R, E, PF[_]] @publicInBinary private[EffR] (
    par: Eff.Of[F0, E] ~> PF
  ) extends FunctionK[Of[F0, R, E], [x] =>> R => PF[x]]:
    def apply[A](fa: EffR[F0, R, E, A]): R => PF[A] =
      (r: R) => par(fa(r))

  // ---------------------------------------------------------------------------
  // Typeclass Instances
  // ---------------------------------------------------------------------------
  // These are implemented directly on EffR without Kleisli delegation to maintain
  // zero-allocation semantics. The pattern `(r: R) => ...` is inlined at compile time.

  private type Base[F[_], E] = [A] =>> Eff[F, E, A]

  /** Derives a `Monad` instance directly for environment-aware programs. */
  given [F[_], R, E] => (M: Monad[Base[F, E]]) => Monad[Of[F, R, E]]:
    def pure[A](a: A): EffR[F, R, E, A] =
      (_: R) => M.pure(a)

    def flatMap[A, B](fa: EffR[F, R, E, A])(f: A => EffR[F, R, E, B]): EffR[F, R, E, B] =
      (r: R) => M.flatMap(fa(r))(a => f(a)(r))

    def tailRecM[A, B](a: A)(f: A => EffR[F, R, E, Either[A, B]]): EffR[F, R, E, B] =
      (r: R) => M.tailRecM(a)(a0 => f(a0)(r))

  /** `Bifunctor` instance enabling `bimap` and `leftMap` on both error and success channels.
    *
    * Provides the canonical bifunctor operations via cats syntax.
    */
  given [F[_]: Functor, R] => Bifunctor[[E, A] =>> EffR[F, R, E, A]]:
    def bimap[A, B, C, D](fab: EffR[F, R, A, B])(f: A => C, g: B => D): EffR[F, R, C, D] =
      (r: R) => Bifunctor[[E, A] =>> Eff[F, E, A]].bimap(fab(r))(f, g)

  /** Provides `MonadError` directly for the typed error channel. */
  given [F[_], R, E] => (ME: MonadError[Base[F, E], E]) => MonadError[Of[F, R, E], E]:
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
  end given

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
  given [F0[_], R, E] => (P: Parallel[Base[F0, E]]) => Parallel[Of[F0, R, E]]:
    // The parallel applicative is a reader over Eff's parallel applicative: R => P.F[A]
    type F[x] = R => P.F[x]

    val applicative: Applicative[F] = ParallelApplicativeImpl(P.applicative)

    val monad: Monad[Of[F0, R, E]] =
      given Monad[Base[F0, E]] = P.monad
      summon[Monad[Of[F0, R, E]]]

    val sequential: F ~> Of[F0, R, E] = ParallelSequentialEffRImpl(P.sequential)

    val parallel: Of[F0, R, E] ~> F = ParallelParEffRImpl(P.parallel)
  end given

  /** Defers evaluation of `EffR` computations until demanded. */
  given [F[_], R, E] => (D: Defer[Base[F, E]]) => Defer[Of[F, R, E]]:
    def defer[A](fa: => EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) => D.defer(fa(r))

  /** Provides time measurement capabilities for `EffR` computations. */
  given [F[_], R, E] => (C: Clock[Base[F, E]], M: Monad[Base[F, E]]) => Clock[Of[F, R, E]]:
    val applicative: Applicative[Of[F, R, E]] = summon[Monad[Of[F, R, E]]]

    def monotonic: EffR[F, R, E, FiniteDuration] = (_: R) => C.monotonic

    def realTime: EffR[F, R, E, FiniteDuration] = (_: R) => C.realTime

  /** Provides unique token generation for `EffR` computations. */
  given [F[_], R, E] => (U: Unique[Base[F, E]], M: Monad[Base[F, E]]) => Unique[Of[F, R, E]]:
    val applicative: Applicative[Of[F, R, E]] = summon[Monad[Of[F, R, E]]]

    def unique: EffR[F, R, E, Unique.Token] = (_: R) => U.unique

  /** Provides choice/alternative semantics for `EffR` computations.
    *
    * `combineK` tries the first computation; if it fails with a typed error, falls back to the
    * second. The environment is threaded to both computations.
    */
  given [F[_], R, E] => (S: SemigroupK[Base[F, E]]) => SemigroupK[Of[F, R, E]]:
    def combineK[A](x: EffR[F, R, E, A], y: EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) => S.combineK(x(r), y(r))

  /** Combines two `EffR` computations when both succeed, using `Semigroup` to combine values.
    *
    * The environment is threaded to both computations sequentially. If either fails, the error
    * propagates.
    */
  given [F[_], R, E, A] => (S: Semigroup[Eff[F, E, A]]) => Semigroup[EffR[F, R, E, A]]:
    def combine(x: EffR[F, R, E, A], y: EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) => S.combine(x(r), y(r))

  /** Combines `EffR` computations with an identity element.
    *
    * `empty` ignores the environment and produces `Monoid[Eff[F, E, A]].empty`.
    */
  given [F[_], R, E, A] => (M: Monoid[Eff[F, E, A]]) => Monoid[EffR[F, R, E, A]]:
    def empty: EffR[F, R, E, A] = (_: R) => M.empty

    def combine(x: EffR[F, R, E, A], y: EffR[F, R, E, A]): EffR[F, R, E, A] =
      (r: R) => M.combine(x(r), y(r))
end EffR
