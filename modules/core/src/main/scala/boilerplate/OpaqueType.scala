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

/** Base trait for opaque type companion objects providing validated construction.
  *
  * Define [[Type]], [[Error]], [[wrap]], [[unwrap]], [[apply]], and [[validate]]. See
  * [[boilerplate.OpaqueType$ OpaqueType]] companion for summoning.
  *
  * {{{
  * opaque type UserId = String
  * object UserId extends OpaqueType[UserId]:
  *   type Type  = String
  *   type Error = IllegalArgumentException
  *
  *   inline def wrap(s: String): UserId    = s
  *   inline def unwrap(id: UserId): String  = id
  *   inline def apply(inline value: String): UserId = fromUnsafe(value)
  *
  *   protected inline def validate(s: String): Option[Error] =
  *     if s.nonEmpty then None else Some(new IllegalArgumentException("empty"))
  *
  * UserId("abc")       // UserId("abc") - throws on invalid input
  * UserId.from("abc")  // Right(UserId("abc"))
  * "abc".as[UserId]    // Right(UserId("abc"))
  * "abc".unwrap        // "abc"
  * }}}
  */
transparent trait OpaqueType[A]:

  /** The underlying representation type. */
  type Type

  /** The typed error produced on validation failure. Must extend `Throwable`. */
  type Error <: Throwable

  /** Wraps a raw value as the opaque type. No validation is performed. */
  def wrap(value: Type): A

  /** Extracts the underlying value from the opaque type. */
  def unwrap(value: A): Type

  /** Validates the raw value, returning `None` on success or `Some(error)` on failure. */
  protected inline def validate(value: Type): Option[Error]

  /** Provides this companion as the `given` instance for extension method resolution. */
  final transparent inline given OpaqueType[A] = this

  /** Provides multiversal equality for the opaque type. */
  given CanEqual[A, A] = CanEqual.derived

  /** Safe construction returning `Right(wrapped)` if valid, `Left(error)` otherwise. */
  final inline def from(value: Type): Either[Error, A] =
    validate(value) match
      case None    => Right(wrap(value))
      case Some(e) => Left(e)

  /** Unsafe construction that throws [[Error]] on validation failure. */
  final inline def fromUnsafe(value: Type): A =
    validate(value) match
      case None    => wrap(value)
      case Some(e) => throw e // scalafix:ok

  /** Direct construction. For runtime-only validation, delegate to [[fromUnsafe]]:
    * {{{
    * inline def apply(inline value: String): UserId = fromUnsafe(value)
    * }}}
    * For compile-time validation of literals, use `inline if` + `compiletime.error`:
    * {{{
    * inline def apply(inline value: Int): PositiveInt =
    *   inline if value <= 0 then compiletime.error("must be positive")
    *   else wrap(value)
    * }}}
    */
  inline def apply(inline value: Type): A

end OpaqueType

/** Companion providing summoning for [[OpaqueType]] instances. */
object OpaqueType:

  /** Summons the [[OpaqueType]] instance for `A`. */
  inline def apply[A](using ot: OpaqueType[A]): OpaqueType[A] = ot

/** Safe construction via extension syntax: `"hello@example.com".as[Email]`. */
extension [B](b: B)
  transparent inline def as[A](using c: OpaqueType[A])(using ev: c.Type =:= B): Either[c.Error, A] =
    c.from(ev.flip(b))

/** Unsafe construction via extension syntax: `"hello@example.com".asUnsafe[Email]`. */
extension [B](b: B)
  transparent inline def asUnsafe[A](using c: OpaqueType[A])(using ev: c.Type =:= B): A =
    c.fromUnsafe(ev.flip(b))

/** Direct construction via extension syntax: `42.const[PositiveInt]`.
  *
  * Delegates to [[OpaqueType.apply]]. For companions that override `apply` with `inline if` +
  * `compiletime.error`, use the direct `Companion(literal)` syntax instead - the `=:=` evidence
  * conversion prevents inline constant propagation through this extension.
  */
extension [B](inline b: B)
  transparent inline def const[A](using c: OpaqueType[A])(using ev: c.Type =:= B): A =
    c.apply(ev.flip(b))

/** Extraction via extension syntax: `email.unwrap`. */
extension [A](a: A)
  transparent inline def unwrap(using c: OpaqueType[A]): c.Type =
    c.unwrap(a)
