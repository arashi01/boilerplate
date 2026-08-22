# Boilerplate

Foundational Scala 3 utilities for opaque type construction, null-safe handling, native platform detection, and zero-cost typed-error effects - targeting JVM, JS, and Native.

## Installation

Each module is published independently. Add the ones you need:

```scala
// Core: opaque types, nullable extensions
libraryDependencies += "africa.shuwari" %% "boilerplate" % "<version>"

// Effect: typed-error effects atop cats-effect
libraryDependencies += "africa.shuwari" %% "boilerplate-effect" % "<version>"

// Streams: the fs2 vocabulary over the typed channel
libraryDependencies += "africa.shuwari" %% "boilerplate-fs2" % "<version>"

// Test kits: ValueCodec law suites; Eff generators and law instances
libraryDependencies += "africa.shuwari" %% "boilerplate-testkit" % "<version>" % Test
libraryDependencies += "africa.shuwari" %% "boilerplate-effect-testkit" % "<version>" % Test
```

On Scala.js and Scala Native, `%%` resolves the platform-specific artefact (the sbt 2.x replacement for `%%%`).

`boilerplate-native` (compile-time OS/architecture detection) is Native-only and published as a per-OS/arch classified NIR library. Consume it through [sbt-snx](https://github.com/shuwariafrica/sbt-snx) so the classifier for your build target resolves automatically:

```scala
SNX.dependencies += "africa.shuwari" %% "boilerplate-native" % "<version>" % NativeClassifier
```

---

## Core

```scala
import boilerplate.*
```

### OpaqueType

`OpaqueType[A, Repr]` is a base trait for opaque type companion objects providing validated
construction under the ecosystem's construction vocabulary: `of` is validated `Either`
construction, `ofUnsafe` its throwing twin for trusted input, and `apply` is reserved for
construction that cannot fail at runtime - compile-time-validated literals. `wrap` - construction
with no validation at all - is `protected`: the companion author's tool, never part of a derived
companion's public API.

That vocabulary is for a **component** type - one built from a representation that is not its wire
text. A type whose representation *is* its wire text (a hostname, a PHC password hash) has one door
instead, `parse`, and mixes in `OpaqueType.Wire[A]`; see [wire-form types](#wire-form-types) below.

**Multiversal equality is opt-in** via `OpaqueType.Eq[A]`. Security-sensitive types (tokens, keys,
password hashes) should omit it, making `==` a compile error under `strictEquality`.

#### Defining an opaque type

```scala
import boilerplate.*

sealed abstract class UserIdError(message: String) extends TypedError(message, None)
object UserIdError:
  case object Empty extends UserIdError("UserId cannot be empty")

opaque type UserId = String

object UserId extends OpaqueType[UserId, String], OpaqueType.Eq[UserId]:
  type Error = UserIdError

  protected inline def wrap(s: String): UserId = s
  inline def unwrap(id: UserId): String        = id

  inline def apply(inline value: String): UserId =
    inline if value == "" then compiletime.error("UserId cannot be empty")
    else wrap(value)

  protected inline def validate(s: String): Either[Error, String] =
    if s.nonEmpty then Right(s) else Left(UserIdError.Empty)
```

`Error` is bounded by `Throwable`, but the family a companion refuses with is the module's own
[`TypedError`](#typederror) root, so a decode site branches over named cases rather than over a
stock exception.

`validate` returns the value to wrap, so a companion canonicalises while it validates: a
normalising type (a lowercase header name, a trimmed identifier) returns the canonical form and
every construction path produces the normalised value. Verbatim types return the input unchanged;
total types set `Error = Nothing` and return `Right(value)`.

`UserId("user-123")` validates the literal at compile time - an invalid constant is a compile
error, and a non-constant argument fails to reduce, directing the caller to the validated
constructors:

```scala
val direct: UserId                      = UserId("user-123")
val safe: Either[UserIdError, UserId]   = UserId.of(input)
val trusted: UserId                     = UserId.ofUnsafe(input) // throws Error on invalid
val underlying: String                  = UserId.unwrap(direct)
```

A module whose own code needs trusted zero-validation construction (an already-validated decode,
an operating-system-supplied value) exposes that deliberately and narrowly over the protected
`wrap`:

```scala
object SignalNumber extends OpaqueType[SignalNumber, Int], OpaqueType.Eq[SignalNumber]:
  private[mylib] inline def trusted(value: Int): SignalNumber = wrap(value)
  // ...
```

For types where equality comparison should be forbidden, omit the `Eq` mixin:

```scala
opaque type SecretToken = String

object SecretToken extends OpaqueType[SecretToken, String]:
  type Error = TokenError
  // ...
  // SecretToken values cannot be compared with == (compile error under strictEquality)
```

#### API summary

| Member              | Description                                                        |
|---------------------|--------------------------------------------------------------------|
| `Repr` (type param) | Underlying representation type                                     |
| `type Error`        | Validation error type (must extend `Throwable`)                    |
| `wrap(value)`       | `protected` - unvalidated construction, the companion author's tool|
| `unwrap(value)`     | Extracts the underlying value                                      |
| `apply(value)`      | Compile-time-validated literal construction                        |
| `validate(value)`   | Returns `Right(valueToWrap)` (canonical form) or `Left(error)`     |
| `of(value)`         | Validated construction returning `Either[Error, A]`                |
| `ofUnsafe(value)`   | Validated construction for trusted input; throws `Error`           |
| `OpaqueType.Eq[A]`  | Mixin providing `CanEqual[A, A]` (opt-in equality)                 |
| `OpaqueType.Codec[A, Repr]` | Mixin deriving a `ValueCodec` from the representation's codec and `of` |

#### Wire-form types

Some types have no representation distinct from their wire text: a hostname is a `String`, a PHC
password hash is a `String`, a base64url credential identifier is a `String`. Giving those an `of`
door alongside a `parse` one would be two names for a single act, so `OpaqueType.Wire[A]` ships the
one door and derives the codec from it:

```scala
opaque type Hostname = String

object Hostname extends OpaqueType.Wire[Hostname], OpaqueType.Eq[Hostname]:
  type Error = HostnameError

  protected inline def wrap(text: String): Hostname = text
  def render(value: Hostname): String               = value

  protected inline def validate(text: String): Either[HostnameError, String] =
    if isHostname(text) then Right(codec.ASCII.lower(text)) else Left(HostnameError(text.length))

  inline def apply(inline text: String): Hostname =
    inline if text == "" then compiletime.error("Hostname cannot be empty") else wrap(text)
```

```scala
val parsed: Either[HostnameError, Hostname] = Hostname.parse(input)
val trusted: Hostname                       = Hostname.parseUnsafe(input) // throws Error
val literal: Hostname                       = Hostname("example.com")     // validated at compile time
val text: String                            = Hostname.render(literal)
val codec: ValueCodec.Aux[Hostname, HostnameError] = summon                // by construction
```

`validate` returns the text to wrap, so a normalising type canonicalises there and every door -
`parse`, `parseUnsafe`, and the derived codec - produces the canonical form. There is no `of` and
no `ofUnsafe`: `Hostname.of("x")` is a compile error, which is the point.

| Member              | Description                                                        |
|---------------------|--------------------------------------------------------------------|
| `type Error`        | Typed error produced when the text is refused                      |
| `wrap(text)`        | `protected` - unvalidated construction, the companion author's tool |
| `render(value)`     | The canonical wire text                                            |
| `validate(text)`    | Returns `Right(textToWrap)` (canonical form) or `Left(error)`      |
| `parse(text)`       | Validated construction returning `Either[Error, A]`                |
| `parseUnsafe(text)` | Validated construction for trusted text; throws `Error`            |
| `apply(text)`       | Compile-time-validated literal construction                        |

---

### nullable

Type-safe null elimination for Scala 3 explicit nulls (`-Yexplicit-nulls`).

```scala
import boilerplate.nullable.*
```

#### Extensions on `A | Null`

```scala
val value: String | Null = javaMethod()

value.option                          // Option[String]
value.either("was null")              // Either[String, String]
value.getOrElse("fallback")           // String
value.unsafe                          // String (throws NPE if null)
value.unsafe("descriptive message")   // String (throws NPE with message if null)
value.fold("default")(_.toUpperCase)  // String - no intermediate Option
value.mapOpt(_.length)                // Option[Int]
value.flatMapOpt(s => Option(s))      // Option[String]
```

#### Extensions on `Option[A | Null]`

Useful when `Option`-returning APIs hand back nullable inner values from Java interop.

```scala
val opt: Option[String | Null] = Some(javaMethod())

opt.flattenNull             // Option[String] - Some(null) becomes None
opt.mapNull(_.toUpperCase)  // Option[String]
opt.flatMapNull(s => Some(s.trim))  // Option[String]
```

#### Extensions on `Either[E, A | Null]`

```scala
val result: Either[String, String | Null] = Right(javaMethod())

result.flattenNull("null value")              // Either[String, String]
result.mapNull("null value")(_.toUpperCase)   // Either[String, String]
result.flatMapNull("null value")(s => Right(s.trim))  // Either[String, String]
```

---

### Slice

`Slice` is a bounds-checked, borrowing view over caller-owned bytes - one byte-slice vocabulary
across the ecosystem. A `Slice` never owns, frees, or outlives its backing region: it is a borrower,
valid only while the caller keeps that region alive.

```scala
import boilerplate.Slice

val buf: Array[Byte] = receive()
val header = Slice.of(buf).take(8)      // a view of the first 8 bytes - no copy
val body   = Slice.of(buf).drop(8)      // the rest - no copy
val owned  = header.toArray             // copy out to an owned Array[Byte]
```

Re-slicing (`take`/`drop`/`slice`) allocates only a small header over the same memory; `toArray` and
`copyInto` copy out.

**Reading and writing scalars.** `apply(i)` reads a byte and `s(i) = b` writes one; `readBE`/`readLE`
decode a `Short`, `Int`, or `Long` at an offset without sub-slicing, and `writeBE`/`writeLE` encode
one back in place - allocation-free, so prefer them in hot codecs over re-slicing per byte. `contentEquals` compares
bytes but is **not** constant-time; use `constantTimeEquals` for secret-dependent comparison (MACs,
tags). These operations trust their bounds: an out-of-range access raises.

```scala
val s = Slice.of(new Array[Byte](4))
s.writeBE[Int](0, 256)  // bytes now 00 00 01 00
s(2)                    // 1: Byte
s.readBE[Int](0)        // 256
s.readLE[Int](0)        // 65536
```

**Erasing secrets.** `wipe` zeros the viewed bytes in place once a secret is no longer needed. On
Native the erase goes through a volatile store the optimiser cannot drop; on the JVM and Scala.js it
is best-effort, as a managed runtime may retain copies (a relocating GC, register spills) beyond its
reach.

**Untrusted bounds.** For wire input whose bounds are attacker-controlled, `sliceOrError` returns a
typed error rather than raising:

```scala
Slice.of(frame).sliceOrError(offset, offset + len) match
  case Right(field)                          => decode(field)
  case Left(SliceError.OutOfBounds(_, _, _)) => reject()
```

**Scala Native.** `Slice.of(ptr, len)` views pointer-backed memory (the FFI `(Ptr, len)` world) whose
lifetime the caller owns; `Slice.borrowing(ptr, len) { s => ... }` scopes that view to the block, and
the scope is **enforced**, not merely documented:

```scala
import language.experimental.captureChecking
import boilerplate.Slice

Slice.borrowing(ptr, len)(s => s.toArray)             // fine - copies out
Slice.borrowing(ptr, len)(s => s.drop(1).take(2).toArray) // fine - re-slice, then copy out
Slice.borrowing(ptr, len)(s => s)                     // rejected: the view outlives its scope
Slice.borrowing(ptr, len)(s => IO.pure(s))            // rejected: so does a suspended effect holding it
Slice.borrowing(ptr, len)(s => s.take(2))             // rejected: so does a view re-sliced from it
Slice.borrowing(ptr, len)(s => s.sliceOrError(0, 2))  // rejected: however the view is wrapped
```

The continuation takes a `Slice^`, so a caller with `import language.experimental.captureChecking`
cannot let that view outlive the region it borrows - through an effect built inside the block, or
through a sub-view, `take`/`drop`/`slice` returning `Slice^{s}` and so carrying the borrow's lifetime
with them. Only a copy (`toArray`) or a pure result leaves the block. Opting in is per file and costs
nothing elsewhere: a caller without the import compiles exactly as before, and an **owned** slice is
untouched by any of this - `Slice.of(array).drop(1).take(2)` re-slices freely, its result staying
pure.

| Member                         | Description                                            |
|--------------------------------|--------------------------------------------------------|
| `Slice.of(array[, off, len])`  | Bounds-checked view over an array (or sub-range)       |
| `Slice.of(ptr, len)`           | Pointer-backed view (Scala Native only)                |
| `Slice.borrowing(ptr, len)(f)` | Scoped pointer-backed view (Scala Native only)         |
| `Slice.empty`                  | The zero-length view                                   |
| `length` / `isEmpty` / `nonEmpty` | Size of the view                                    |
| `take(n)` / `drop(n)` / `slice(from, until)` | Bounds-checked sub-views, no copy (raise) |
| `apply(i)` / `s(i) = b`        | Read / write the byte at `i` (raises out of range)     |
| `readBE[A](o)` / `readLE[A](o)`| Decode `A` = `Short`/`Int`/`Long`, allocation-free (raise) |
| `writeBE[A](o, v)` / `writeLE[A](o, v)` | Encode `A` = `Short`/`Int`/`Long` in place (raise) |
| `contentEquals(that)`          | Byte equality (not constant-time)                      |
| `constantTimeEquals(that)`     | Constant-time byte equality (secrets, MACs, tags)      |
| `sliceOrError(from, until)`    | Typed sub-view for untrusted bounds                    |
| `toArray` / `copyInto(dst)`    | Copy out to a fresh array / into `dst`                 |
| `wipe()`                       | Zero the viewed bytes in place (erase secrets)         |

The `unsafe*` accessors (array + offset, or an interior pointer on Native) are a seam for
library-author backends; ordinary users never need them.

---

### Secret

Where `Slice` borrows bytes someone else keeps alive, `Secret` **owns** them and is responsible for
erasing them. There is no accessor and no copy-out: the bytes are reachable only inside a scoped
view, and that view cannot escape.

```scala
import boilerplate.Secret

val key = Secret.fill(32)(view => fillWithRandomBytes(view))
val tag = key.use(view => mac(view, message))   // the view is valid for this call alone
key.destroy()                                    // zeroed in place
```

Bytes that already exist elsewhere - a backend's output buffer, a decoded field - are adopted by
copy through `Secret.of`. The copy is the point: the source stays the caller's, and erasing it once
the carrier holds a copy is the caller's to do.

```scala
val adopted = Secret.of(backendBuffer)
backendBuffer.wipe()
```

`fill` allocates the buffer inside the carrier, so no unwiped copy is left outside it; if `init`
throws, the buffer is erased before the throwable propagates. `use` raises rather than let a read
observe a destroyed secret, and `destroy` raises rather than erase bytes a read is holding - one
atomic cell tracks idle, in-use, and destroyed. Both are programmer errors, so both raise rather than
returning a typed error.

`toString` reports the length alone (`Secret(32 bytes)`), `hashCode` is constant so a secret cannot
seed a hash oracle, and equality compares contents in constant time while holding the read guard on
both carriers - a concurrent `destroy` raises rather than erasing mid-comparison. A destroyed
secret is equal only to itself: erased bytes are an implementation artifact, not a value.

Under `import language.experimental.captureChecking` the scope is enforced: `key.use(view => view)`
does not compile, nor does `key.useEff(view => IO.pure(view))` - the escape through a suspended
effect - nor `key.use(view => view.take(2))`, a sub-view carrying the same lifetime.
`boilerplate-effect` adds `useEff`, which holds the read guard across the effect the continuation
returns rather than only across the call, and `Secret.scoped`, an `EffResource` that destroys on
release.

| Member                | Description                                                        |
|-----------------------|--------------------------------------------------------------------|
| `Secret.fill(n)(init)`| Allocate `n` zeroed bytes and fill them through a scoped view       |
| `Secret.of(source)`   | Copy `source` into a new carrier; the source stays the caller's     |
| `use(f)`              | Read through a view valid for that call alone (raises if destroyed) |
| `destroy()`           | Erase in place; idempotent, raises while a read is in flight        |
| `useEff(f)`           | `use` holding the guard across the returned effect (effect module)  |
| `Secret.scoped(n)(init)` | `EffResource[Nothing, Secret]` destroying on release (effect module) |

---

### UUID

An RFC 9562 UUID: 128 bits held as two `Long`s, big-endian, compared as unsigned octets. It exists
because the JDK's `UUID` gets two things wrong for wire work - `fromString` accepts forms no
protocol should (`1-1-1-1-1`, a leading `+`, groups of the wrong length), and `compareTo` compares
the halves as *signed* longs, which is not the order RFC 9562 section 6.11 specifies.

```scala
import boilerplate.UUID

val id      = UUID.v4(randomBytes)                 // 16 caller-supplied random bytes
val ordered = UUID.v7(clock.millis(), randomBytes) // time-ordered
val parsed  = UUID.parse(text)                     // Either[ValueCodec.Invalid, UUID]
val octets  = id.toArray                           // 16 big-endian bytes
id.copyInto(wireBuffer)                            // or write them in place
```

Randomness and the clock stay the caller's: `v4` and `v7` take bytes, so the generator is chosen
where the security requirement is known rather than inherited from this library. Construction sets
the version and variant bits and nothing else - `v4` keeps 122 of the caller's bits, `v7` keeps 74
and puts `unixMillis` in the leading 48.

`parse` reads the 8-4-4-4-12 hex form in either letter case and nothing else: no braces, no
`urn:uuid:` prefix, no short or over-long groups, no non-ASCII digit. `render` and `toString` emit
the canonical lower-case form. The `Ordering` and the `ValueCodec.Aux[UUID, ValueCodec.Invalid]`
given ship in the companion; `nil` and `max` are the RFC's two sentinels, and neither carries a
meaningful `version`.

| Member                    | Description                                                      |
|---------------------------|------------------------------------------------------------------|
| `UUID.of(bytes)`          | The 16 octets verbatim; `Left` on any other length               |
| `UUID.v4(random)`         | Version 4 over 16 caller-supplied random bytes                   |
| `UUID.v7(millis, random)` | Version 7: timestamp then 74 bits of the caller's random bytes   |
| `UUID.parse(text)` / `render(u)` | The strict RFC 9562 text form, lower case out            |
| `UUID.nil` / `UUID.max`   | The all-zero and all-one sentinels                               |
| `version`                 | The version field, meaningful only for the RFC 9562 variant      |
| `toArray` / `copyInto(dst)` | A fresh 16-byte copy, or the octets written in place           |

---

### TypedError

The base for a module's typed-error root, capturing the four lines every one of them repeats: a
stack-trace-free `Exception` with a message and an optional cause, and derived multiversal equality.

```scala
import boilerplate.TypedError

sealed abstract class StoreError(message: String, cause: Option[Throwable]) extends TypedError(message, cause)
object StoreError:
  case object Missing extends StoreError("missing", None)

  final class Unexpected private (cause: Throwable) extends StoreError("unexpected store failure", Some(cause))
  object Unexpected:
    def apply(cause: Throwable): StoreError = TypedError.idempotent[StoreError, Unexpected](cause)(new Unexpected(_))
```

A payload-free arm is a plain `case object`, with no companion class beside it: `ErrorTest` tests a
singleton by identity, so such an arm is observable on a union channel exactly as a class arm is.

The base is deliberately not sealed - the module declares its own `sealed` root over it, and that
root, not this class, is what exhaustivity checks against. `TypedError.idempotent` returns a cause
that already belongs to the root unchanged, so wrapping an error that has already crossed the
boundary does not nest it.

### ErrorTest

`ErrorTest[E]` is the runtime evidence that a `Throwable` is an `E` - what every observer of a typed
error channel filters by, and so what the [`Eff`](#effect) combinators below are stated in terms of.
Instances are derived by macro for any concrete channel: a class by `isInstanceOf`, a stable
singleton by identity, a union arm by arm.

Nothing is written at a call site whose channel is concrete. It is written where code is **generic**
in its error type, which is the one place the compiler cannot derive it:

```scala
def audited[E <: Throwable, A](name: String)(body: Eff[E, A])(using ErrorTest[E]): Eff[E, A] =
  body.tapError(e => IO.println(s"$name failed: $e"))
```

Inside such code the evidence composes: holding `ErrorTest[E]`, an `ErrorTest[StoreError | E]` for a
channel widened with a concrete arm derives from it. What is refused is a channel that cannot be
tested honestly - an abstract type with no evidence in scope, a parameterised or refined type, and an
*intersection*, which is what the compiler infers when a continuation's branches fail with unrelated
arms, or when a type parameter is instantiated from a union the compiler itself inferred. Deriving a
test for that intersection would capture unrelated failures as typed, so it is a compile error naming
the remedy: name the precise union, by declaring the value's type or ascribing the branches.

`ErrorTest` is deliberately **not** a `scala.reflect.TypeTest`. The compiler casts a `TypeTest`
extractor's bound result to one arm of a union, which throws at any `object` arm - a hand-written,
obviously correct `TypeTest` fails identically. `ErrorTest`'s own `unapply` binds the union itself:

```scala
val et = summon[ErrorTest[StoreError.Missing.type | StoreError.Unexpected]]

throwable match
  case et(e) => s"typed: ${e.getMessage}"
  case other => s"defect: $other"
```

---

### ValueCodec

`ValueCodec[A]` is the scalar wire-text codec: the one `String <-> A` seam for path captures,
query parameters, header values, form fields, environment variables, and command arguments. The
typed failure travels as an abstract `Error <: Throwable` member - a domain scalar surfaces its
own sealed family, so a direct decode site branches exhaustively, while a generic consumer widens
to `Throwable` for free:

```scala
import boilerplate.ValueCodec

summon[ValueCodec[Int]].decode("17")      // Right(17)
summon[ValueCodec[Int]].decode("x")       // Left(ValueCodec.Invalid("not an integer"))

// A codec from parts, error member preserved:
val port: ValueCodec.Aux[Int, ValueCodec.Invalid] =
  ValueCodec(s => s.toIntOption.toRight(ValueCodec.Invalid("not an integer")), _.toString)
```

Givens ship for `String` (`Error = Nothing`), `Int`, `Long`, and `Boolean`. The numeric givens
admit ASCII wire forms alone - no Unicode digits, no `+` sign (leading zeros normalise); they are
wire parsers, not `toIntOption`. Every constructor and given preserves the `Error` member (`ValueCodec.Aux[A, E]`); a seam returning bare
`ValueCodec[A]` erases the family and with it exhaustivity, so hand codecs onward as `Aux`.
Failure messages name the violated constraint and never carry the offending input.

Laws: `decode(encode(a)) == Right(a)`; `encode` is total and canonical; a normalising `decode` is
idempotent through re-encoding. `boilerplate-testkit` carries these as reusable law suites.

Codecs for `Secret` and `Slice` are refused at compile time - an encode would render secret
material to an immutable `String`, or a borrowed view past its lifetime. A deliberate local given
can override the refusal; the guard catches accident, not intent.

**Opaque types get their codec in one line** via the `OpaqueType.Codec[A, Repr]` mixin: `decode`
reads a `Repr` from the text with the representation's own codec and passes it through `of` - so a
normalising companion decodes to the canonical value - and `encode` renders `unwrap`.

```scala
object HeaderName extends OpaqueType[HeaderName, String], OpaqueType.Codec[HeaderName, String]:
  // type Error, wrap, unwrap, validate, apply as usual - and the codec given is derived.

object Port extends OpaqueType[Port, Int], OpaqueType.Eq[Port], OpaqueType.Codec[Port, Int]:
  // ...
```

The codec's error member is the precise union of both stages, so a decode site tells a malformed
text apart from a rejected value:

```scala
summon[ValueCodec[Port]].decode("eighty")  // Left(ValueCodec.Invalid("not an integer"))
summon[ValueCodec[Port]].decode("70000")   // Left(PortError("out of range"))
// the member is ValueCodec.Aux[Port, PortError | ValueCodec.Invalid] - both arms match exhaustively
```

For `Repr = String` the text stage is infallible, so the union collapses to the companion's own
`Error`. A type whose representation *is* its wire text takes `OpaqueType.Wire` instead, which
derives its codec from `parse`/`render`.

---

### codec: byte-to-text vocabulary

`boilerplate.codec` carries the byte-to-text codecs every wire protocol spells values with -
distinct from `ValueCodec` (`String <-> A`); these are `bytes <-> text`. Decode failures are the
typed `codec.Malformed(detail)`; the detail names the violated constraint, never the input.

```scala
import boilerplate.codec.*

Base64.encode(bytes)         // RFC 4648 s4, padded - PEM, MIME, Basic credentials
Base64Url.encode(bytes)      // RFC 4648 s5, unpadded - JOSE, web tokens
Base32.encode(bytes)         // RFC 4648 s6, unpadded upper case - enrolment URIs
Hex.encode(bytes)            // RFC 4648 s8, lower case - digests, fingerprints

Base64Url.decode(text)       // Either[Malformed, Array[Byte]] - strict canonical
```

The base-N decoders are **canonical-strict**: exactly one encoding per octet string is accepted -
wrong padding, wrong alphabet, impossible lengths, and non-zero trailing bits are all rejected,
so nothing keyed on the encoded string (a replay cache, a denylist, a unique-token column) can be
bypassed by re-spelling it. `Hex.decode` alone accepts both letter cases (transcribed hex arrives
in either) and documents that anything keyed on hex text must key on the decoded bytes instead.
Encoders take `Slice` or `Array[Byte]`; decoders allocate a fresh `Array[Byte]` the caller owns -
and may wipe.

`Percent` covers RFC 3986 percent-encoding over UTF-8 with the keep-set as a predicate
(`keepUnreserved` is the universal baseline; each URI component brings its own), and both decode
disciplines the wire genuinely needs: `decode` is strict (`Malformed` on a truncated or non-hex
escape - URI components), `decodeLenient` is total (invalid escapes pass through literally - form
parsing).

`ASCII` carries the locale-free operations wire parsers need: `lower`/`upper` (the Turkish
dotless-i can never reach a protocol token), the RFC 9110 `isTokenChar`/`isToken` classes, the
character and whole-string predicates (`isDigit`/`isLetter`/`isAlphanumeric`, `isDigits`/
`isLetters`) that keep `Character.isDigit`'s whole-Unicode classes out of numeric wire fields, and
the strict unsigned reads `uint`/`ulong` - ASCII digits alone, no sign, `None` on overflow - each
with a fixed-width form (`uint(text, width)`) that refuses anything but exactly `width` digits, for
the padded numeric fields wire formats are built from.

`Decimal` is the money-class plain-decimal seam: `render` emits the one canonical plain form
(trailing zeros stripped, never scientific notation - `BigDecimal.toString` after
`stripTrailingZeros` renders `250` as `2.5E+2`), and `parse` admits plain forms alone, rejecting
exponents, `+` signs, and non-ASCII digits that `BigDecimal(String)` itself accepts.

---

### Platform (`boilerplate-native`, Scala Native only)

Compile-time operating-system and architecture detection for Scala Native targets. Each OS/arch target is
published as its own classified NIR jar, so the constants reflect the actual build target rather than
whichever host happened to build the artefact.

```scala
import boilerplate.{Platform, OS, Arch}

// Compile-time branching - unreachable branches are eliminated
inline if Platform.linux then linuxImpl()
else inline if Platform.darwin then darwinImpl()
else windowsImpl()

// Enum values for runtime dispatch
Platform.os match
  case OS.Linux   => // ...
  case OS.Darwin  => // ...
  case OS.Windows => // ...

Platform.arch match
  case Arch.X86_64  => // ...
  case Arch.Aarch64 => // ...
```

| Member    | Type      | Description                                    |
|-----------|-----------|------------------------------------------------|
| `linux`   | `Boolean` | `true` when the target OS is Linux             |
| `darwin`  | `Boolean` | `true` when the target OS is Darwin (macOS)    |
| `windows` | `Boolean` | `true` when the target OS is Windows           |
| `x86_64`  | `Boolean` | `true` when the target architecture is x86-64  |
| `aarch64` | `Boolean` | `true` when the target architecture is AArch64 |
| `os`      | `OS`      | Enum value for the build-target OS             |
| `arch`    | `Arch`    | Enum value for the build-target architecture   |

`inline if` branches on these constants produce zero-overhead platform-specific code.

`OS` and `Arch` name their cases exactly as sbt-snx's own `snx.OS` and `snx.Arch` do, so a build
definition and a consumed artefact agree on what a target is called - the plugin's `osx` classifier
token is this enum's `Darwin`. Neither side can consume the other's type: one is a build plugin, the
other a published NIR artefact, so the alignment is by name and documented rather than shared.

---

## Effect

Typed-error effects atop cats-effect, at no runtime cost. `Eff[+E, +A]` tracks a compile-time error
type `E <: Throwable` as a **phantom** over `cats.effect.IO`'s own error channel: the representation
is exactly `IO[A]`, a typed failure rides `IO`'s native `Throwable` channel, and no `Either` is ever
allocated. The result is statically-tracked error handling with full cats-effect integration and no
wrapper.

```scala
import boilerplate.effect.*
import cats.effect.IO
```

### Core types

| Type                   | Representation      | Purpose                                        |
|------------------------|---------------------|------------------------------------------------|
| `Eff[E, A]`            | `IO[A]`             | Typed-error effect (phantom `E`)               |
| `UEff[A]`              | `Eff[Nothing, A]`   | Infallible effect                              |
| `TEff[A]`              | `Eff[Throwable, A]` | Throwable-errored effect                       |
| `EffResource[E, A]`    | `Resource[IO, A]`   | Lifecycle-scoped resource with a typed acquire |
| `Provider[R, E, A]`    | -                   | A recipe for one service, wired at compile time |
| `Pool[E, A]`           | -                   | A bounded pool whose lease carries a typed channel |
| `RetryPolicy`          | -                   | Declarative retry pacing and bounds            |

`Eff` is covariant in both parameters, so a value of `Eff[Narrow, A]` is usable wherever
`Eff[Wide, A]` is expected with no call-site method. Composition is exact: every combinator that
joins two channels yields their **precise union**, so a for-comprehension over steps that fail
differently infers `Eff[NotFound | Invalid, A]` with no ascription, and reifying it gives an
`Either` whose `Left` matches exhaustively.

Two shapes still lose the union, and both are Scala's own widening of an inferred union rather than
anything this library introduces:

- A continuation whose channel comes from an `if` or `match` over branches that fail differently
  infers their **join**, not their union. Ascribe the lambda's result, or its branches, where the
  precise union matters. The join of unrelated roots is an intersection, and observing one is a
  compile error rather than a silent capture of unrelated failures - see [`ErrorTest`](#errortest).
- An enum's **simple case** widens to the enum type (`Eff.fail(Refused.Malformed)` is
  `Eff[Refused, Nothing]`), so a channel that is a strict subset of an enum's simple cases has to be
  ascribed or pinned with an explicit type argument. `case object` arms and parameterised enum cases
  are unaffected.

### Quick start

```scala
import boilerplate.TypedError

sealed abstract class AppError(msg: String) extends TypedError(msg, None)
object AppError:
  final case class NotFound(id: String) extends AppError(s"not found: $id")
  final case class Invalid(reason: String) extends AppError(s"invalid: $reason")

case class User(id: String, name: String)

def findUser(id: String): Eff[AppError.NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(AppError.NotFound(id))

def validateUser(user: User): Eff[AppError.Invalid, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(AppError.Invalid("name required"))

// for-comprehension: the error channel widens to the union automatically
val workflow: Eff[AppError, User] = for
  user      <- findUser("1")
  validated <- validateUser(user)
yield validated

// Exhaustive error handling - both channels consumed, so the result is infallible
val message: UEff[String] = workflow.fold(
  {
    case AppError.NotFound(id) => s"user $id not found"
    case AppError.Invalid(msg) => s"invalid: $msg"
  },
  user => s"welcome ${user.name}"
)

// Reify the typed channel on demand
val io: IO[Either[AppError, User]] = workflow.either.absolve
```

### Lifting: a raw `IO` already is an `Eff`

`IO[A]` is declared as a **supertype** of `Eff[E, A]`, so any `IO` value flows into an `Eff`-typed
position by subtyping alone - no conversion, no import, no compiler flag, nothing to call:

```scala
val infallible: UEff[Int]         = IO.pure(1)
val typed: Eff[AppError, Int]     = IO.pure(1)
val inChain: Eff[AppError, Int]   = findUser("1").flatMap(_ => IO.pure(1))
```

Every cats-effect and fs2 primitive therefore composes into an `Eff` workflow directly, and so does
every operation on one - a `Ref[IO, A]`'s `get` returns `IO[A]`, which is already an `Eff`.

The relation is strictly one-directional. Reaching `IO` from a typed channel is the explicit
`absolve`, and neither of these compiles:

```scala
val leak: IO[Int] = typed        // rejected - a typed channel does not silently become IO
val narrow: UEff[Int] = typed    // rejected - covariance widens E, it does not narrow it
```

**Lifting commits nothing about the error channel.** An `IO` placed where `Eff[E, A]` is expected
simply *is* that value; the channel a context claims is the channel its observers filter by. Where a
bare `IO` is passed to an entry point generic in `E`, `E` pins to `Nothing` and the infallible
overload is selected - so `Eff.retry(io, policy)` runs `io` exactly once, a defect being no typed
error.

One consequence to know: in a for-comprehension each generator's `flatMap` comes from its own
receiver, and `IO` has a member `flatMap` of its own, which wins over the extension. So an `IO`
generator may not be **followed** by an `Eff` generator - and one `IO` generator downgrades every
generator after it, not merely the next:

| shape          | result   |
|----------------|----------|
| `eff; io`      | compiles |
| `io; io`       | compiles |
| `eff; eff`     | compiles |
| `io; eff`      | fails    |
| `eff; io; eff` | fails    |

An `IO` in final position is fine, and a comprehension that is `IO` throughout is an `IO`, which then
lifts as a value into an `Eff`-typed position. Where an `Eff` step must follow an `IO` one, mark the
`IO` generator with `.eff` - an identity view committing `E = Nothing` - rather than restructuring;
an extension `flatMap` on `IO` cannot fix this, because the member is selected first and then fails:

```scala
val ordered: Eff[AppError, User] = for
  _    <- IO.println("starting").eff
  user <- findUser("1")
yield user
```

`Resource` has the same seam and the same remedy: `Resource.pure[IO, Int](1).eff` is an
`EffResource[Nothing, Int]` for a leading generator in an `EffResource` comprehension.

### Eff constructors

| Category     | Methods                                                                         |
|--------------|---------------------------------------------------------------------------------|
| Pure         | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful    | `lift(IO[Either])`, `lift(IO[Option], ifNone)`                                  |
| Suspended    | `delay(=> Either)`, `defer(=> Eff)`, `suspend(=> A)`, `blocking`, `suspendBlocking` |
| Values       | `succeed`, `fail`, `unit`, `attempt`, `attempt(pf)`                             |
| Temporal     | `sleep(duration)`, `monotonic`, `realTime`                                      |
| Cancellation | `canceled`, `cede`, `never`                                                     |
| Async        | `fromFuture(IO[Future], ifFailure)`, `fromFuture(pf)`, `async`, `asyncAttempt(ifDefect)` |
| Conditional  | `when`, `unless`, `raiseWhen`, `raiseUnless`, `cond(pred, ifTrue, ifFalse)`     |
| Collection   | `traverse`, `sequence`, `parTraverse`, `parSequence` (each with a `_` discard variant) |
| Retry        | `retry(eff, policy[, retryOn][, onRetry])`                                      |

Entering the effect needs nothing beyond these and a raw `IO`: the supertype bound carries the
lifting, so `Eff.succeed` and `Eff.fail` are the only constructors most code reaches for.

### Eff combinators

| Category      | Methods                                                                      |
|---------------|------------------------------------------------------------------------------|
| Mapping       | `map`, `flatMap`, `subflatMap`, `transform`                                  |
| Composition   | `*>`, `<*`, `product`, `void`, `as`, `flatTap`                               |
| Recovery      | `valueOr`, `catchAll`, `catchSome`, `catchOnly`                              |
| Error mapping | `mapError`, `mapErrorPartial`                                                |
| Alternative   | `alt`, `orElseSucceed`, `orElseFail`                                         |
| Folding       | `fold`, `foldF`, `redeemAll`                                                 |
| Observation   | `tapError`, `flatTapError`, `attemptTap`                                     |
| Extraction    | `option`, `collectSome`, `collectRight`                                      |
| Conversion    | `either` (`UEff[Either[E, A]]`), `absolve` (the `IO` exit), `eitherT`        |
| Resource      | `bracket`, `bracketCase`                                                     |
| Concurrency   | `start`, `race`, `both`, `background`                                        |
| Temporal      | `delayBy(duration)`, `andWait(duration)`, `timed`, `timeout(dur, onTimeout)`, `timeoutTo(dur, fallback)` |
| Executor      | `evalOn(ec)` - channel-neutral shift, `E` preserved                          |
| Cancellation  | `onCancel(fin)`, `guarantee(fin)`, `guaranteeCase(fin)`                      |
| Parallel      | `&>`, `<&`                                                                   |

An `IO` argument needs no separate name: `IO[A]` is `Eff[Nothing, A]`, so `flatMap` with an `IO`
lambda keeps the receiver's channel exactly and `flatTap` observes without widening it.

**Observing the typed channel.** The combinators that observe or transform the error - `either`,
`catchAll`, `mapError`, `fold`, `catchOnly`, `option`, `redeemAll`, `orElseFail`, `valueOr`, `alt`,
`tapError`, `attemptTap`, `retry`, ... - filter the caught `Throwable` through
[`ErrorTest[E]`](#errortest), re-raising any non-`E` defect unchanged. For a concrete `E` the
evidence is derived at the call site with nothing written there, singleton arms included; on the
infallible channel (`E = Nothing`) every observer is degenerate and any handler is dead code.

**Narrowing partial recovery (`catchOnly`).** Covariance lets you handle one arm of a union error
while keeping the rest typed. The residual is inferred - no annotation needed. An infallible
handler selects a twin overload that bounds the residual by the receiver's channel and subtracts
the handled arm (a handler covering the whole channel infers `Nothing`; a root-typed channel stays
bounded by the root, which does not decompose); a fallible handler's own return type pins it:

```scala
val consumed: Eff[IOError | AppError, Unit] = ...
val handled = consumed.catchOnly((app: AppError) => log(app)) // : Eff[IOError, Unit]
```

The handler may itself fail into the residual channel - ascribe its failure to the residual root
(`Eff.fail(e): Eff[IOError, Nothing]`), or the solver pins the residual to the failure's concrete
subtype. The handled arm must be runtime-testable; an erasure-ambiguous choice is rejected at the
call site.

**Writing your own error-observing API generic in `E`.** Threading `using ErrorTest[E]` sets a trap:
where `E` would infer as `Nothing`, the solver silently widens it to `Throwable` (whose test is the
identity, so every defect is captured). Pin `E` from a covariant parameter - order the parameter
lists so an effect or handler argument fixes `E` first - and add a `Nothing`-pinned overload for the
infallible case, the shape the built-in observers and `retry` use.

### EffResource

`EffResource[E, A]` is the resource vocabulary in the same shape: the representation is exactly
`Resource[IO, A]`, and `E` is the same phantom, carrying the error type an **acquisition** may fail
with. Putting the error in a covariant parameter of the resource type - rather than inside an
invariant `F` - is what lets acquisition channels compose, so sequencing resources that fail
differently yields their precise union exactly as `Eff` does, with no `mapK` and no cast:

```scala
val config: EffResource[ConfigError, Config] = EffResource.make(loadConfig)(_ => IO.unit)
val db: EffResource[DbError, Db]             = EffResource.make(openDb)(closeDb)

val both: EffResource[ConfigError | DbError, (Config, Db)] =
  for
    c <- config
    d <- db
  yield (c, d)
```

A raw `cats.effect.Resource[IO, A]` lifts by the same subtyping bound, and `absolve` is the way back.
Release never carries a typed error: a finaliser runs on success, typed failure and cancellation
alike, and has no channel of its own to fail into.

| Member                               | Description                                          |
|--------------------------------------|------------------------------------------------------|
| `EffResource.eval(eff)`              | Hold the result of an effect, no finaliser           |
| `EffResource.make(acquire)(release)` | Acquire and release                                  |
| `EffResource.makeFull(acquire)(release)` | As `make`, acquisition uncancelable outside `poll` |
| `EffResource.pure(a)` / `.unit`      | A value with no finaliser                            |
| `use(f)` / `use_` / `surround(eff)`  | Run with the resource held, then release             |
| `both(that)`                         | Acquire both, holding each until the scope exits     |
| `onFinalize(fin)`                    | Register an additional finaliser                     |
| `map` / `evalMap` / `evalTap`        | Transform or observe the acquired value              |
| `flatMap`                            | Sequence resources; finalisers run in reverse order  |
| `EffResource.retry(res, policy[, retryOn][, onRetry])` | Retry ACQUISITION per policy            |
| `absolve`                            | The underlying `Resource[IO, A]`                     |

**Retrying acquisition.** `EffResource.retry` applies a [`RetryPolicy`](#retry-and-retrypolicy) to the
acquisition alone - the client-pool shape: a failed attempt has already released whatever prefix it
acquired, and the consumer of the resource is never re-run. A defect never retries, on any overload.

### Provider: dependency wiring at compile time

A `Provider` is an inert recipe for one service: how to build it as an `EffResource`, given the
services it depends on. `Provider.wire[Target]` assembles a set of them into the resource that builds
`Target`. Dependencies are matched by type, so argument order is irrelevant; each service is
constructed **once** however many others depend on it; finalisers run in reverse construction order;
and the result's error channel is the union of the providers' own.

All of that is decided at compile time. The expansion is the nested `EffResource` composition a
careful author would have written by hand - there is no registry, no environment threaded through the
effect type, and no lookup at run time.

```scala
val configProvider = Provider(EffResource.make(loadConfig)(_ => IO.unit))
val dbProvider     = Provider((c: Config) => EffResource.make(openDb(c))(closeDb))
val cacheProvider  = Provider((c: Config) => EffResource.make(openCache(c))(closeCache))
val serverProvider = Provider((d: Db, c: Cache) => EffResource.make(start(d, c))(stop))

val server: EffResource[ConfigError | DbError, Server] =
  Provider.wire[Server](serverProvider, dbProvider, cacheProvider, configProvider)
```

`Config` is a diamond here - both `Db` and `Cache` need it - and it is built exactly once, as a
property of the emitted code rather than a runtime cache. Users never write the dependency tuple:
the companion's `apply` takes either a dependency-free `EffResource` or a function of up to eight
dependencies, and `wire` reads the rest off the types.

The wired set must be exact, and every way it can be wrong is a compile error, reported together:

```text
Provider.wire[Server] failed:
  - missing: no provider for Config
      required by: Db (argument 2), Cache (argument 3)
      note: argument 4 provides PgConfig <: Config; ascribe it to Provider[?, ?, Config] to supply it
  - duplicate: Db is provided by arguments 2 and 5 - remove one, or ascribe their outputs to distinct services
  - unused: argument 6 (provides Metrics) is not reachable from Server - remove it
```

Matching is on the exact declared output type, so publishing a provider at a wider service type is a
free covariant ascription - which is what the `note:` line points at. A dependency cycle is reported
on its own, with the full path (`cycle: Db -> Config -> Db`).

`wire` is a convenience over the primary surface, not a framework: the result is an ordinary
`EffResource`, and a consumer who prefers `given` resolution or a hand-written for-comprehension
simply does not use it.

### Retry and RetryPolicy

`RetryPolicy` is a pure value describing only pacing and bounds, so one policy is shareable across
differently-typed effects: a backoff strategy (`constant`, `exponential`, `fullJitter`,
`decorrelated`) refined by `withMaxAttempts` (total executions, unbounded when absent),
`withMaxDelay` (per-attempt cap), and `withMaxCumulativeDelay` (total sleep budget - retrying stops
rather than sleep beyond it).

The `retry` overloads interpret it: `retryOn: E => Boolean` selects which typed errors are worth
retrying, and `onRetry: (attempt, error, nextDelay) => IO[Unit]` observes each retry before its
sleep. A defect never retries, on any overload.

```scala
import scala.concurrent.duration.*

val policy = RetryPolicy.fullJitter(100.millis).withMaxAttempts(5).withMaxDelay(2.seconds)
val retried: Eff[AppError, User] =
  Eff.retry(workflow, policy, { case _: AppError.NotFound => true; case _: AppError.Invalid => false })
```

### Pool

`Pool[E, A]` is a bounded pool of values built by an `EffResource`. Its `lease` is itself an
`EffResource`, so an entry is held for the lease's scope and returned on release - after success,
after a typed failure, and after cancellation alike - and the lease's channel says exactly what can
go wrong: the factory's own error, or `Pool.Exhausted`.

```scala
import boilerplate.effect.*

val connections: EffResource[Nothing, Pool[ConnError, Conn]] =
  Pool(EffResource.make(open)(close), Pool.Config(8).withIdleTimeout(30.seconds))

connections.use { pool =>
  pool.lease.use(conn => query(conn))   // : Eff[ConnError | Pool.Exhausted, Rows]
}
```

Capacity is a semaphore and eviction happens on lease, so a pool holds no background fibre and no
timer: an entry that has expired or gone unhealthy is destroyed when it is next reached for, and no
stale entry is ever lent. When the pool's own scope ends, every idle entry is destroyed; an entry
still lent is destroyed when its lease releases it.

A pooled value is a **reference** (`A <: AnyRef`): entries are tracked by identity, which is what
lets `invalidate` name one of several live entries. An opaque handle type over a reference declares
`<: AnyRef` to be poolable.

Retrying is composed rather than configured - `EffResource.retry(create, policy)` on the factory,
which is the same `RetryPolicy` everything else uses, so the pool has no policy of its own.

| Member                          | Description                                                     |
|---------------------------------|-----------------------------------------------------------------|
| `Pool(create, config)`          | A pool over `create`, closed with its own scope                 |
| `Pool(create, config, healthy)` | As above, checking an idle entry before lending it              |
| `pool.lease`                    | `EffResource[E \| Exhausted, A]` - an entry for the scope        |
| `pool.invalidate(a)`            | Destroy the leased `a` on return instead of reusing it          |
| `Pool.stats(pool)`              | Idle, in-use, and waiting counts                                |
| `Config(capacity)`              | `withExhaustion`, `withIdleTimeout`, `withObserver`             |
| `Exhaustion.Fail \| Wait(deadline)` | Raise at once, or wait until the deadline                   |
| `Event` / `Reason`              | What the observer is told, and why an entry was destroyed       |

`Exhaustion.Fail` raises `Exhausted(capacity)` as soon as every entry is in use; `Wait(deadline)`
queues the lease and raises at the deadline instead. Waiters are served in arrival order, and a
cancelled waiter leaks no capacity. A typed failure *during use* is not evidence the entry is
broken, so it is returned like any other - `invalidate` and the health check are the explicit seams
for saying otherwise.

### cats interop

Every cats and cats-effect instance is available on `Eff.Of[E]` (the type lambda
`[A] =>> Eff[E, A]`) at no cost - `E` is a phantom, so `IO`'s own `Async[IO]` **is** the
`Async[Eff.Of[E]]`. The one bespoke instance is the typed `MonadError[_, E]`, whose
`handleErrorWith` filters `IO`'s `Throwable` channel through an `ErrorTest[E]`, catching only a
genuine `E` and re-raising any other defect.

<details>
<summary><strong>Effect typeclasses</strong></summary>

| Typeclass                     | Requirement                | Capability                           |
|-------------------------------|----------------------------|--------------------------------------|
| `Monad`                       | -                          | `flatMap`, `pure` (also `Functor`)   |
| `MonadError[_, E]`            | `ErrorTest[E]`             | Typed error channel `E`              |
| `Async`                       | -                          | `async`, `evalOn`, `fromFuture`      |
| `Sync`, `GenTemporal`, `GenConcurrent`, `GenSpawn`, `MonadCancel`, `Clock`, `Unique`, `Defer` | - | Inherited from `Async` by subtyping |
| `Parallel`                    | -                          | `.parMapN`, `.parTraverse`           |
| `SemigroupK`                  | `ErrorTest[E]`             | `combineK` / `<+>` (choice via `alt`)|
| `Semigroup`                   | `Semigroup[A]`             | `combine` on success values          |
| `Monoid`                      | `Monoid[A]`                | `combine` with `empty`               |

`EffResource.Of[E]` carries `Async[Resource[IO, *]]` the same way, and with it `MonadCancel`.

</details>

Because the error is a `Throwable` in `IO`'s channel rather than a foldable value, there are no
`Bifunctor`, `Foldable`, `Traverse`, `Bifoldable`, or `Bitraverse` instances - mapping the error to a
non-`Throwable` would be unsound. `Show`, `Eq`, and `PartialOrder` are not provided either: they
would have to delegate to `IO` instances that exist only in cats-effect's test kit, and
`boilerplate-effect-testkit` defines its own `Eq` for law suites.

With cats syntax in scope (`import cats.syntax.all.*`, or the error modules specifically), the
standard operators resolve on the typed `MonadError[_, E]`:

| Source             | Methods                                                |
|--------------------|--------------------------------------------------------|
| `ApplicativeError` | `recover`, `recoverWith`, `onError`, `adaptError`      |
| `MonadError`       | `ensure`, `ensureOr`, `rethrow`, `redeem`, `redeemWith`|

**Import this package with a wildcard where cats syntax is also in scope.** Every combinator whose
name cats or cats-effect syntax also provides is declared at package level as well as on the
companion, and the package-level copy is what keeps it selected: an imported conversion sits in
lexical scope, a companion's extensions only in implicit scope, and lexical is searched first.
`import boilerplate.effect.*` is what brings those package-level copies in. A file that imports
`boilerplate.effect.Eff` **by name** and also imports `cats.syntax.all.*` or
`cats.effect.syntax.all.*` has the conversions in lexical scope and nothing of ours there, so its
calls are captured exactly as though the copies did not exist.

A captured call is not a compile error you would notice - it resolves one `F` for both operands, so
the channel is pinned to a single type instead of their union and the loss shows up later, where the
next observer asks for evidence at the wrong type. Three of them do fail outright, our signatures
differing from the conversions': `timeout` takes the failure to raise, and `flatTap` and
`bracketCase` take `Eff` continuations.

Carried at package level for `Eff`: `map`, `flatMap`, `*>`, `<*`, `&>`, `<&`, `product`, `flatTap`,
`void`, `as`, `bracket`, `bracketCase`, `start`, `background`, `race`, `both`, `onCancel`,
`guarantee`, `guaranteeCase`, `delayBy`, `andWait`, `timed`, `evalOn`, `timeout`, `timeoutTo`,
`attemptTap`; for `EffResource`: `map`, `flatMap`, `both`. That list is not maintained by hand - a
suite row derives the colliding names from the classpath, so a cats or cats-effect release that adds
one, or drops one, fails a test rather than quietly changing what your code means.

Names cats provides that this library does not shadow - `recover`, `recoverWith`, `onError`,
`adaptError`, `ensure`, `rethrow`, `redeem`, `redeemWith`, `parMapN`, `<+>`, `memoize` - resolve on
the instances above and work as they do for any other effect.

### Cats-effect primitives

There is nothing to lift. A `Ref[IO, A]`, `Deferred`, `Queue`, `Semaphore`, `CountDownLatch`,
`CyclicBarrier`, `AtomicCell`, or `Supervisor` is created in `IO` and its operations return `IO`,
which is already an `Eff` - so they compose into a typed workflow directly:

```scala
val program: Eff[AppError, Int] = for
  ref      <- IO.ref(0)
  deferred <- IO.deferred[Int]
  _        <- ref.update(_ + 1)
  _        <- deferred.complete(42)
  result   <- deferred.get
yield result
```

Summoning a typeclass on `Eff.Of[E]` works equally well where a program is written against the
capability rather than `IO`:

```scala
import cats.effect.kernel.GenConcurrent

val C = summon[GenConcurrent[Eff.Of[AppError], Throwable]]
```

### Erasing secrets

`boilerplate-effect` depends on core, so secret bytes can be tied to an effect's lifecycle. For an
owning carrier, use [`Secret`](#secret):

```scala
import boilerplate.Secret

Secret.scoped(32)(fillWithRandomBytes).use(key => key.useEff(view => sign(view, message)))
```

For a borrowed `Slice` that must be erased when a scope ends, `IO[Slice].wiping` runs a scoped read
and then erases - on success, typed error, or cancellation:

```scala
IO(Slice.of(material.toArray)).wiping(view => digest(view))
```

Keep the copy allocation inside the acquire, so the slice is erased from the moment it exists. It
takes a continuation rather than yielding a resource for a reason: a `Resource` has no binder to
scope the view to its `use`, so it could hand the slice out to be read after the wipe. Here the view
cannot escape - the same enforcement `Secret.use` gets.

### Fibre join extensions

For `Fiber[Eff.Of[E], Throwable, A]` (e.g. from `start` or `Supervisor.supervise`): a fibre that
failed with a typed error completes as `Outcome.Errored(e)`, and the join re-raises `e` on the
effect's channel, while a `Succeeded` returns its value.

| Extension               | Result Type  | On Cancellation        |
|-------------------------|--------------|------------------------|
| `fiber.joinNever`       | `Eff[E, A]`  | Never completes        |
| `fiber.joinOrFail(err)` | `Eff[E, A]`  | Fails with typed error |

### Complete example

```scala
import boilerplate.TypedError
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.Outcome
import scala.concurrent.duration.*

sealed abstract class AppError(msg: String) extends TypedError(msg, None)
object AppError:
  final case class NotFound(id: String) extends AppError(s"not found: $id")
  final case class ValidationError(reason: String) extends AppError(s"invalid: $reason")
  case object Cancelled extends AppError("cancelled")
  case object Timeout extends AppError("timed out")

case class User(id: String, name: String)

def fetchUser(id: String): Eff[AppError.NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(AppError.NotFound(id))

def validateUser(user: User): Eff[AppError.ValidationError, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(AppError.ValidationError("name required"))

// Distinct typed errors unify into their union automatically
val workflow: Eff[AppError, User] = for
  user      <- fetchUser("1")
  validated <- validateUser(user)
yield validated

// A fibre's typed failure is Outcome.Errored
val concurrent: Eff[AppError, User] = for
  fiber  <- workflow.start
  result <- fiber.joinOrFail(AppError.Cancelled)
  _      <- IO.println(s"got ${result.name}")   // a raw IO composes straight in
yield result

val raced: Eff[AppError, Either[User, User]] = workflow.race(workflow)
val parallel: Eff[AppError, (User, User)]    = workflow.both(workflow)
val withTimeout: Eff[AppError, User]         = workflow.timeout(5.seconds, AppError.Timeout)

// Guaranteed cleanup - a typed failure surfaces as Outcome.Errored
val withCleanup: Eff[AppError, User] =
  workflow.guaranteeCase {
    case Outcome.Succeeded(_) => IO.println("success")
    case Outcome.Errored(_)   => IO.println("error")
    case Outcome.Canceled()   => IO.println("cancelled")
  }

val io: IO[Either[AppError, User]] = concurrent.either.absolve
```
---

## Streams (`boilerplate-fs2`)

`boilerplate-fs2` is the fs2 vocabulary over the typed channel - two aliases and the observers fs2
itself cannot provide, on `fs2-core` 3.13.0.

```scala
import boilerplate.stream.*

val rows: EffStream[DbError, Row]           = Stream.eval(query).flatMap(Stream.emits)
val parse: EffPipe[ParseError, Row, Record] = _.evalMap(decode)

val records: EffStream[DbError | ParseError, Record] =
  rows.through(parse.widen[DbError | ParseError])

val collected: Eff[DbError | ParseError, List[Record]] = records.compile.toList
```

`EffStream[E, O]` is `Stream[Eff.Of[E], O]` and `EffPipe[E, I, O]` is `Pipe[Eff.Of[E], I, O]`, so
fs2's entire combinator surface is available unchanged and compiling a stream lands on `Eff`. A
stream widens by subtyping, `Stream` being covariant in its effect, and a raw `Stream[IO, O]` lands
in a typed position for the same reason `IO` does. A **pipe** does not: `Pipe` is a function alias
whose effect is invariant, and `through` takes its function's input at the receiver's own effect -
hence `widen`, which claims a pipe at a wider channel without changing what it can fail with.

| Extension              | Description                                                        |
|------------------------|--------------------------------------------------------------------|
| `catchAll(f)`          | Recover typed failures with another stream; a defect propagates     |
| `reify`                | Typed failure as a final `Left` element; a defect propagates        |
| `mapError(f)`          | Transform the typed channel; a defect propagates                    |
| `absolve`              | The stream on `IO`'s channel - the one-directional exit             |
| `pipe.widen[E2]`       | The same pipe claimed at a wider channel                            |
| `ioStream.eff`         | A raw `Stream[IO, O]` as an infallible `EffStream`, for a generator |

**One hazard to know.** fs2's own `attempt` and `handleErrorWith` take every `Throwable`, defects
included, and they are *members* of `Stream` - so on this alias they win over the extensions above.
Reach for `reify` and `catchAll` by name; a call to `attempt` or `handleErrorWith` on an `EffStream`
is fs2's untyped behaviour, not this vocabulary's. `reify` is named apart from `Eff.either` for the
same reason: `Stream#either` is fs2's merge.

---

## Test kits

`boilerplate-testkit` ships the `ValueCodec` law suites as a `munit.ScalaCheckSuite` mixin:

```scala
class MyCodecsSuite extends munit.ScalaCheckSuite, boilerplate.testkit.ValueCodecLaws:
  valueCodecLaws[UserId]("UserId")                          // round trip + canonical encode
  valueCodecNormalisation[HeaderName]("HeaderName", texts)  // decode idempotent through re-encode
  valueCodecRenderWithin[Amount]("Amount")(plainDecimal)    // no exponent or locale leakage
  valueCodecRefuses[HeaderName]("HeaderName", notTokens)    // every text outside the form is rejected
```

`valueCodecRefuses` is the negative law the round-trip ones cannot express: the generator carries the
shapes the codec must **not** admit - a neighbouring format, a lax spelling, a non-ASCII numeral - so
a decode that quietly widens is caught here rather than by a consumer.

`boilerplate-effect-testkit` ships ScalaCheck generators (`EffGenerators`) and the
cats-effect-testkit-based law instances (`EffTestInstances` - `Eq`, `Cogen`, `Prop` conversion
under a `Ticker`) for property suites over `Eff` and `EffResource`. Each instance reifies the typed
channel before comparing, so it takes the same `ErrorTest[E]` the observers do - derived at the
suite's concrete law error with nothing written.

---

## Licence

MIT
