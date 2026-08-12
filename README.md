# Boilerplate

Foundational Scala 3 utilities for opaque type construction, null-safe handling, native platform detection, and zero-cost typed-error effects - targeting JVM, JS, and Native.

## Installation

Each module is published independently. Add the ones you need:

```scala
// Core: opaque types, nullable extensions
libraryDependencies += "africa.shuwari" %% "boilerplate" % "<version>"

// Effect: typed-error effects atop cats-effect
libraryDependencies += "africa.shuwari" %% "boilerplate-effect" % "<version>"

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

**Multiversal equality is opt-in** via `OpaqueType.Eq[A]`. Security-sensitive types (tokens, keys,
password hashes) should omit it, making `==` a compile error under `strictEquality`.

#### Defining an opaque type

```scala
import boilerplate.*

opaque type UserId = String

object UserId extends OpaqueType[UserId, String], OpaqueType.Eq[UserId]:
  type Error = IllegalArgumentException

  protected inline def wrap(s: String): UserId = s
  inline def unwrap(id: UserId): String        = id

  inline def apply(inline value: String): UserId =
    inline if value == "" then compiletime.error("UserId cannot be empty")
    else wrap(value)

  protected inline def validate(s: String): Either[Error, String] =
    if s.nonEmpty then Right(s)
    else Left(new IllegalArgumentException("UserId cannot be empty"))
```

`validate` returns the value to wrap, so a companion canonicalises while it validates: a
normalising type (a lowercase header name, a trimmed identifier) returns the canonical form and
every construction path produces the normalised value. Verbatim types return the input unchanged;
total types set `Error = Nothing` and return `Right(value)`.

`UserId("user-123")` validates the literal at compile time - an invalid constant is a compile
error, and a non-constant argument fails to reduce, directing the caller to the validated
constructors:

```scala
val direct: UserId                                 = UserId("user-123")
val safe: Either[IllegalArgumentException, UserId] = UserId.of(input)
val trusted: UserId                                = UserId.ofUnsafe(input) // throws Error on invalid
val underlying: String                             = UserId.unwrap(direct)
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
  type Error = IllegalArgumentException
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
| `OpaqueType.Codec[A]` | Mixin deriving a `ValueCodec` from `of`/`unwrap` (`Repr = String`) |

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
| `use(f)`              | Read through a view valid for that call alone (raises if destroyed) |
| `destroy()`           | Erase in place; idempotent, raises while a read is in flight        |
| `useEff(f)`           | `use` holding the guard across the returned effect (effect module)  |
| `Secret.scoped(n)(init)` | `EffResource[Nothing, Secret]` destroying on release (effect module) |

---

### TypedError

The base for a module's typed-error root, capturing the four lines every one of them repeats: a
stack-trace-free `Exception` with a message and an optional cause, and derived multiversal equality.

```scala
import boilerplate.TypedError

sealed abstract class StoreError(message: String, cause: Option[Throwable]) extends TypedError(message, cause)
object StoreError:
  sealed abstract class Missing private () extends StoreError("missing", None)
  case object Missing extends Missing

  final class Unexpected private (cause: Throwable) extends StoreError("unexpected store failure", Some(cause))
  object Unexpected:
    def apply(cause: Throwable): StoreError = TypedError.idempotent[StoreError, Unexpected](cause)(new Unexpected(_))
```

The base is deliberately not sealed - the module declares its own `sealed` root over it, and that
root, not this class, is what exhaustivity checks against. `TypedError.idempotent` returns a cause
that already belongs to the root unchanged, so wrapping an error that has already crossed the
boundary does not nest it.

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

**Opaque types get their codec in one line** via the `OpaqueType.Codec[A]` mixin (`Repr =
String`): `decode` is the companion's own `of` - so a normalising companion decodes to the
canonical value - and `encode` is `unwrap`. For a non-`String` representation, write the given
through the constructor, failing the text stage into the companion's own error family:

```scala
object HeaderName extends OpaqueType[HeaderName, String], OpaqueType.Codec[HeaderName]:
  // type Error, wrap, unwrap, validate, apply as usual - and the codec given is derived.
```

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
the strict unsigned reads `uint`/`ulong` - ASCII digits alone, no sign, `None` on overflow.

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
import boilerplate.{Platform, Os, Arch}

// Compile-time branching - unreachable branches are eliminated
inline if Platform.linux then linuxImpl()
else inline if Platform.mac then macImpl()
else windowsImpl()

// Enum values for runtime dispatch
Platform.os match
  case Os.Linux   => // ...
  case Os.Mac     => // ...
  case Os.Windows => // ...

Platform.arch match
  case Arch.X86_64  => // ...
  case Arch.Aarch64 => // ...
```

| Member    | Type      | Description                                    |
|-----------|-----------|------------------------------------------------|
| `linux`   | `Boolean` | `true` when the target OS is Linux             |
| `mac`     | `Boolean` | `true` when the target OS is macOS             |
| `windows` | `Boolean` | `true` when the target OS is Windows           |
| `x86_64`  | `Boolean` | `true` when the target architecture is x86-64  |
| `aarch64` | `Boolean` | `true` when the target architecture is AArch64 |
| `os`      | `Os`      | Enum value for the build-target OS             |
| `arch`    | `Arch`    | Enum value for the build-target architecture   |

`inline if` branches on these constants produce zero-overhead platform-specific code.

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
| `RetryPolicy`          | -                   | Declarative retry pacing and bounds            |

`Eff` is covariant in both parameters, so a value of `Eff[Narrow, A]` is usable wherever
`Eff[Wide, A]` is expected with no call-site method, and a `flatMap` over steps with distinct error
types widens the channel to their join: for arms of one sealed root that is the root itself, and
for unrelated arms a structural type wider than their union - either the union or the root is
reachable by ascription. That widening is silent - the channel can grow wider than intended with no
compile error, so ascribe the result type, or use `mapError`/`catchOnly`, to contain it.

### Quick start

```scala
import scala.util.control.NoStackTrace

sealed abstract class AppError(msg: String) extends Exception(msg) with NoStackTrace derives CanEqual
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
overload is selected - so `Eff.retry(io, 3)` runs `io` exactly once, a defect being no typed error.

One consequence to know: in a for-comprehension each generator's `flatMap` comes from its own
receiver, and `IO` has a member `flatMap` of its own, which wins over the extension. So an `IO`
generator may not be **followed** by an `Eff` generator - and one `IO` generator downgrades every
generator after it, not merely the next:

| shape          |          |
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
| Retry        | `retry(eff, maxRetries)`, `retryWithBackoff(eff, maxRetries, delay, maxDelay)`, `retry(eff, policy[, retryOn][, onRetry])` |

Entering the effect needs nothing beyond these and a raw `IO`: the supertype bound carries the
lifting, so `Eff.succeed` and `Eff.fail` are the only constructors most code reaches for.

### Eff combinators

| Category      | Methods                                                                      |
|---------------|------------------------------------------------------------------------------|
| Mapping       | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                   |
| Composition   | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap`       |
| Recovery      | `valueOr`, `catchAll`, `catchSome`, `catchOnly`                              |
| Error mapping | `mapError`, `mapErrorPartial`                                                |
| Alternative   | `alt`, `orElseSucceed`, `orElseFail`                                         |
| Folding       | `fold`, `foldF`, `redeemAll`                                                 |
| Observation   | `tap`, `tapError`, `flatTapError`, `attemptTap`                              |
| Variance      | `assume`, `assumeError`                                                      |
| Extraction    | `option`, `collectSome`, `collectRight`                                      |
| Conversion    | `either` (`UEff[Either[E, A]]`), `absolve` (the `IO` exit), `eitherT`        |
| Resource      | `bracket`, `bracketCase`, `timeout`                                          |
| Concurrency   | `start`, `race`, `both`, `background`                                        |
| Temporal      | `delayBy(duration)`, `andWait(duration)`, `timed`, `timeoutTo(dur, fallback)` |
| Executor      | `evalOn(ec)` - channel-neutral shift, `E` preserved                          |
| Cancellation  | `onCancel(fin)`, `guarantee(fin)`, `guaranteeCase(fin)`                      |
| Parallel      | `&>`, `<&`                                                                   |

**Observing the typed channel.** The combinators that observe or transform the error - `either`,
`catchAll`, `mapError`, `fold`, `catchOnly`, `option`, `redeemAll`, `orElseFail`, `valueOr`, `alt`,
`tapError`, `attemptTap`, `retry`, ... - filter the caught `Throwable` through a
`TypeTest[Throwable, E]`, re-raising any non-`E` defect unchanged. For a concrete `E` (a sealed
`Throwable` root, or a union of them) the compiler **synthesises** that `TypeTest`, so nothing is
written at the call site; a library `given TypeTest[Throwable, Nothing]` covers the infallible
(`E = Nothing`) case, where every observer is degenerate and any handler is dead code.

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

**Writing your own error-observing API generic in `E`.** Threading `using TypeTest[Throwable, E]`
sets a trap: where `E` would infer as `Nothing`, the solver silently widens it to `Throwable` (whose
test is the identity, so every defect is captured) instead of committing to the shipped
`given TypeTest[Throwable, Nothing]` - it happens during inference, so importing the given does not
prevent it. Pin `E` from a covariant parameter (order the parameter lists so an effect or handler
argument fixes `E` first) and add a `Nothing`-pinned overload for the infallible case - the shape the
built-in observers and `retry` use.

### EffResource

`EffResource[E, A]` is the resource vocabulary in the same shape: the representation is exactly
`Resource[IO, A]`, and `E` is the same phantom, carrying the error type an **acquisition** may fail
with. Putting the error in a covariant parameter of the resource type - rather than inside an
invariant `F` - is what lets an acquisition channel widen, so composing resources of distinct error
types widens the channel exactly as `Eff` does (their join; the union by ascription) with no `mapK`
and no cast:

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

**Retrying acquisition.** `EffResource.retry` applies a [`RetryPolicy`](#eff-constructors) to the
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

### cats interop

Every cats and cats-effect instance is available on `Eff.Of[E]` (the type lambda
`[A] =>> Eff[E, A]`) at no cost - `E` is a phantom, so `IO`'s own `Async[IO]` **is** the
`Async[Eff.Of[E]]`. The one bespoke instance is the typed `MonadError[_, E]`, whose
`handleErrorWith` filters `IO`'s `Throwable` channel through a `TypeTest[Throwable, E]`, catching
only a genuine `E` and re-raising any other defect.

<details>
<summary><strong>Effect typeclasses</strong></summary>

| Typeclass                     | Requirement                | Capability                           |
|-------------------------------|----------------------------|--------------------------------------|
| `Monad`                       | -                          | `flatMap`, `pure` (also `Functor`)   |
| `MonadError[_, E]`            | `TypeTest[Throwable, E]`   | Typed error channel `E`              |
| `Async`                       | -                          | `async`, `evalOn`, `fromFuture`      |
| `Sync`, `GenTemporal`, `GenConcurrent`, `GenSpawn`, `MonadCancel`, `Clock`, `Unique`, `Defer` | - | Inherited from `Async` by subtyping |
| `Parallel`                    | -                          | `.parMapN`, `.parTraverse`           |
| `SemigroupK`                  | `TypeTest[Throwable, E]`   | `combineK` / `<+>` (choice via `alt`)|
| `Semigroup`                   | `Semigroup[A]`             | `combine` on success values          |
| `Monoid`                      | `Monoid[A]`                | `combine` with `empty`               |

`EffResource.Of[E]` carries `Async[Resource[IO, *]]` the same way, and with it `MonadCancel`.

</details>

<details>
<summary><strong>Data typeclasses</strong></summary>

| Typeclass      | Requirement           | Behaviour                                 |
|----------------|-----------------------|-------------------------------------------|
| `Show`         | `Show[IO[A]]`         | Textual representation (delegates to `IO`)|
| `Eq`           | `Eq[IO[A]]`           | Equality comparison                       |
| `PartialOrder` | `PartialOrder[IO[A]]` | Partial ordering                          |

Because the error is a `Throwable` in `IO`'s channel rather than a foldable value, there are no
`Bifunctor`, `Foldable`, `Traverse`, `Bifoldable`, or `Bitraverse` instances - mapping the error to a
non-`Throwable` would be unsound - and `Show`/`Eq`/`PartialOrder` delegate straight to `IO[A]`.

</details>

With cats syntax in scope (`import cats.syntax.all.*`, or the error modules specifically), the
standard operators resolve on the typed `MonadError[_, E]`:

| Source             | Methods                                                |
|--------------------|--------------------------------------------------------|
| `ApplicativeError` | `recover`, `recoverWith`, `onError`, `adaptError`      |
| `MonadError`       | `ensure`, `ensureOr`, `rethrow`, `redeem`, `redeemWith`|

The blanket import coexists with union inference: `map` and `flatMap` are declared at package level
as well as on the companions, for `Eff` and `EffResource` alike, so they stay selected ahead of cats'
own syntax - which would otherwise pin a for-comprehension's error type to the first step's `E` and
reject a workflow whose steps carry distinct error types.

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
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.Outcome
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

sealed abstract class AppError(msg: String) extends Exception(msg) with NoStackTrace derives CanEqual
object AppError:
  final case class NotFound(id: String) extends AppError(s"not found: $id")
  final case class ValidationError(reason: String) extends AppError(s"invalid: $reason")
  // Payload-free cases as class + case object, so type positions can name the class: reified
  // unions over `.type` singleton arms mis-erase (Scala 3.8.4), classes are sound.
  sealed abstract class Cancelled private () extends AppError("cancelled")
  case object Cancelled extends Cancelled
  sealed abstract class Timeout private () extends AppError("timed out")
  case object Timeout extends Timeout

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

## Test kits

`boilerplate-testkit` ships the `ValueCodec` law suites as a `munit.ScalaCheckSuite` mixin:

```scala
class MyCodecsSuite extends munit.ScalaCheckSuite, boilerplate.testkit.ValueCodecLaws:
  valueCodecLaws[UserId]("UserId")                          // round trip + canonical encode
  valueCodecNormalisation[HeaderName]("HeaderName", texts)  // decode idempotent through re-encode
  valueCodecRenderWithin[Amount]("Amount")(plainDecimal)    // no exponent or locale leakage
```

`boilerplate-effect-testkit` ships ScalaCheck generators (`EffGenerators`) and the
cats-effect-testkit-based law instances (`EffTestInstances` - `Eq`, `Cogen`, `Prop` conversion
under a `Ticker`) for property suites over `Eff` and `EffResource`.

---

## Licence

MIT
