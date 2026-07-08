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

import cats.effect.IO
import munit.CatsEffectSuite

import boilerplate.Slice
import boilerplate.effect.AppError.*

class SliceWipingSuite extends CatsEffectSuite:

  test("wiping runs the use over the secret, then erases it"):
    val backing = Array[Byte](1, 2, 3, 4)
    IO(Slice.of(backing)).wiping
      .useEffIO[Throwable, List[Byte]](s => EffIO.succeed(s.toArray.toList))
      .either
      .map: result =>
        assertEquals(result, Right(List[Byte](1, 2, 3, 4))) // the use observed the secret
        assertEquals(backing.toList, List[Byte](0, 0, 0, 0)) // erased on release

  test("wiping erases the secret even when the use fails"):
    val backing = Array[Byte](1, 2, 3, 4)
    IO(Slice.of(backing)).wiping
      .useEffIO[AppError, Unit](_ => EffIO.fail(Timeout))
      .either
      .map: result =>
        assertEquals(result, Left(Timeout))
        assertEquals(backing.toList, List[Byte](0, 0, 0, 0))

  test("wiping erases the secret when the use is cancelled"):
    val backing = Array[Byte](1, 2, 3, 4)
    for
      started <- IO.deferred[Unit]
      fiber <- IO(Slice.of(backing)).wiping
                 .useEffIO[Throwable, Unit](_ => EffIO.liftF(started.complete(()).flatMap(_ => IO.never[Unit])))
                 .absolve
                 .start
      _ <- started.get // the use has begun, so the resource is acquired
      _ <- fiber.cancel // cancel blocks until finalisers - the wipe - have run
    yield assertEquals(backing.toList, List[Byte](0, 0, 0, 0))
end SliceWipingSuite
