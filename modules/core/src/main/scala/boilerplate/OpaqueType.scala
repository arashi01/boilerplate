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
  * ==Scope==
  *
  * This trait is designed for '''value-like''' opaque types - those whose construction semantics
  * reduce to `Repr => A` validation (`UserId`, `Email`, `Timestamp`, `Distance`). It is not
  * intended for:
  *
  *   - '''Resource or handle types''' with complex lifecycles (file descriptors, database
  *     connections, native pointer wrappers, types constructed via FFI calls or requiring effectful
  *     initialisation). Exposing `unwrap` would let consumers retain references whose lifecycle
  *     they do not control. Declare such opaque types and their smart constructors directly,
  *     without this mixin.
  *   - '''Parameterised opaque types''' such as `Eff[F[_], E, A]`, where the companion object
  *     cannot serve as a singleton `given` instance for all type parameter combinations.
  *
  * For opaque types parameterised by a '''phantom type''', provide a nested companion per
  * instantiation:
  *
  * {{{
  * opaque type Distance[U] = Double
  * object Distance:
  *   object Metres extends OpaqueType[Distance[Metres], Double], OpaqueType.Eq[Distance[Metres]]:
  *     // ...
  *   object Feet extends OpaqueType[Distance[Feet], Double], OpaqueType.Eq[Distance[Feet]]:
  *     // ...
  * }}}
  *
  * ==Usage==
  *
  * Define [[Error]], [[wrap]], [[unwrap]], [[apply]], and [[validate]]. CanEqual is opt-in via the
  * [[OpaqueType$.Eq Eq]] mixin - security-sensitive types (tokens, keys, etc.) should omit it.
  *
  * {{{
  * opaque type UserId = String
  * object UserId extends OpaqueType[UserId, String], OpaqueType.Eq[UserId]:
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
  *
  * For types where validation always succeeds, set `Error` to `Nothing` and return `None`:
  *
  * {{{
  * opaque type Timestamp = Long
  * object Timestamp extends OpaqueType[Timestamp, Long], OpaqueType.Eq[Timestamp]:
  *   type Error = Nothing
  *   protected inline def validate(value: Long): Option[Nothing] = None
  *   // ...
  * }}}
  *
  * For types where equality comparison should be forbidden (e.g. secret tokens, password hashes),
  * simply omit the [[OpaqueType$.Eq Eq]] mixin:
  *
  * {{{
  * opaque type SecretToken = String
  * object SecretToken extends OpaqueType[SecretToken, String]:
  *   // No OpaqueType.Eq - comparing tokens with == is a compile error
  * }}}
  *
  * @tparam A The opaque type.
  * @tparam Repr The underlying representation type.
  */
transparent trait OpaqueType[A, Repr]:

  /** The typed error produced on validation failure. Must extend `Throwable`. */
  type Error <: Throwable

  /** Wraps a raw value as the opaque type. No validation is performed. */
  def wrap(value: Repr): A

  /** Extracts the underlying value from the opaque type. */
  def unwrap(value: A): Repr

  /** Validates the raw value, returning `None` on success or `Some(error)` on failure. */
  protected inline def validate(value: Repr): Option[Error]

  /** Provides this companion as the `given` instance for extension method resolution. */
  final transparent inline given OpaqueType[A, Repr] = this

  /** Safe construction returning `Right(wrapped)` if valid, `Left(error)` otherwise. */
  final inline def from(value: Repr): Either[Error, A] =
    validate(value) match
      case None    => Right(wrap(value))
      case Some(e) => Left(e)

  /** Unsafe construction that throws [[Error]] on validation failure. */
  final inline def fromUnsafe(value: Repr): A =
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
  inline def apply(inline value: Repr): A

end OpaqueType

/** Companion providing summoning and the opt-in [[OpaqueType$.Eq Eq]] mixin for multiversal
  * equality. See [[OpaqueType]] for scope and usage guidance.
  */
object OpaqueType:

  /** Summons the [[OpaqueType]] instance for `A`. */
  inline def apply[A, Repr](using ot: OpaqueType[A, Repr]): OpaqueType[A, Repr] = ot

  /** Provides `CanEqual[A, A]` for `==` and `!=`. Omit to forbid value-level equality (e.g. for
    * secret material).
    */
  transparent trait Eq[A]:
    given CanEqual[A, A] = CanEqual.derived

/** Safe construction via extension syntax: `"hello@example.com".as[Email]`. */
extension [B](b: B)
  transparent inline def as[A](using c: OpaqueType[A, B]): Either[c.Error, A] =
    c.from(b)

/** Unsafe construction via extension syntax: `"hello@example.com".asUnsafe[Email]`. */
extension [B](b: B)
  transparent inline def asUnsafe[A](using c: OpaqueType[A, B]): A =
    c.fromUnsafe(b)

/** Direct construction via extension syntax: `42.const[PositiveInt]`.
  *
  * Delegates to [[OpaqueType.apply]]. For companions that override `apply` with `inline if` +
  * `compiletime.error`, constant propagation works through the `inline` parameter chain.
  */
extension [B](inline b: B)
  transparent inline def const[A](using c: OpaqueType[A, B]): A =
    c.apply(b)

/** Extraction via extension syntax: `email.unwrap`. */
extension [A](a: A)
  transparent inline def unwrap[Repr](using c: OpaqueType[A, Repr]): Repr =
    c.unwrap(a)
