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

/** Scalar wire-text codec: the one `String <-> A` seam for path captures, query parameters, header
  * values, form fields, environment variables, and command arguments. Body payloads, with their
  * streams and media types, are a downstream concern, and decoding is pure - effectful or streaming
  * decode belongs to the layers that own those capabilities.
  *
  * The typed failure travels as the abstract [[ValueCodec.Error Error]] member: a domain scalar
  * surfaces its own sealed family, so a direct decode site branches exhaustively, while a generic
  * consumer widens `Error <: Throwable` for free. Every constructor and given here carries the
  * member precisely ([[ValueCodec$.Aux Aux]]); a seam returning bare `ValueCodec[A]` erases the
  * family and with it exhaustivity, so erasure is the consumer's deliberate act, never this
  * library's default.
  *
  * Laws: `decode(encode(a)) == Right(a)`; `encode` is total and canonical - one wire text per
  * value; a normalising `decode` is idempotent through re-encoding. The test-kit artefact carries
  * these as reusable law suites.
  *
  * Codecs for [[boilerplate.Secret Secret]] and [[boilerplate.Slice Slice]] are refused at compile
  * time: an encode would render secret material to an immutable `String`, or a borrowed view past
  * its lifetime. The refusals live in this companion's implicit scope, so a deliberate local
  * instance can still override them - the guard catches accident, not intent.
  */
trait ValueCodec[A]:
  /** The typed failure a decode can produce; `Nothing` for an infallible scalar. */
  type Error <: Throwable

  def decode(text: String): Either[Error, A]

  def encode(value: A): String

/** Provides the precise alias, the constructor, the primitive givens, and the compile-time refusals
  * for [[boilerplate.ValueCodec ValueCodec]].
  */
object ValueCodec:

  /** [[boilerplate.ValueCodec ValueCodec]] with the `Error` member made precise - the form every
    * seam that hands codecs onward should name, so exhaustive branching survives the crossing.
    */
  type Aux[A, E <: Throwable] = ValueCodec[A] { type Error = E }

  /** Decode failure for scalars with no richer error family. The message names the violated
    * constraint and never carries the offending input: the call site already holds the input, and a
    * logged failure must not replay it.
    */
  final case class Invalid(detail: String) extends TypedError(detail, None)

  /** Creates a codec from its two functions, preserving the error member. */
  def apply[A, E <: Throwable](parse: String => Either[E, A], render: A => String): Aux[A, E] =
    new ValueCodec[A]:
      type Error = E
      def decode(text: String): Either[E, A] = parse(text)
      def encode(value: A): String = render(value)

  given string: Aux[String, Nothing] = ValueCodec(Right(_), identity)

  given int: Aux[Int, Invalid] = ValueCodec(s => s.toIntOption.toRight(Invalid("not an integer")), _.toString)

  given long: Aux[Long, Invalid] = ValueCodec(s => s.toLongOption.toRight(Invalid("not an integer")), _.toString)

  given boolean: Aux[Boolean, Invalid] = ValueCodec(
    {
      case "true"  => Right(true)
      case "false" => Right(false)
      case _       => Left(Invalid("not a boolean"))
    },
    v => if v then "true" else "false"
  )

  /** Refused: encoding would render secret material to an immutable `String` outside every wipe
    * guarantee. Summoning this is a compile error.
    */
  inline given secret: ValueCodec[Secret] =
    scala.compiletime.error(
      "a ValueCodec[Secret] must not exist: encode would render secret material to an immutable String"
    )

  /** Refused: a borrowed view must not be encoded past its lifetime. Summoning this is a compile
    * error.
    */
  inline given slice: ValueCodec[Slice] =
    scala.compiletime.error("a ValueCodec[Slice] must not exist: a borrowed view must not be encoded past its lifetime")
end ValueCodec
