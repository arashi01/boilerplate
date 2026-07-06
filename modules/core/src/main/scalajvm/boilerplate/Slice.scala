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

/** A bounds-checked, immutable view over caller-owned bytes - the ecosystem's one byte-slice
  * vocabulary. A `Slice` never owns, frees, or outlives its backing region: it is a borrower, valid
  * only while the caller keeps that region alive.
  *
  * Construct with [[Slice$.of]] (bounds-checked); re-slice with [[take]]/[[drop]]/[[slice]] (each a
  * fresh header over the same memory, no copy); copy out with [[toArray]]/[[copyInto]]. The
  * `unsafe*` accessors are the seam for library-author backends and are platform-specific (an array
  * and offset on JVM/JS, an interior pointer on Native); ordinary users never need them.
  */
final class Slice private (val unsafeArray: Array[Byte], val unsafeOffset: Int, val length: Int):
  def isEmpty: Boolean = length == 0

  /** A view of the first `n` bytes. Requires `0 <= n <= length`. */
  def take(n: Int): Slice =
    require(0 <= n && n <= length, "take bounds")
    new Slice(unsafeArray, unsafeOffset, n)

  /** A view past the first `n` bytes. Requires `0 <= n <= length`. */
  def drop(n: Int): Slice =
    require(0 <= n && n <= length, "drop bounds")
    new Slice(unsafeArray, unsafeOffset + n, length - n)

  /** A view of the byte range `[from, until)`. Requires `0 <= from <= until <= length`. */
  def slice(from: Int, until: Int): Slice =
    require(0 <= from && from <= until && until <= length, "slice bounds")
    new Slice(unsafeArray, unsafeOffset + from, until - from)

  /** Copies the viewed bytes out to a fresh owned array. */
  def toArray: Array[Byte] =
    val out = new Array[Byte](length)
    System.arraycopy(unsafeArray, unsafeOffset, out, 0, length)
    out

  /** Copies `min(length, dst.length)` bytes into `dst`, returning the number copied. */
  def copyInto(dst: Slice): Int =
    val n = math.min(length, dst.length)
    System.arraycopy(unsafeArray, unsafeOffset, dst.unsafeArray, dst.unsafeOffset, n)
    n
end Slice

/** Provides constructors for [[boilerplate.Slice Slice]]. */
object Slice:
  given CanEqual[Slice, Slice] = CanEqual.derived

  /** A view over the whole array. */
  def of(array: Array[Byte]): Slice = new Slice(array, 0, array.length)

  /** A view over `array[offset, offset + length)`. Requires the range to be within `array`. */
  def of(array: Array[Byte], offset: Int, length: Int): Slice =
    require(offset >= 0 && length >= 0 && offset + length <= array.length, "slice bounds")
    new Slice(array, offset, length)

  /** The empty view. */
  val empty: Slice = of(Array.emptyByteArray)
end Slice
