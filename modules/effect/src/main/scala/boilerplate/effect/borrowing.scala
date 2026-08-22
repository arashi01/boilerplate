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
import cats.effect.kernel.Resource

import boilerplate.Secret
import boilerplate.Slice

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
