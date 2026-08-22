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
package boilerplate.stream

import cats.effect.IO
import fs2.Pipe
import fs2.Stream

import boilerplate.ErrorTest
import boilerplate.effect.Eff

/** An fs2 stream over the typed error channel.
  *
  * `Stream` is covariant in its effect, so a stream on a narrow channel is already a stream on a
  * wider one - widening needs no `covary` and no `mapK`. A raw `Stream[IO, O]` lands in a typed
  * position for the same reason, `IO[A]` being a supertype of `Eff[E, A]`.
  */
type EffStream[+E <: Throwable, +O] = Stream[Eff.Of[E], O]

/** A pipe over the typed error channel.
  *
  * `Pipe` is a function alias whose effect is invariant, so a pipe does NOT widen by subtyping the
  * way a stream does; use [[widen]] to claim one at a wider channel.
  */
type EffPipe[E <: Throwable, -I, +O] = Pipe[Eff.Of[E], I, O]

// `E` is absent from the representation - `Eff.Of[E]` IS `IO` - so this is an identity. It is the
// one place the phantom is cast, and every observer below reaches fs2's own combinators through it.
private def raw[E <: Throwable, O](stream: EffStream[E, O]): Stream[IO, O] =
  stream.asInstanceOf[Stream[IO, O]] // scalafix:ok DisableSyntax.asInstanceOf

extension [E <: Throwable, I, O](pipe: EffPipe[E, I, O])
  /** The same pipe claimed at a wider channel. `Stream#through` takes its function's input at the
    * RECEIVER's effect, so a stream on the wider channel needs a pipe declared there; the pipe
    * itself still fails only with its own errors.
    */
  def widen[E2 >: E <: Throwable]: EffPipe[E2, I, O] =
    pipe.asInstanceOf[EffPipe[E2, I, O]] // scalafix:ok DisableSyntax.asInstanceOf

/** The typed observers on a stream. Each one filters by [[boilerplate.ErrorTest ErrorTest]] and
  * lets a defect through, as their `Eff` counterparts do.
  *
  * HAZARD: fs2's own `attempt` and `handleErrorWith` take every `Throwable`, defects included, and
  * they are MEMBERS of `Stream` - so on this alias they win over any extension here. Reach for
  * `reify` and `catchAll` by name; a call to `attempt` or `handleErrorWith` on an `EffStream` is
  * fs2's untyped behaviour, not this vocabulary's.
  */
extension [E <: Throwable, O](self: EffStream[E, O])
  /** Recovers typed failures with another stream; a defect propagates. */
  def catchAll[E2 <: Throwable, O2 >: O](f: E => EffStream[E2, O2])(using et: ErrorTest[E]): EffStream[E2, O2] =
    raw(self).handleErrorWith(t =>
      if et.test(t) then raw(f(t.asInstanceOf[E])) else Stream.raiseError[IO](t) // scalafix:ok DisableSyntax.asInstanceOf
    )

  /** Reifies typed failures as a final `Left` element; a defect propagates. Named apart from
    * `Eff.either` because `Stream#either` is fs2's merge, and `Stream#attempt` captures defects -
    * both members, both winning over any extension.
    */
  def reify(using et: ErrorTest[E]): EffStream[Nothing, Either[E, O]] =
    raw(self)
      .map(o => Right(o): Either[E, O])
      .handleErrorWith(t =>
        if et.test(t) then Stream.emit(Left(t.asInstanceOf[E])) // scalafix:ok DisableSyntax.asInstanceOf
        else Stream.raiseError[IO](t)
      )

  /** Transforms the typed channel; a defect propagates. */
  def mapError[E2 <: Throwable](f: E => E2)(using et: ErrorTest[E]): EffStream[E2, O] =
    raw(self).handleErrorWith(t =>
      Stream.raiseError[IO](if et.test(t) then f(t.asInstanceOf[E]) else t) // scalafix:ok DisableSyntax.asInstanceOf
    )

  /** The stream on `IO`'s channel - the explicit, one-directional exit. */
  def absolve: Stream[IO, O] = raw(self)
end extension

extension [O](stream: Stream[IO, O])
  /** This stream viewed as an infallible `EffStream` - identity, for the generator position where
    * `Stream`'s own member `flatMap` would otherwise drop the chain off the typed surface.
    */
  def eff: EffStream[Nothing, O] = stream
