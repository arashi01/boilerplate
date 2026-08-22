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
package boilerplate

import scala.language.experimental.captureChecking

/** The typed error for the untrusted-bounds reader [[sliceOrError]] - wire input whose bounds are
  * attacker-controlled. Trusted-bounds operations (`take`/`drop`/`slice`,
  * `apply`/`readBE`/`readLE`) raise instead.
  */
sealed abstract class SliceError(message: String) extends TypedError(message, None)
object SliceError:
  /** The requested range `[from, until)` did not satisfy `0 <= from <= until <= length`. */
  final case class OutOfBounds(from: Int, until: Int, length: Int) extends SliceError(s"slice [$from, $until) is outside [0, $length]")

extension (s: Slice^)
  /** The untrusted-bounds reader for wire input: `Right(view)` when `0 <= from <= until <= length`,
    * else `Left(SliceError.OutOfBounds)`. Trusted callers use `slice`, which raises.
    */
  def sliceOrError(from: Int, until: Int): Either[SliceError, Slice^{s}] =
    if 0 <= from && from <= until && until <= s.length then Right(s.slice(from, until))
    else Left(SliceError.OutOfBounds(from, until, s.length))
