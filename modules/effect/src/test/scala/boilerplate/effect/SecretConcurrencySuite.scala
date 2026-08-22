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

import boilerplate.Secret

// JVM only: this is the lane with real parallelism, so the read guard's CAS is exercised rather
// than merely interleaved.
class SecretConcurrencySuite extends CatsEffectSuite:
  private val rounds = 200

  private def race: IO[Unit] =
    for
      secret <- IO(Secret.fill(4)(view => (0 until 4).foreach(i => view(i) = 7.toByte)))
      reader <- secret.useEff(view => IO(List(view(0), view(1), view(2), view(3)))).absolve.attempt.start
      destroyer <- IO(secret.destroy()).attempt.start
      read <- reader.joinWithNever
      destroyed <- destroyer.joinWithNever
    yield
      read.foreach(bytes => assertEquals(bytes, List[Byte](7, 7, 7, 7), "a concurrent destroy erased bytes mid-read"))
      // A read fails only once the secret is destroyed, and a destroy fails only while a read holds
      // the guard - so the two cannot both fail.
      assert(read.isRight || destroyed.isRight, s"both the read and the destroy failed: $read / $destroyed")

  test("a read racing a destroy either sees the whole secret or is refused, never a partial wipe"):
    Eff.traverse_(List.range(0, rounds))(_ => race).absolve
end SecretConcurrencySuite
