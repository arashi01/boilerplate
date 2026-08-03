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

import scala.language.experimental.captureChecking

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Outcome
import cats.effect.kernel.Resource

import boilerplate.Secret
import boilerplate.Slice

extension [E <: Throwable, A](fiber: Fiber[Eff.Of[E], Throwable, A])
  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation never
    * completes.
    */
  inline def joinNever: Eff[E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => IO.raiseError[A](e)
      case Outcome.Canceled()    => IO.never[A]
    }

  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation fails
    * with `onCanceled`.
    */
  inline def joinOrFail(onCanceled: => E): Eff[E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => IO.raiseError[A](e)
      case Outcome.Canceled()    => Eff.fail(onCanceled)
    }
end extension

extension (acquire: IO[Slice])
  /** Acquires a secret slice through `acquire`, runs `f` on a view of it, then erases it - on
    * success, typed error, and cancellation alike. Keep the working-copy allocation inside
    * `acquire` so the slice is erased from the moment it exists.
    *
    * The scoped continuation, rather than a resource yielding the slice, is what makes the read
    * window enforceable: a `Resource` has no binder to root a borrowed view's lifetime in, so a
    * caller's `use` could read the slice after the wipe. Here the view may not escape `f`, nor may
    * one re-sliced from it.
    */
  def wiping[E <: Throwable, A](f: Slice^ => Eff[E, A]): Eff[E, A] =
    Resource.make(acquire)(s => IO(s.wipe())).use(s => f(s).absolve)

extension (s: Secret)
  /** Runs `f` on a view of the bytes and then the effect it returns, holding the read guard for
    * both - so a concurrent `destroy` cannot erase the bytes part-way through the operation, not
    * merely part-way through the call. The view itself may not escape `f`.
    */
  def useEff[E <: Throwable, A](f: Slice^ => Eff[E, A]): Eff[E, A] =
    IO(Secret.enter(s)).bracket(_ => Secret.unguarded(s)(f).absolve)(_ => IO(Secret.exit(s)))

extension (secret: Secret.type)
  /** A resource filling a secret of `size` bytes through `init` and destroying it on release - on
    * success, typed error, and cancellation alike.
    */
  def scoped(size: Int)(init: Slice^ => Unit): EffResource[Nothing, Secret] =
    Resource.make(IO(Secret.fill(size)(init)))(s => IO(s.destroy()))

extension [A](io: IO[A])
  /** This effect viewed as an infallible `Eff` - identity at runtime, committing `E = Nothing`
    * (failures stay defects). Subtyping lifts an `IO` in every argument position already; this
    * exists for the one position subtyping cannot reach - a leading `IO` generator in a
    * for-comprehension selects `IO`'s own member `flatMap` before any typed step is considered,
    * so mark that generator with `.eff` to keep the chain on the typed surface.
    */
  inline def eff: UEff[A] = io

extension [A](resource: Resource[IO, A])
  /** This resource viewed as an infallible `EffResource` - identity at runtime; the `Resource`
    * counterpart of `eff` on `IO`, for the same leading-generator position.
    */
  inline def eff: EffResource[Nothing, A] = resource

// The monadic core re-declared at package level, delegating to the companions, which carry its
// documentation. Selection of `e.m` falls back to an implicit conversion only when NO extension
// applies, at every scope level - so these lexically visible twins stay selected ahead of cats'
// Ops-conversion syntax (e.g. from `import cats.syntax.all.*`), whose `flatMap` over `Monad[Of[E]]`
// would pin `E` to the receiver's and reject error-union widening in for-comprehensions.
extension [E <: Throwable, A](self: Eff[E, A])
  inline def map[B](f: A => B): Eff[E, B] =
    Eff.map(self)(f)

  inline def flatMap[E2 >: E <: Throwable, B](f: A => Eff[E2, B]): Eff[E2, B] =
    Eff.flatMap(self)(f)

extension [E <: Throwable, A](self: EffResource[E, A])
  inline def map[B](f: A => B): EffResource[E, B] =
    EffResource.map(self)(f)

  inline def flatMap[E2 >: E <: Throwable, B](f: A => EffResource[E2, B]): EffResource[E2, B] =
    EffResource.flatMap(self)(f)
