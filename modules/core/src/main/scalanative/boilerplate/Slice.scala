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

import scala.annotation.publicInBinary
import scala.annotation.unused
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.scalanative.libc.string
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsafe.UnsafeRichArray
import scala.scalanative.unsigned.*

/** A bounds-checked, immutable view over caller-owned bytes - the ecosystem's one byte-slice
  * vocabulary. A `Slice` never owns, frees, or outlives its backing region: it is a borrower, valid
  * only while the caller keeps that region alive.
  *
  * The class is a bare carrier; every operation lives as an extension in [[Slice$]]. Construct with
  * [[Slice$.of]] (bounds-checked); re-slice with `take`/`drop`/`slice` (each a fresh header over
  * the same memory, no copy); read with `apply`/`readBE`/`readLE`/`contentEquals`; copy out with
  * `toArray`/`copyInto`. The `unsafe*` accessors are the seam for library-author backends and are
  * platform-specific (an array and offset on JVM/JS, an interior pointer on Native); ordinary users
  * never need them.
  *
  * Every slice is lowered at construction to an interior pointer plus an `anchor` that keeps a
  * GC-managed backing array reachable; array- and pointer-backed memory then share one branch-free
  * code path. Soundness rests on the non-moving GC guarantee - an interior pointer stays valid for
  * as long as `anchor` is reachable, which every re-slice preserves.
  */
// `anchor` roots the backing array so the interior `unsafePtr` stays valid; its value is never
// observed (only propagated to re-slices), hence @unused.
final class Slice private (@unused private val anchor: AnyRef | Null, val unsafePtr: Ptr[Byte], val length: Int)

/** Provides constructors, readers, and re-slicing extensions for [[boilerplate.Slice Slice]]. */
object Slice:
  given CanEqual[Slice, Slice] = CanEqual.derived

  /** A view over the whole array. */
  def of(array: Array[Byte]): Slice = of(array, 0, array.length)

  /** A view over `array[offset, offset + length)`. Requires the range to be within `array`. */
  def of(array: Array[Byte], offset: Int, length: Int): Slice =
    require(offset >= 0 && length >= 0 && offset + length <= array.length, "slice bounds")
    if array.length == 0 then empty
    else new Slice(array, array.atUnsafe(offset), length)

  /** A pointer-backed view over a caller-owned region (the FFI `(Ptr, len)` world). The caller
    * keeps the region alive for as long as any slice derived from it is used; prefer [[borrowing]]
    * for a scoped lifetime.
    */
  def of(ptr: Ptr[Byte], length: Int): Slice =
    require(length >= 0, "slice bounds")
    new Slice(null, ptr, length) // scalafix:ok DisableSyntax.null

  /** The empty view. */
  val empty: Slice =
    val anchor = new Array[Byte](1) // stable non-null address for the empty view
    new Slice(anchor, anchor.atUnsafe(0), 0)

  /** Runs `f` with a pointer-backed `Slice` valid only for that call - the scoped, safe shape over
    * the raw [[of]] `(ptr, length)` seam. The caller keeps the region alive across `f` (e.g. an
    * enclosing `stackalloc`/`Zone`); copy out with `toArray` to persist a value beyond it.
    */
  inline def borrowing[A](ptr: Ptr[Byte], length: Int)(f: Slice => A): A =
    f(of(ptr, length))

  extension (s: Slice)
    /** True when the view spans no bytes. */
    def isEmpty: Boolean = s.length == 0

    /** A view of the first `n` bytes. Requires `0 <= n <= length`. */
    def take(n: Int): Slice =
      require(0 <= n && n <= s.length, "take bounds")
      new Slice(s.anchor, s.unsafePtr, n)

    /** A view past the first `n` bytes. Requires `0 <= n <= length`. */
    def drop(n: Int): Slice =
      require(0 <= n && n <= s.length, "drop bounds")
      new Slice(s.anchor, s.unsafePtr + n, s.length - n)

    /** A view of the byte range `[from, until)`. Requires `0 <= from <= until <= length`. */
    def slice(from: Int, until: Int): Slice =
      require(0 <= from && from <= until && until <= s.length, "slice bounds")
      new Slice(s.anchor, s.unsafePtr + from, until - from)

    /** Copies the viewed bytes out to a fresh owned array. */
    def toArray: Array[Byte] =
      val out = new Array[Byte](s.length)
      if s.length > 0 then
        val _ = string.memcpy(out.atUnsafe(0), s.unsafePtr, s.length.toUSize)
      out

    /** Copies `min(length, dst.length)` bytes into `dst`, returning the number copied. */
    def copyInto(dst: Slice): Int =
      val n = math.min(s.length, dst.length)
      if n > 0 then
        val _ = string.memmove(dst.unsafePtr, s.unsafePtr, n.toUSize) // memmove: dst may alias src
      n

    /** Reads the byte at `i`. Requires `0 <= i < length`. */
    def apply(i: Int): Byte =
      require(0 <= i && i < s.length, "index out of range")
      s.unsafePtr(i)

    /** Compares content for equality via `memcmp`. NOT constant-time - unsuitable where comparison
      * timing could leak a secret.
      */
    def contentEquals(that: Slice): Boolean =
      s.length == that.length &&
        (s.length == 0 || string.memcmp(s.unsafePtr, that.unsafePtr, s.length.toUSize) == 0)

    /** Reads a big-endian `Short`, `Int`, or `Long` (per `A`) at `offset`, without sub-slicing; an
      * out-of-range read raises.
      */
    inline def readBE[A](offset: Int): A =
      inline erasedValue[A] match
        case _: Short => beShort(s, offset).asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf
        case _: Int   => beInt(s, offset).asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf
        case _: Long  => beLong(s, offset).asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf
        case _        => error("Slice.readBE reads a Short, Int, or Long")

    /** Reads a little-endian scalar; see [[readBE]]. */
    inline def readLE[A](offset: Int): A =
      inline erasedValue[A] match
        case _: Short => leShort(s, offset).asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf
        case _: Int   => leInt(s, offset).asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf
        case _: Long  => leLong(s, offset).asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf
        case _        => error("Slice.readLE reads a Short, Int, or Long")
  end extension

  // Concrete-return scalar readers. `@publicInBinary` so the `inline` readBE/readLE may reference
  // them from an expanded call site; kept private so the public surface is just readBE/readLE.
  @publicInBinary private[Slice] def beShort(s: Slice, o: Int): Short =
    require(o >= 0 && o + 2 <= s.length, "readBE out of range")
    val p = s.unsafePtr
    (((p(o) & 0xff) << 8) | (p(o + 1) & 0xff)).toShort

  @publicInBinary private[Slice] def beInt(s: Slice, o: Int): Int =
    require(o >= 0 && o + 4 <= s.length, "readBE out of range")
    val p = s.unsafePtr
    ((p(o) & 0xff) << 24) | ((p(o + 1) & 0xff) << 16) | ((p(o + 2) & 0xff) << 8) | (p(o + 3) & 0xff)

  @publicInBinary private[Slice] def beLong(s: Slice, o: Int): Long =
    require(o >= 0 && o + 8 <= s.length, "readBE out of range")
    val p = s.unsafePtr
    ((p(o) & 0xffL) << 56) | ((p(o + 1) & 0xffL) << 48) | ((p(o + 2) & 0xffL) << 40) |
      ((p(o + 3) & 0xffL) << 32) | ((p(o + 4) & 0xffL) << 24) | ((p(o + 5) & 0xffL) << 16) |
      ((p(o + 6) & 0xffL) << 8) | (p(o + 7) & 0xffL)

  @publicInBinary private[Slice] def leShort(s: Slice, o: Int): Short =
    require(o >= 0 && o + 2 <= s.length, "readLE out of range")
    val p = s.unsafePtr
    (((p(o + 1) & 0xff) << 8) | (p(o) & 0xff)).toShort

  @publicInBinary private[Slice] def leInt(s: Slice, o: Int): Int =
    require(o >= 0 && o + 4 <= s.length, "readLE out of range")
    val p = s.unsafePtr
    ((p(o + 3) & 0xff) << 24) | ((p(o + 2) & 0xff) << 16) | ((p(o + 1) & 0xff) << 8) | (p(o) & 0xff)

  @publicInBinary private[Slice] def leLong(s: Slice, o: Int): Long =
    require(o >= 0 && o + 8 <= s.length, "readLE out of range")
    val p = s.unsafePtr
    ((p(o + 7) & 0xffL) << 56) | ((p(o + 6) & 0xffL) << 48) | ((p(o + 5) & 0xffL) << 40) |
      ((p(o + 4) & 0xffL) << 32) | ((p(o + 3) & 0xffL) << 24) | ((p(o + 2) & 0xffL) << 16) |
      ((p(o + 1) & 0xffL) << 8) | (p(o) & 0xffL)
end Slice
