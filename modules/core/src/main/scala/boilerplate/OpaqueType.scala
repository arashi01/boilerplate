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
  *     initialisation). Declare such opaque types and their smart constructors directly, without
  *     this mixin.
  *   - '''Parameterised opaque types''' such as `Eff[E, A]`, where the companion object cannot
  *     serve one instantiation per type parameter combination.
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
  * ==The construction vocabulary==
  *
  * The names carry the ecosystem's construction roles: `of` is validated `Either` construction;
  * `ofUnsafe` is its throwing twin for trusted input; `apply` is reserved for construction that
  * cannot fail at runtime - validate literals at compile time and direct non-literal input to
  * `of`/`ofUnsafe`. `wrap` - construction with no validation at all - is `protected`: it is the
  * companion author's tool, never a consumer's. A companion whose own module needs trusted
  * zero-validation construction (an already-validated decode, an operating-system-supplied value)
  * exposes that deliberately and narrowly:
  *
  * {{{
  * object SignalNumber extends OpaqueType[SignalNumber, Int], OpaqueType.Eq[SignalNumber]:
  *   private[emile] inline def trusted(value: Int): SignalNumber = wrap(value)
  *   // ...
  * }}}
  *
  * That vocabulary is for a COMPONENT type - one built from a representation that is not its wire
  * form. A type whose representation IS its wire text has one door instead, `parse`, and mixes in
  * [[OpaqueType$.Wire Wire]] rather than this trait.
  *
  * ==Usage==
  *
  * Define [[Error]], [[wrap]], [[unwrap]], [[apply]], and [[validate]]. `CanEqual` is opt-in via
  * the [[OpaqueType$.Eq Eq]] mixin - security-sensitive types (tokens, keys) omit it, making `==` a
  * compile error - and a wire-text codec is opt-in via the [[OpaqueType$.Codec Codec]] mixin, which
  * names the representation: `OpaqueType.Codec[UserId, String]`.
  *
  * {{{
  * sealed abstract class UserIdError(message: String) extends TypedError(message, None)
  * object UserIdError:
  *   case object Empty extends UserIdError("UserId cannot be empty")
  *
  * opaque type UserId = String
  * object UserId extends OpaqueType[UserId, String], OpaqueType.Eq[UserId]:
  *   type Error = UserIdError
  *
  *   protected inline def wrap(s: String): UserId = s
  *   inline def unwrap(id: UserId): String        = id
  *
  *   inline def apply(inline value: String): UserId =
  *     inline if value == "" then scala.compiletime.error("UserId cannot be empty")
  *     else wrap(value)
  *
  *   protected inline def validate(s: String): Either[Error, String] =
  *     if s.nonEmpty then Right(s) else Left(UserIdError.Empty)
  *
  * UserId("abc")       // literal, validated at construction
  * UserId.of("abc")    // Right(UserId("abc"))
  * }}}
  *
  * `validate` returns the value to wrap, which lets a companion canonicalise while it validates: a
  * normalising type (a lowercase header name, a trimmed identifier) returns the canonical form and
  * every construction path - `of`, `ofUnsafe`, and any codec derived from them - produces the
  * normalised value. A verbatim type returns its input unchanged; the literal `apply` contract is
  * unchanged, so a compile-time-validated literal must be written in canonical form. For types
  * where validation always succeeds, set `Error` to `Nothing` and return `Right(value)`.
  */
transparent trait OpaqueType[A, Repr]:

  /** The typed error produced on validation failure - the module's own
    * [[boilerplate.TypedError TypedError]] root, so a refusal names a case rather than a stock
    * exception. `Nothing` where validation cannot fail.
    */
  type Error <: Throwable

  /** Wraps a raw value with NO validation - the companion author's tool for construction the
    * surrounding code has already made safe. Consumers construct through [[of]], [[ofUnsafe]], or
    * [[apply]]; a module needing a trusted seam exposes its own narrowly-scoped delegate.
    */
  protected inline def wrap(value: Repr): A

  /** Extracts the underlying value from the opaque type. */
  def unwrap(value: A): Repr

  /** Validates and canonicalises the raw value: `Right` carries the value to wrap - the input
    * unchanged for a verbatim type, the canonical form for a normalising one - and `Left` the typed
    * failure.
    */
  protected inline def validate(value: Repr): Either[Error, Repr]

  // The `: A` ascriptions on the wrap applications anchor the inliner's retyping at call sites
  // outside the companion, where the opaque type and its representation are no longer equal;
  // without them expansion of a pattern-bound wrap application fails to typecheck there.

  /** Validated construction: `Right(wrapped)` if valid, `Left(error)` otherwise. */
  final inline def of(value: Repr): Either[Error, A] =
    validate(value) match
      case Right(v) => Right(wrap(v): A)
      case Left(e)  => Left(e)

  /** Validated construction for trusted input, throwing [[Error]] on failure. */
  final inline def ofUnsafe(value: Repr): A =
    validate(value) match
      case Right(v) => wrap(v): A
      case Left(e)  => throw e // scalafix:ok DisableSyntax.throw

  /** Direct construction, reserved for input that cannot fail at RUNTIME: validate literals at
    * compile time with `inline if` + `compiletime.error`, rejecting non-literal input towards
    * [[of]]/[[ofUnsafe]]:
    * {{{
    * inline def apply(inline value: Int): PositiveInt =
    *   inline if value <= 0 then compiletime.error("must be positive")
    *   else wrap(value)
    * }}}
    */
  inline def apply(inline value: Repr): A

end OpaqueType

/** Provides the opt-in [[OpaqueType$.Eq Eq]] and [[OpaqueType$.Codec Codec]] mixins, and the
  * [[OpaqueType$.Wire Wire]] base for types whose representation is their wire text. See
  * [[OpaqueType]] for scope, the construction vocabulary, and usage.
  */
object OpaqueType:

  /** Provides `CanEqual[A, A]` for `==` and `!=`. Omit to forbid value-level equality (e.g. for
    * secret material).
    */
  transparent trait Eq[A]:
    given valueEquality: CanEqual[A, A] = CanEqual.derived

  /** Derives the wire-text codec by composing the representation's own codec with the companion's
    * construction: `decode` reads a `Repr` from the text and passes it through `of` - so a
    * normalising companion decodes to the canonical value - and `encode` renders `unwrap`. Implies
    * no equality: `CanEqual` stays opt-in through [[OpaqueType$.Eq Eq]].
    *
    * The codec's error member is the precise union of both stages, so a decode site tells a
    * malformed text apart from a rejected value:
    *
    * {{{
    * object Port extends OpaqueType[Port, Int], OpaqueType.Eq[Port], OpaqueType.Codec[Port, Int]
    *
    * summon[ValueCodec[Port]].decode(text) // Either[PortError | ValueCodec.Invalid, Port]
    * }}}
    *
    * For `Repr = String` the text stage is infallible, so the union collapses to the companion's
    * own [[OpaqueType.Error Error]]; a total-accept companion (`Error = Nothing`) over `String`
    * yields an infallible codec.
    */
  transparent trait Codec[A, Repr]:
    self: OpaqueType[A, Repr] =>

    /** The derived codec. Inline so the companion's deferred `validate`/`wrap` resolve against the
      * concrete companion at each summon site, which means every summon constructs the codec -
      * summon once, where the seam is built, rather than per request.
      */
    inline given valueCodec(using r: ValueCodec[Repr]): ValueCodec.Aux[A, self.Error | r.Error] =
      ValueCodec[A, self.Error | r.Error](
        text => r.decode(text).flatMap(repr => self.of(repr)),
        value => r.encode(self.unwrap(value))
      )
  end Codec

  /** Base trait for the companion of an opaque type whose representation IS its wire text: a
    * hostname, a PHC password hash, a base64url credential identifier. Its doors are
    * [[Wire.parse parse]] and [[Wire.render render]] - there is no `of`, because there is no
    * separate representation to build from - and it is a [[boilerplate.ValueCodec ValueCodec]] by
    * construction.
    *
    * Define `Error`, `wrap`, `render`, `validate`, and `apply`; `validate` returns the text to
    * wrap, so a normalising type canonicalises there and every door produces the canonical form.
    *
    * {{{
    * opaque type Hostname = String
    * object Hostname extends OpaqueType.Wire[Hostname], OpaqueType.Eq[Hostname]:
    *   type Error = HostnameError
    *
    *   protected inline def wrap(text: String): Hostname = text
    *   def render(value: Hostname): String               = value
    *
    *   protected inline def validate(text: String): Either[HostnameError, String] =
    *     if isHostname(text) then Right(codec.ASCII.lower(text)) else Left(HostnameError(text.length))
    *
    *   inline def apply(inline text: String): Hostname =
    *     inline if text == "" then scala.compiletime.error("Hostname cannot be empty") else wrap(text)
    * }}}
    */
  transparent trait Wire[A]:

    /** The typed error produced when the text is refused - the module's own
      * [[boilerplate.TypedError TypedError]] root, as [[OpaqueType.Error Error]] is.
      */
    type Error <: Throwable

    /** Wraps validated text with NO further checking - the companion author's tool, as
      * [[OpaqueType.wrap wrap]] is for a component type.
      */
    protected inline def wrap(text: String): A

    /** The canonical wire text of `value`. */
    def render(value: A): String

    /** Validates and canonicalises the text: `Right` carries the text to wrap. */
    protected inline def validate(text: String): Either[Error, String]

    /** Validated construction from wire text: `Right(wrapped)` if accepted, `Left(error)`
      * otherwise.
      */
    final inline def parse(text: String): Either[Error, A] =
      validate(text) match
        case Right(v) => Right(wrap(v): A)
        case Left(e)  => Left(e)

    /** Validated construction for trusted text, throwing [[Error]] on refusal. */
    final inline def parseUnsafe(text: String): A =
      validate(text) match
        case Right(v) => wrap(v): A
        case Left(e)  => throw e // scalafix:ok DisableSyntax.throw

    /** Direct construction, reserved for text that cannot be refused at RUNTIME: validate literals
      * at compile time with `inline if` + `compiletime.error`, directing non-literal text to
      * [[parse]]/[[parseUnsafe]].
      */
    inline def apply(inline text: String): A

    /** The codec the doors already are. Inline for the same reason [[OpaqueType$.Codec Codec]]'s
      * is, so every summon constructs it - summon once, where the seam is built, not per request.
      */
    inline given valueCodec: ValueCodec.Aux[A, this.Error] =
      ValueCodec(text => parse(text), value => render(value))
  end Wire
end OpaqueType
