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

import boilerplate.OpaqueType
import boilerplate.TypedError
import boilerplate.ValueCodec
import boilerplate.codec.ASCII

final case class KitError(detail: String) extends TypedError(detail, None)

opaque type KitName = String
object KitName extends OpaqueType[KitName, String], OpaqueType.Eq[KitName], OpaqueType.Codec[KitName]:
  type Error = KitError
  protected inline def wrap(s: String): KitName = s
  def unwrap(n: KitName): String = n
  protected inline def validate(s: String): Either[KitError, String] =
    if s.isEmpty then Left(KitError("empty")) else Right(ASCII.lower(s))
  inline def apply(inline value: String): KitName = ofUnsafe(value)

class ValueCodecLawsSuite extends ScalaCheckSuite, ValueCodecLaws:

  // Canonical values by construction: generated through the companion's own ofUnsafe.
  private given Arbitrary[KitName] = Arbitrary(Gen.alphaStr.filter(_.nonEmpty).map(KitName.ofUnsafe))

  valueCodecLaws[Int]("Int")
  valueCodecLaws[Long]("Long")
  valueCodecLaws[Boolean]("Boolean")
  valueCodecLaws[String]("String")
  valueCodecLaws[KitName]("KitName")
  valueCodecNormalisation[KitName]("KitName", Gen.alphaStr)
  valueCodecNormalisation[Int]("Int", Gen.numStr)
  valueCodecRenderWithin[Int]("Int")(c => ASCII.isDigit(c) || c == '-')
  valueCodecRenderWithin[Long]("Long")(c => ASCII.isDigit(c) || c == '-')
end ValueCodecLawsSuite
