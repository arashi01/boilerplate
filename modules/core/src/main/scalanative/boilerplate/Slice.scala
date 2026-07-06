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

import scala.annotation.unused
import scala.scalanative.libc.string
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsafe.UnsafeRichArray
import scala.scalanative.unsigned.*

/** A bounds-checked, immutable view over caller-owned bytes - the ecosystem's one byte-slice
  * vocabulary. A `Slice` never owns, frees, or outlives its backing region: it is a borrower, valid
  * only while the caller keeps that region alive.
  *
  * Construct with [[Slice$.of]] (bounds-checked); re-slice with [[take]]/[[drop]]/[[slice]] (each a
  * fresh header over the same memory, no copy); copy out with [[toArray]]/[[copyInto]]. The
  * `unsafe*` accessors are the seam for library-author backends and are platform-specific (an array
  * and offset on JVM/JS, an interior pointer on Native); ordinary users never need them.
  *
  * Every slice is lowered at construction to an interior pointer plus an `anchor` that keeps a
  * GC-managed backing array reachable; array- and pointer-backed memory then share one branch-free
  * code path. Soundness rests on the non-moving GC guarantee - an interior pointer stays valid for
  * as long as `anchor` is reachable, which every re-slice preserves.
  */
// `anchor` roots the backing array so the interior `unsafePtr` stays valid; its value is never
// observed (only propagated to re-slices), hence @unused.
final class Slice private (@unused private val anchor: AnyRef | Null, val unsafePtr: Ptr[Byte], val length: Int):
  def isEmpty: Boolean = length == 0

  /** A view of the first `n` bytes. Requires `0 <= n <= length`. */
  def take(n: Int): Slice =
    require(0 <= n && n <= length, "take bounds")
    new Slice(anchor, unsafePtr, n)

  /** A view past the first `n` bytes. Requires `0 <= n <= length`. */
  def drop(n: Int): Slice =
    require(0 <= n && n <= length, "drop bounds")
    new Slice(anchor, unsafePtr + n, length - n)

  /** A view of the byte range `[from, until)`. Requires `0 <= from <= until <= length`. */
  def slice(from: Int, until: Int): Slice =
    require(0 <= from && from <= until && until <= length, "slice bounds")
    new Slice(anchor, unsafePtr + from, until - from)

  /** Copies the viewed bytes out to a fresh owned array. */
  def toArray: Array[Byte] =
    val out = new Array[Byte](length)
    if length > 0 then
      val _ = string.memcpy(out.atUnsafe(0), unsafePtr, length.toUSize)
    out

  /** Copies `min(length, dst.length)` bytes into `dst`, returning the number copied. */
  def copyInto(dst: Slice): Int =
    val n = math.min(length, dst.length)
    if n > 0 then
      val _ = string.memmove(dst.unsafePtr, unsafePtr, n.toUSize) // memmove: dst may alias src
    n
end Slice

/** Provides constructors for [[boilerplate.Slice Slice]]. */
object Slice:
  given CanEqual[Slice, Slice] = CanEqual.derived

  /** A view over the whole array. */
  def of(array: Array[Byte]): Slice = of(array, 0, array.length)

  /** A view over `array[offset, offset + length)`. Requires the range to be within `array`. */
  def of(array: Array[Byte], offset: Int, length: Int): Slice =
    require(offset >= 0 && length >= 0 && offset + length <= array.length, "slice bounds")
    if array.length == 0 then empty
    else new Slice(array, array.atUnsafe(offset), length)

  /** A pointer-backed view (the FFI / libuv `(Ptr, len)` world). The caller owns the region's
    * lifetime.
    */
  def of(ptr: Ptr[Byte], length: Int): Slice =
    require(length >= 0, "slice bounds")
    new Slice(null, ptr, length) // scalafix:ok DisableSyntax.null

  /** The empty view. */
  val empty: Slice =
    val anchor = new Array[Byte](1) // stable non-null address for the empty view
    new Slice(anchor, anchor.atUnsafe(0), 0)
end Slice
