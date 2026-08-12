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
package boilerplate.testkit

import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import boilerplate.ValueCodec

/** The [[boilerplate.ValueCodec ValueCodec]] laws as reusable property rows: mix into a
  * `munit.ScalaCheckSuite` and register per instance.
  *
  * {{{
  * class MyCodecsSuite extends ScalaCheckSuite, ValueCodecLaws:
  *   valueCodecLaws[UserId]("UserId")            // canonical values from the Arbitrary
  *   valueCodecNormalisation[HeaderName]("HeaderName", headerTexts)
  * }}}
  */
trait ValueCodecLaws:
  self: ScalaCheckSuite =>

  /** Registers the round-trip and canonical-encode laws: `decode(encode(a)) == Right(a)` and one
    * wire text per value. The generator must produce CANONICAL values - for a normalising type that
    * means values as its companion constructs them, which an `Arbitrary` built from the companion's
    * own `of`/`ofUnsafe` yields by construction.
    */
  def valueCodecLaws[A](name: String)(using codec: ValueCodec[A], arb: Arbitrary[A], ce: CanEqual[A, A]): Unit =
    property(s"$name: decode(encode(a)) == Right(a)"):
      forAll { (a: A) =>
        codec.decode(codec.encode(a)) match
          case Right(b) => b == a
          case Left(_)  => false
      }
    property(s"$name: encode is canonical - re-decode reproduces the same wire text"):
      forAll { (a: A) =>
        val text = codec.encode(a)
        codec.decode(text) match
          case Right(b) => codec.encode(b) == text
          case Left(_)  => false
      }
  end valueCodecLaws

  /** Registers the render-character law: every encoded form stays within `allowed` - for a
    * decimal-bearing instance, the plain-decimal class (ASCII digits, `-`, `.`) catches
    * scientific-notation and locale leakage at every member.
    */
  def valueCodecRenderWithin[A](name: String)(allowed: Char => Boolean)(using codec: ValueCodec[A], arb: Arbitrary[A]): Unit =
    property(s"$name: encode emits only allowed characters"):
      forAll { (a: A) =>
        codec.encode(a).forall(allowed)
      }

  /** Registers the normalisation law over arbitrary wire text: an accepted input decodes to a value
    * whose encoding is a fixed point - `decode` is idempotent through re-encoding. Rejected inputs
    * are outside the law.
    */
  def valueCodecNormalisation[A](name: String, texts: Gen[String])(using codec: ValueCodec[A]): Unit =
    property(s"$name: decode is idempotent through re-encoding"):
      forAll(texts) { text =>
        codec.decode(text) match
          case Left(_)  => true
          case Right(a) =>
            val canonical = codec.encode(a)
            codec.decode(canonical) match
              case Right(b) => codec.encode(b) == canonical
              case Left(_)  => false
      }
end ValueCodecLaws
