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
import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Poll
import cats.effect.kernel.Resource

import boilerplate.ErrorTest

/** Typed-error lifecycle-scoped resource, the [[boilerplate.effect.Eff Eff]] counterpart of
  * `cats.effect.Resource`: the representation is exactly `Resource[IO, A]`, and `E <: Throwable` is
  * the same compile-time phantom, carrying the error type an acquisition may fail with.
  *
  * `Resource[IO, A]` is declared as a supertype, so a raw `cats.effect.Resource` flows into an
  * `EffResource`-typed position by subtyping alone, and `absolve` is the explicit way back.
  * Expressing the error in a covariant parameter of the resource type - rather than inside an
  * invariant `F` - is what lets acquisition channels compose: sequencing resources that fail
  * differently yields their precise union, exactly as [[boilerplate.effect.Eff Eff]] does, with no
  * `mapK` and no cast.
  *
  * Release never carries a typed error. A finaliser runs on success, typed failure and cancellation
  * alike, and has no channel of its own to fail into; anything it raises is a defect on `IO`'s
  * channel.
  *
  * The surface mirrors the subset of `cats.effect.Resource` this ecosystem consumes and grows
  * additively. Refer to [[boilerplate.effect.EffResource$ EffResource]] for constructors and
  * combinators.
  */
opaque type EffResource[+E <: Throwable, +A] >: Resource[IO, A] = Resource[IO, A]

/** Provides constructors, combinators, and type class instances for
  * [[boilerplate.effect.EffResource EffResource]].
  */
object EffResource:
  /** Partially applied alias enabling higher-kinded usage of
    * [[boilerplate.effect.EffResource EffResource]].
    */
  type Of[E <: Throwable] = [A] =>> EffResource[E, A]

  /** A resource with no finaliser, holding the result of `eff`. */
  def eval[E <: Throwable, A](eff: Eff[E, A]): EffResource[E, A] = Resource.eval(eff.absolve)

  /** A resource acquiring through `acquire` and releasing through `release`. */
  def make[E <: Throwable, A](acquire: Eff[E, A])(release: A => Eff[Nothing, Unit]): EffResource[E, A] =
    Resource.make(acquire.absolve)(a => release(a).absolve)

  /** As [[make]], with acquisition running uncancelable and `poll` marking the region within it
    * that may be cancelled.
    */
  def makeFull[E <: Throwable, A](acquire: Poll[IO] => Eff[E, A])(release: A => Eff[Nothing, Unit]): EffResource[E, A] =
    Resource.makeFull[IO, A](poll => acquire(poll).absolve)(a => release(a).absolve)

  /** A resource with no finaliser, holding `a`. */
  def pure[A](a: A): EffResource[Nothing, A] = Resource.pure(a)

  /** Canonical resource holding unit, with no finaliser. */
  val unit: EffResource[Nothing, Unit] = Resource.unit

  extension [E <: Throwable, A](self: EffResource[E, A])
    /** Acquires, runs `f`, and releases - on success, typed failure and cancellation alike.
      *
      * The yielded value is alive only until release runs, and nothing here stops `f` returning it.
      * For a payload release erases (a borrowed view, secret bytes), the scoped continuation APIs
      * (`wiping` on the acquiring `IO[Slice]`, `useEff` on `Secret`) are the enforced read window -
      * a resource has no binder to root a view's lifetime in, so enforcement lives at continuation
      * seams.
      */
    def use[E2 <: Throwable, B](f: A => Eff[E2, B]): Eff[E | E2, B] =
      (self: Resource[IO, A]).use(a => f(a).absolve)

    /** Acquires and immediately releases, for a resource used only for its effects. */
    @targetName("useUnit")
    def use_ : Eff[E, Unit] = (self: Resource[IO, A]).use_

    /** Runs `eff` with the resource acquired, discarding the resource value. */
    def surround[E2 <: Throwable, B](eff: => Eff[E2, B]): Eff[E | E2, B] =
      (self: Resource[IO, A]).surround(eff.absolve)

    /** Acquires both resources concurrently, holding each until the scope exits. */
    def both[E2 <: Throwable, B](that: EffResource[E2, B]): EffResource[E | E2, (A, B)] =
      (self: Resource[IO, A]).both(that: Resource[IO, B])

    /** Registers an additional finaliser, run after this resource's own. */
    def onFinalize(fin: Eff[Nothing, Unit]): EffResource[E, A] =
      (self: Resource[IO, A]).onFinalize(fin.absolve)

    /** Transforms the acquired value effectfully; the result acquires with either channel. */
    def evalMap[E2 <: Throwable, B](f: A => Eff[E2, B]): EffResource[E | E2, B] =
      (self: Resource[IO, A]).evalMap(a => f(a).absolve)

    /** Observes the acquired value effectfully; the result acquires with either channel. */
    def evalTap[E2 <: Throwable, B](f: A => Eff[E2, B]): EffResource[E | E2, A] =
      (self: Resource[IO, A]).evalTap(a => f(a).absolve)

    /** Maps the acquired value. */
    def map[B](f: A => B): EffResource[E, B] = (self: Resource[IO, A]).map(f)

    /** Sequences resources; the result acquires with either channel, and finalisers run in reverse
      * order.
      */
    def flatMap[E2 <: Throwable, B](f: A => EffResource[E2, B]): EffResource[E | E2, B] =
      (self: Resource[IO, A]).flatMap(a => f(a))

    /** Absorbs the typed error into `IO`. O(0) identity - an acquisition failure is already on
      * `IO`'s channel.
      */
    def absolve: Resource[IO, A] = self
  end extension

  /** Retries ACQUISITION on typed failures, paced and bounded by `policy`; the final typed error
    * propagates once the policy stops. Only acquisition is in scope - the client-pool shape: a
    * failed attempt has already released whatever prefix it acquired, the next attempt re-acquires
    * the WHOLE composed resource (place `retry` on an inner stage before composing to re-run that
    * stage alone), the retried allocation is registered atomically once it succeeds, and the
    * consumer of the resource is never re-run. A defect propagates without retrying.
    */
  def retry[E <: Throwable, A](resource: EffResource[E, A], policy: RetryPolicy)(using
    ErrorTest[E]
  ): EffResource[E, A] =
    retryAllocated(resource, policy, _ => true, (_, _, _) => IO.unit)

  /** As the policy overload, retrying only acquisition failures `retryOn` accepts; a rejected error
    * propagates immediately.
    */
  def retry[E <: Throwable, A](resource: EffResource[E, A], policy: RetryPolicy, retryOn: E => Boolean)(using
    ErrorTest[E]
  ): EffResource[E, A] =
    retryAllocated(resource, policy, retryOn, (_, _, _) => IO.unit)

  /** As the policy overload, invoking `onRetry` with the 1-based number of the acquisition attempt
    * that just failed, its error, and the delay about to be slept - only when a retry will actually
    * happen, before its sleep.
    */
  def retry[E <: Throwable, A](
    resource: EffResource[E, A],
    policy: RetryPolicy,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using ErrorTest[E]): EffResource[E, A] =
    retryAllocated(resource, policy, _ => true, onRetry)

  /** As the policy overload, with both the `retryOn` filter and the `onRetry` observer. */
  def retry[E <: Throwable, A](
    resource: EffResource[E, A],
    policy: RetryPolicy,
    retryOn: E => Boolean,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using ErrorTest[E]): EffResource[E, A] =
    retryAllocated(resource, policy, retryOn, onRetry)

  /** Retries an infallible resource: an acquisition defect is never a typed error, so it propagates
    * on the first attempt - zero retries, no delay.
    */
  def retry[A](resource: EffResource[Nothing, A], @unused policy: RetryPolicy): EffResource[Nothing, A] =
    resource

  /** Retries an infallible resource: an acquisition defect is never a typed error, so it propagates
    * on the first attempt - zero retries, no delay.
    */
  def retry[A](
    resource: EffResource[Nothing, A],
    @unused policy: RetryPolicy,
    @unused retryOn: Nothing => Boolean
  ): EffResource[Nothing, A] = resource

  /** Retries an infallible resource: an acquisition defect is never a typed error, so it propagates
    * on the first attempt - zero retries, no delay, no observation.
    */
  def retry[A](
    resource: EffResource[Nothing, A],
    @unused policy: RetryPolicy,
    @unused onRetry: (Int, Nothing, FiniteDuration) => IO[Unit]
  ): EffResource[Nothing, A] = resource

  /** Retries an infallible resource: an acquisition defect is never a typed error, so it propagates
    * on the first attempt - zero retries, no delay, no observation.
    */
  def retry[A](
    resource: EffResource[Nothing, A],
    @unused policy: RetryPolicy,
    @unused retryOn: Nothing => Boolean,
    @unused onRetry: (Int, Nothing, FiniteDuration) => IO[Unit]
  ): EffResource[Nothing, A] = resource

  // `allocatedCase` inside `applyFull` is the one cancellation-safe way to retry acquisition: a
  // failing allocation has already released its own prefix, so attempts never leak; `poll` keeps
  // the retried acquisition cancelable; and the succeeding pair is registered within applyFull's
  // uncancelable region, closing the window `allocated` alone would leave open.
  private def retryAllocated[E <: Throwable, A](
    resource: EffResource[E, A],
    policy: RetryPolicy,
    retryOn: E => Boolean,
    onRetry: (Int, E, FiniteDuration) => IO[Unit]
  )(using ErrorTest[E]): EffResource[E, A] =
    Resource.applyFull { poll =>
      val allocation: Eff[E, (A, Resource.ExitCase => IO[Unit])] = (resource: Resource[IO, A]).allocatedCase
      poll(Eff.retry(allocation, policy, retryOn, onRetry).absolve)
    }

  /** `Async` for `EffResource`, and by subtyping every capability it extends - `MonadCancel` above
    * all, which is what a resource's callers summon. Sourced from `Resource`'s own instance: the
    * phantom erases, so `Of[E]` IS `Resource[IO, *]`.
    */
  given effResourceAsync: [E <: Throwable] => Async[Of[E]] =
    Resource.catsEffectAsyncForResource[IO].asInstanceOf[Async[Of[E]]] // scalafix:ok DisableSyntax.asInstanceOf
end EffResource
