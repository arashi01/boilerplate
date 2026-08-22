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

/** An RFC 9562 UUID: 128 bits held as two `Long`s, big-endian, ordered as unsigned octets.
  *
  * Construction sets the version and variant bits and nothing else - the remaining bits are the
  * caller's, from the caller's own generator and clock. No randomness and no clock live here, so a
  * caller chooses its own source and this type stays pure.
  *
  * Refer to [[boilerplate.UUID$ UUID]] for the constructors, the text form, and the instances.
  */
final class UUID private (private val hi: Long, private val lo: Long) derives CanEqual:
  override def equals(that: Any): Boolean = that match
    case other: UUID => hi == other.hi && lo == other.lo
    case _           => false

  override def hashCode: Int = java.lang.Long.hashCode(hi ^ lo)

  override def toString: String = UUID.render(this)

/** Provides the constructors, the text form, and the instances for [[boilerplate.UUID UUID]]. */
object UUID:
  /** All 128 bits zero - RFC 9562's "no such value here" sentinel. */
  val nil: UUID = new UUID(0L, 0L)

  /** All 128 bits one - RFC 9562's "end of list" sentinel. */
  val max: UUID = new UUID(-1L, -1L)

  private def read(bytes: Slice^): UUID = new UUID(bytes.readBE[Long](0), bytes.readBE[Long](8))

  /** The 16 octets as a UUID, verbatim: no version or variant bit is touched. */
  def of(bytes: Slice^): Either[ValueCodec.Invalid, UUID] =
    if bytes.length != 16 then Left(ValueCodec.Invalid("a UUID is 16 bytes")) else Right(read(bytes))

  /** Version 4 over 16 caller-supplied random bytes: the version nibble of octet 6 and the variant
    * bits of octet 8 are overwritten, and the other 122 bits are kept as given.
    */
  def v4(random: Slice^): UUID =
    require(random.length == 16, "v4 takes 16 random bytes")
    val u = read(random)
    new UUID((u.hi & 0xffffffffffff0fffL) | 0x0000000000004000L, (u.lo & 0x3fffffffffffffffL) | 0x8000000000000000L)

  /** Version 7: `unixMillis` in the leading 48 bits, then 74 bits of the caller's 16 random bytes.
    * The first six random bytes are discarded - the timestamp occupies them. `unixMillis` must fit
    * the RFC's 48 unsigned bits, since a wider value would silently lose its high end.
    */
  def v7(unixMillis: Long, random: Slice^): UUID =
    require(random.length == 16, "v7 takes 16 random bytes")
    require(unixMillis >= 0L && unixMillis < (1L << 48), "v7 takes a Unix millisecond timestamp within 48 unsigned bits")
    val u = read(random)
    new UUID((unixMillis << 16) | 0x0000000000007000L | (u.hi & 0x0000000000000fffL), (u.lo & 0x3fffffffffffffffL) | 0x8000000000000000L)

  /** Reads the 8-4-4-4-12 hex form in either letter case, and nothing else: no braces, no `urn:uuid:`
    * prefix, no short or over-long groups, no non-ASCII digit.
    */
  def parse(text: String): Either[ValueCodec.Invalid, UUID] =
    inline def hex(c: Char): Int =
      if c >= '0' && c <= '9' then c - '0'
      else if c >= 'a' && c <= 'f' then c - 'a' + 10
      else if c >= 'A' && c <= 'F' then c - 'A' + 10
      else -1
    if text.length != 36 || text.charAt(8) != '-' || text.charAt(13) != '-' || text.charAt(18) != '-' || text.charAt(23) != '-' then
      Left(ValueCodec.Invalid("not a UUID"))
    else
      // Runs once per inbound identifier on every wire seam; the single walk accumulates straight
      // into the two words, with no intermediate array and no substring per group.
      // scalafix:off DisableSyntax.var, DisableSyntax.while
      var hi = 0L
      var lo = 0L
      var i = 0
      var bad = false
      while !bad && i < 36 do
        if i != 8 && i != 13 && i != 18 && i != 23 then
          val v = hex(text.charAt(i))
          if v < 0 then bad = true
          else if i < 18 then hi = (hi << 4) | v.toLong
          else lo = (lo << 4) | v.toLong
        i += 1
      // scalafix:on DisableSyntax.var, DisableSyntax.while
      if bad then Left(ValueCodec.Invalid("not a UUID")) else Right(new UUID(hi, lo))
    end if
  end parse

  /** The canonical lower-case 8-4-4-4-12 hex form. */
  def render(u: UUID): String =
    inline def digit(n: Int): Char = if n < 10 then ('0' + n).toChar else ('a' + (n - 10)).toChar
    val out = new Array[Char](36)
    // Runs once per outbound identifier on every wire seam, as `parse` does inbound; the nibbles go
    // straight into the 36-character buffer, with no octet array and no substring per group.
    // scalafix:off DisableSyntax.var, DisableSyntax.while
    var nibble = 0
    var at = 0
    while nibble < 32 do
      if at == 8 || at == 13 || at == 18 || at == 23 then
        out(at) = '-'
        at += 1
      val word = if nibble < 16 then u.hi else u.lo
      out(at) = digit(((word >>> ((15 - (nibble & 15)) * 4)) & 0xfL).toInt)
      at += 1
      nibble += 1
    // scalafix:on DisableSyntax.var, DisableSyntax.while
    new String(out)
  end render

  extension (u: UUID)
    /** The version field - bits 48 to 51 - meaningful only for the RFC 9562 variant, so neither
      * [[nil]] nor [[max]] reports a version that means anything.
      */
    def version: Int = ((u.hi >>> 12) & 0xf).toInt

    /** A fresh 16-byte big-endian copy. */
    def toArray: Array[Byte] =
      val out = new Array[Byte](16)
      val s = Slice.of(out)
      s.writeBE[Long](0, u.hi)
      s.writeBE[Long](8, u.lo)
      out

    /** Writes the 16 big-endian octets into the start of `dst`, which must hold at least 16 bytes -
      * the allocation-free path into a wire buffer.
      */
    def copyInto(dst: Slice^): Unit =
      require(dst.length >= 16, "a UUID needs 16 bytes")
      dst.writeBE[Long](0, u.hi)
      dst.writeBE[Long](8, u.lo)
  end extension

  /** Unsigned big-endian octet order - RFC 9562 section 6.11's, which agrees with the order of the
    * canonical text and which signed 64-bit comparison of the halves does not.
    */
  given ordering: Ordering[UUID] = (a, b) =>
    val c = java.lang.Long.compareUnsigned(a.hi, b.hi)
    if c != 0 then c else java.lang.Long.compareUnsigned(a.lo, b.lo)

  given valueCodec: ValueCodec.Aux[UUID, ValueCodec.Invalid] = ValueCodec(parse, render)
end UUID
