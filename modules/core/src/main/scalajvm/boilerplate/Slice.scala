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
import scala.annotation.tailrec
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.language.experimental.captureChecking

/** A bounds-checked, borrowing view over caller-owned bytes - the ecosystem's one byte-slice
  * vocabulary. A `Slice` never owns, frees, or outlives its backing region: it is a borrower, valid
  * only while the caller keeps that region alive.
  *
  * The class is a bare carrier; every operation lives as an extension in [[Slice$]]. Construct with
  * [[Slice$.of]] (bounds-checked); re-slice with `take`/`drop`/`slice` (each a fresh header over
  * the same memory, no copy); read with `apply`/`readBE`/`readLE`/`contentEquals`; write with
  * `update`/`writeBE`/`writeLE`; copy out with `toArray`/`copyInto`; erase in place with `wipe`.
  * The `unsafe*` accessors are the seam for library-author backends and are platform-specific (an
  * array and offset on JVM/JS, an interior pointer on Native); ordinary users never need them.
  */
final class Slice private (val unsafeArray: Array[Byte], val unsafeOffset: Int, val length: Int)

/** Provides constructors, readers, and re-slicing extensions for [[boilerplate.Slice Slice]]. */
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

  extension (s: Slice^)
    /** True when the view spans no bytes. */
    def isEmpty: Boolean = s.length == 0

    /** True when the view spans at least one byte. */
    def nonEmpty: Boolean = s.length > 0

    /** A view of the first `n` bytes. Requires `0 <= n <= length`. */
    def take(n: Int): Slice^{s} =
      require(0 <= n && n <= s.length, "take bounds")
      new Slice(s.unsafeArray, s.unsafeOffset, n)

    /** A view past the first `n` bytes. Requires `0 <= n <= length`. */
    def drop(n: Int): Slice^{s} =
      require(0 <= n && n <= s.length, "drop bounds")
      new Slice(s.unsafeArray, s.unsafeOffset + n, s.length - n)

    /** A view of the byte range `[from, until)`. Requires `0 <= from <= until <= length`. */
    def slice(from: Int, until: Int): Slice^{s} =
      require(0 <= from && from <= until && until <= s.length, "slice bounds")
      new Slice(s.unsafeArray, s.unsafeOffset + from, until - from)

    /** Copies the viewed bytes out to a fresh owned array. */
    def toArray: Array[Byte] =
      val out = new Array[Byte](s.length)
      System.arraycopy(s.unsafeArray, s.unsafeOffset, out, 0, s.length)
      out

    /** Copies `min(length, dst.length)` bytes into `dst`, returning the number copied. */
    def copyInto(dst: Slice^): Int =
      val n = math.min(s.length, dst.length)
      System.arraycopy(s.unsafeArray, s.unsafeOffset, dst.unsafeArray, dst.unsafeOffset, n)
      n

    /** Overwrites the viewed bytes with zeros in place, for erasing secret material after use.
      *
      * Resists dead-store elimination. On Native the bytes are written through a volatile store the
      * optimiser must keep; on the JVM and Scala.js it is best-effort, as a managed runtime may
      * retain copies (a relocating GC, register spills) beyond the reach of any in-place erase.
      */
    def wipe(): Unit =
      java.util.Arrays.fill(s.unsafeArray, s.unsafeOffset, s.unsafeOffset + s.length, 0.toByte)

    /** Reads the byte at `i`. Requires `0 <= i < length`. */
    def apply(i: Int): Byte =
      require(0 <= i && i < s.length, "index out of range")
      s.unsafeArray(s.unsafeOffset + i)

    /** Writes `value` as the byte at `i`, enabling `s(i) = value` - the write mirror of [[apply]].
      * Requires `0 <= i < length`.
      */
    def update(i: Int, value: Byte): Unit =
      require(0 <= i && i < s.length, "index out of range")
      s.unsafeArray(s.unsafeOffset + i) = value

    /** Compares content for equality. NOT constant-time - use [[constantTimeEquals]] where
      * comparison timing could leak a secret.
      */
    def contentEquals(that: Slice^): Boolean =
      s.length == that.length &&
        java.util.Arrays.equals(
          s.unsafeArray,
          s.unsafeOffset,
          s.unsafeOffset + s.length,
          that.unsafeArray,
          that.unsafeOffset,
          that.unsafeOffset + that.length
        )

    /** Compares content for equality in constant time - the timing reveals nothing about where the
      * bytes differ - for secrets, MACs, or authentication tags where [[contentEquals]] would be a
      * timing oracle. Views of differing length compare unequal; that check is not itself
      * constant-time, as lengths are taken to be public.
      */
    def constantTimeEquals(that: Slice^): Boolean =
      s.length == that.length &&
        ctEquals(s.unsafeArray, s.unsafeOffset, that.unsafeArray, that.unsafeOffset, s.length)

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

    /** Writes `value` - a `Short`, `Int`, or `Long`, per `A` - big-endian at `offset`, without
      * sub-slicing; an out-of-range write raises. The write-side mirror of [[readBE]].
      */
    inline def writeBE[A](offset: Int, value: A): Unit =
      inline erasedValue[A] match
        case _: Short => putBeShort(s, offset, value.asInstanceOf[Short]) // scalafix:ok DisableSyntax.asInstanceOf
        case _: Int   => putBeInt(s, offset, value.asInstanceOf[Int]) // scalafix:ok DisableSyntax.asInstanceOf
        case _: Long  => putBeLong(s, offset, value.asInstanceOf[Long]) // scalafix:ok DisableSyntax.asInstanceOf
        case _        => error("Slice.writeBE writes a Short, Int, or Long")

    /** Writes a little-endian scalar; see [[writeBE]]. */
    inline def writeLE[A](offset: Int, value: A): Unit =
      inline erasedValue[A] match
        case _: Short => putLeShort(s, offset, value.asInstanceOf[Short]) // scalafix:ok DisableSyntax.asInstanceOf
        case _: Int   => putLeInt(s, offset, value.asInstanceOf[Int]) // scalafix:ok DisableSyntax.asInstanceOf
        case _: Long  => putLeLong(s, offset, value.asInstanceOf[Long]) // scalafix:ok DisableSyntax.asInstanceOf
        case _        => error("Slice.writeLE writes a Short, Int, or Long")
  end extension

  // No early-out is deliberate: OR-accumulating every byte keeps the timing independent of where the
  // bytes differ. A short-circuit compare would reintroduce the timing oracle.
  private def ctEquals(a: Array[Byte], ao: Int, b: Array[Byte], bo: Int, n: Int): Boolean =
    @tailrec def go(i: Int, acc: Int): Int =
      if i >= n then acc else go(i + 1, acc | (a(ao + i) ^ b(bo + i)))
    go(0, 0) == 0

  // Concrete-return scalar readers. `@publicInBinary` so the `inline` readBE/readLE may reference
  // them from an expanded call site; kept private so the public surface is just readBE/readLE.
  @publicInBinary private[Slice] def beShort(s: Slice^, o: Int): Short =
    require(o >= 0 && o + 2 <= s.length, "readBE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    (((b(i) & 0xff) << 8) | (b(i + 1) & 0xff)).toShort

  @publicInBinary private[Slice] def beInt(s: Slice^, o: Int): Int =
    require(o >= 0 && o + 4 <= s.length, "readBE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    ((b(i) & 0xff) << 24) | ((b(i + 1) & 0xff) << 16) | ((b(i + 2) & 0xff) << 8) | (b(i + 3) & 0xff)

  @publicInBinary private[Slice] def beLong(s: Slice^, o: Int): Long =
    require(o >= 0 && o + 8 <= s.length, "readBE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    ((b(i) & 0xffL) << 56) | ((b(i + 1) & 0xffL) << 48) | ((b(i + 2) & 0xffL) << 40) |
      ((b(i + 3) & 0xffL) << 32) | ((b(i + 4) & 0xffL) << 24) | ((b(i + 5) & 0xffL) << 16) |
      ((b(i + 6) & 0xffL) << 8) | (b(i + 7) & 0xffL)

  @publicInBinary private[Slice] def leShort(s: Slice^, o: Int): Short =
    require(o >= 0 && o + 2 <= s.length, "readLE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    (((b(i + 1) & 0xff) << 8) | (b(i) & 0xff)).toShort

  @publicInBinary private[Slice] def leInt(s: Slice^, o: Int): Int =
    require(o >= 0 && o + 4 <= s.length, "readLE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    ((b(i + 3) & 0xff) << 24) | ((b(i + 2) & 0xff) << 16) | ((b(i + 1) & 0xff) << 8) | (b(i) & 0xff)

  @publicInBinary private[Slice] def leLong(s: Slice^, o: Int): Long =
    require(o >= 0 && o + 8 <= s.length, "readLE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    ((b(i + 7) & 0xffL) << 56) | ((b(i + 6) & 0xffL) << 48) | ((b(i + 5) & 0xffL) << 40) |
      ((b(i + 4) & 0xffL) << 32) | ((b(i + 3) & 0xffL) << 24) | ((b(i + 2) & 0xffL) << 16) |
      ((b(i + 1) & 0xffL) << 8) | (b(i) & 0xffL)

  // Concrete scalar writers. `@publicInBinary` so the `inline` writeBE/writeLE may reference them
  // from an expanded call site; kept private so the public surface is just writeBE/writeLE.
  @publicInBinary private[Slice] def putBeShort(s: Slice^, o: Int, v: Short): Unit =
    require(o >= 0 && o + 2 <= s.length, "writeBE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    b(i) = (v >>> 8).toByte
    b(i + 1) = v.toByte

  @publicInBinary private[Slice] def putBeInt(s: Slice^, o: Int, v: Int): Unit =
    require(o >= 0 && o + 4 <= s.length, "writeBE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    b(i) = (v >>> 24).toByte
    b(i + 1) = (v >>> 16).toByte
    b(i + 2) = (v >>> 8).toByte
    b(i + 3) = v.toByte

  @publicInBinary private[Slice] def putBeLong(s: Slice^, o: Int, v: Long): Unit =
    require(o >= 0 && o + 8 <= s.length, "writeBE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    b(i) = (v >>> 56).toByte
    b(i + 1) = (v >>> 48).toByte
    b(i + 2) = (v >>> 40).toByte
    b(i + 3) = (v >>> 32).toByte
    b(i + 4) = (v >>> 24).toByte
    b(i + 5) = (v >>> 16).toByte
    b(i + 6) = (v >>> 8).toByte
    b(i + 7) = v.toByte
  end putBeLong

  @publicInBinary private[Slice] def putLeShort(s: Slice^, o: Int, v: Short): Unit =
    require(o >= 0 && o + 2 <= s.length, "writeLE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    b(i) = v.toByte
    b(i + 1) = (v >>> 8).toByte

  @publicInBinary private[Slice] def putLeInt(s: Slice^, o: Int, v: Int): Unit =
    require(o >= 0 && o + 4 <= s.length, "writeLE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    b(i) = v.toByte
    b(i + 1) = (v >>> 8).toByte
    b(i + 2) = (v >>> 16).toByte
    b(i + 3) = (v >>> 24).toByte

  @publicInBinary private[Slice] def putLeLong(s: Slice^, o: Int, v: Long): Unit =
    require(o >= 0 && o + 8 <= s.length, "writeLE out of range")
    val b = s.unsafeArray
    val i = s.unsafeOffset + o
    b(i) = v.toByte
    b(i + 1) = (v >>> 8).toByte
    b(i + 2) = (v >>> 16).toByte
    b(i + 3) = (v >>> 24).toByte
    b(i + 4) = (v >>> 32).toByte
    b(i + 5) = (v >>> 40).toByte
    b(i + 6) = (v >>> 48).toByte
    b(i + 7) = (v >>> 56).toByte
  end putLeLong
end Slice
