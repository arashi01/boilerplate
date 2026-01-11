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
  * Implementors define [[Type]], [[Error]], [[wrap]], [[unwrap]], and [[validate]].
  *
  * {{{
  * opaque type UserId = String
  * object UserId extends OpaqueType[UserId]:
  *   type Type  = String
  *   type Error = IllegalArgumentException
  *
  *   inline def wrap(s: String): UserId   = s
  *   inline def unwrap(id: UserId): String = id
  *
  *   protected def validate(s: String): Option[Error] =
  *     if s.nonEmpty then None else Some(new IllegalArgumentException("empty"))
  *
  * UserId.from("abc")  // Right(UserId("abc"))
  * "abc".as[UserId]    // Right(UserId("abc"))
  * }}}
  */
trait OpaqueType[A]:

  /** The underlying representation type. */
  type Type

  /** The typed error produced on validation failure. Must extend `Throwable`. */
  type Error <: Throwable

  /** Wraps a raw value as the opaque type. No validation is performed. */
  inline def wrap(value: Type): A

  /** Extracts the underlying value from the opaque type. */
  inline def unwrap(value: A): Type

  /** Validates the raw value, returning `None` on success or `Some(error)` on failure.
    *
    * Uses `Option[Error]` rather than the more semantically natural `Error | Unit` union type due
    * to a Scala Native codegen limitation:
    * [[https://github.com/scala-native/scala-native/issues/4747 issue #4747]] forces
    * `nir.Type.Unit` for the entire if-expression when one branch returns `Unit`, breaking union
    * type discrimination at runtime. When
    * [[https://github.com/scala-native/scala-native/pull/4748 PR #4748]] is merged, consider
    * migrating back to `Error | Unit`.
    */
  protected inline def validate(value: Type): Option[Error]

  // Transparent inline preserves singleton type with refinements, enabling =:= evidence at call sites.
  /** Provides this companion as the given instance for extension method resolution. */
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

end OpaqueType

/** Summoning for [[OpaqueType]] instances. */
object OpaqueType:

  /** Summons the [[OpaqueType]] instance for `A`. */
  inline def apply[A](using ot: OpaqueType[A]): OpaqueType[A] = ot

/** Safe construction via extension syntax: `"hello@example.com".as[Email]`. */
extension [B](b: B)
  inline def as[A](using c: OpaqueType[A])(using ev: c.Type =:= B): Either[c.Error, A] =
    c.from(ev.flip(b))

/** Unsafe construction via extension syntax: `"hello@example.com".asUnsafe[Email]`. */
extension [B](b: B)
  inline def asUnsafe[A](using c: OpaqueType[A])(using ev: c.Type =:= B): A =
    c.fromUnsafe(ev.flip(b))

/** Extraction via extension syntax: `email.unwrap`. */
extension [A](a: A)
  inline def unwrap(using c: OpaqueType[A]): c.Type =
    c.unwrap(a)
