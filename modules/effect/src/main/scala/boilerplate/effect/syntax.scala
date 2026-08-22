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

import scala.annotation.targetName
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Outcome
import cats.effect.kernel.Resource

import boilerplate.ErrorTest

extension [E <: Throwable, A](fiber: Fiber[Eff.Of[E], Throwable, A])
  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation never
    * completes.
    */
  def joinNever: Eff[E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => IO.raiseError[A](e)
      case Outcome.Canceled()    => IO.never[A]
    }

  /** Joins the fibre: a success returns its value, a typed error `E` propagates, cancellation fails
    * with `onCanceled`.
    */
  def joinOrFail(onCanceled: => E): Eff[E, A] =
    fiber.join.flatMap {
      case Outcome.Succeeded(fa) => fa
      case Outcome.Errored(e)    => IO.raiseError[A](e)
      case Outcome.Canceled()    => Eff.fail(onCanceled)
    }
end extension

extension [A](io: IO[A])
  /** This effect viewed as an infallible `Eff` - identity at runtime, committing `E = Nothing`
    * (failures stay defects). Subtyping lifts an `IO` in every argument position already; this
    * exists for the one position subtyping cannot reach - an `IO` generator anywhere before a typed
    * step in a for-comprehension selects `IO`'s own member `flatMap`, dropping the chain off the
    * typed surface, so mark each such generator with `.eff`.
    */
  def eff: UEff[A] = io

extension [A](resource: Resource[IO, A])
  /** This resource viewed as an infallible `EffResource` - identity at runtime; the `Resource`
    * counterpart of `eff` on `IO`, for the same generator position.
    */
  def eff: EffResource[Nothing, A] = resource

// Every combinator whose name a cats or cats-effect syntax conversion ALSO provides, re-declared at
// package level and delegating to the companion, which carries its documentation. Selection of `e.m`
// falls back to an implicit conversion only when no extension applies, at every scope level, and an
// imported conversion is lexical while the companion's extensions are only in implicit scope - so
// without a lexical twin `import cats.syntax.all.*` or `import cats.effect.syntax.all.*` captures
// the call. A captured call resolves one `F` for both operands, which loses the precise union; where
// our signature differs from the conversion's it does not compile at all.
//
// These are reached only through `import boilerplate.effect.*`: a consumer who imports `Eff` by name
// has the conversions in lexical scope and none of this, so the capture happens as though the
// package-level copies were absent.
extension [E <: Throwable, A](self: Eff[E, A])
  def map[B](f: A => B): Eff[E, B] =
    Eff.map(self)(f)

  def flatMap[E2 <: Throwable, B](f: A => Eff[E2, B]): Eff[E | E2, B] =
    Eff.flatMap(self)(f)

  @targetName("productR")
  def *>[E2 <: Throwable, B](that: => Eff[E2, B]): Eff[E | E2, B] =
    Eff.*>(self)(that)

  @targetName("productL")
  def <*[E2 <: Throwable, B](that: => Eff[E2, B]): Eff[E | E2, A] =
    Eff.<*(self)(that)

  @targetName("parProductR")
  def &>[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, B] =
    Eff.&>(self)(that)

  @targetName("parProductL")
  def <&[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, A] =
    Eff.<&(self)(that)

  def product[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, (A, B)] =
    Eff.product(self)(that)

  def flatTap[E2 <: Throwable, B](f: A => Eff[E2, B]): Eff[E | E2, A] =
    Eff.flatTap(self)(f)

  def void: Eff[E, Unit] =
    Eff.void(self)

  def as[B](b: B): Eff[E, B] =
    Eff.as(self)(b)

  def bracket[E2 <: Throwable, B](use: A => Eff[E2, B])(release: A => IO[Unit]): Eff[E | E2, B] =
    Eff.bracket(self)(use)(release)

  def bracketCase[E2 <: Throwable, B](use: A => Eff[E2, B])(
    release: (A, Outcome[Eff.Of[E | E2], Throwable, B]) => IO[Unit]
  ): Eff[E | E2, B] =
    Eff.bracketCase(self)(use)(release)

  def start: Eff[E, Fiber[Eff.Of[E], Throwable, A]] =
    Eff.start(self)

  def background: Resource[IO, IO[Outcome[Eff.Of[E], Throwable, A]]] =
    Eff.background(self)

  def race[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, Either[A, B]] =
    Eff.race(self)(that)

  def both[E2 <: Throwable, B](that: Eff[E2, B]): Eff[E | E2, (A, B)] =
    Eff.both(self)(that)

  def onCancel[E2 <: Throwable](fin: Eff[E2, Unit]): Eff[E | E2, A] =
    Eff.onCancel(self)(fin)

  def guarantee[E2 <: Throwable](fin: Eff[E2, Unit]): Eff[E | E2, A] =
    Eff.guarantee(self)(fin)

  def guaranteeCase(fin: Outcome[Eff.Of[E], Throwable, A] => IO[Unit]): Eff[E, A] =
    Eff.guaranteeCase(self)(fin)

  def delayBy(duration: FiniteDuration): Eff[E, A] =
    Eff.delayBy(self)(duration)

  def andWait(duration: FiniteDuration): Eff[E, A] =
    Eff.andWait(self)(duration)

  def timed: Eff[E, (FiniteDuration, A)] =
    Eff.timed(self)

  def evalOn(ec: ExecutionContext): Eff[E, A] =
    Eff.evalOn(self)(ec)

  def timeout[E2 <: Throwable](duration: FiniteDuration, onTimeout: => E2): Eff[E | E2, A] =
    Eff.timeout(self)(duration, onTimeout)

  def timeoutTo[E2 <: Throwable, B >: A](duration: FiniteDuration, fallback: => Eff[E2, B]): Eff[E | E2, B] =
    Eff.timeoutTo(self)(duration, fallback)

  def attemptTap[E2 <: Throwable](f: Either[E, A] => Eff[E2, Unit])(using ErrorTest[E]): Eff[E | E2, A] =
    Eff.attemptTap(self)(f)
end extension

// `attemptTap` is the one twinned name that is also a channel observer, so its receiver-`Nothing`
// overload is twinned beside it: without the pair at package level the general twin would be found
// first for a `UEff` receiver, widen `E` to `Throwable` to satisfy the evidence, and capture defects.
extension [A](self: Eff[Nothing, A])
  def attemptTap[E2 <: Throwable](f: Either[Nothing, A] => Eff[E2, Unit]): Eff[E2, A] =
    Eff.attemptTap(self)(f)

extension [E <: Throwable, A](self: EffResource[E, A])
  def map[B](f: A => B): EffResource[E, B] =
    EffResource.map(self)(f)

  def flatMap[E2 <: Throwable, B](f: A => EffResource[E2, B]): EffResource[E | E2, B] =
    EffResource.flatMap(self)(f)

  def both[E2 <: Throwable, B](that: EffResource[E2, B]): EffResource[E | E2, (A, B)] =
    EffResource.both(self)(that)
