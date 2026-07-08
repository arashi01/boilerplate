# Boilerplate

Foundational Scala 3 utilities for opaque type construction, null-safe handling, native platform detection, cross-platform codecs, and zero-cost typed-error effects - targeting JVM, JS, and Native.

## Installation

Each module is published independently. Add the ones you need:

```scala
// Core: opaque types, nullable extensions
libraryDependencies += "io.github.arashi01" %% "boilerplate" % "<version>"

// Codecs: Base64 (JVM, JS, Native)
libraryDependencies += "io.github.arashi01" %% "boilerplate-codecs" % "<version>"

// Effect: typed-error effects atop cats-effect
libraryDependencies += "io.github.arashi01" %% "boilerplate-effect" % "<version>"
```

On Scala.js and Scala Native, `%%` resolves the platform-specific artefact (the sbt 2.x replacement for `%%%`).

`boilerplate-native` (compile-time OS/architecture detection) is Native-only and published as a per-OS/arch classified NIR library. Consume it through [sbt-snx](https://github.com/shuwariafrica/sbt-snx) so the classifier for your build target resolves automatically:

```scala
SNX.dependencies += "io.github.arashi01" %% "boilerplate-native" % "<version>" % NativeClassifier
```

---

## Core

```scala
import boilerplate.*
```

### OpaqueType

`OpaqueType[A, Repr]` is a base trait for opaque type companion objects providing validated construction and extension-based syntax.

**Multiversal equality is opt-in** via `OpaqueType.Eq[A]`. Security-sensitive types (tokens, keys,
password hashes) should omit it to prevent accidental comparison.

#### Defining an opaque type

```scala
import boilerplate.*

opaque type UserId = String

object UserId extends OpaqueType[UserId, String], OpaqueType.Eq[UserId]:
  type Error = IllegalArgumentException

  inline def wrap(s: String): UserId     = s
  inline def unwrap(id: UserId): String  = id
  inline def apply(inline value: String): UserId = fromUnsafe(value)

  protected inline def validate(s: String): Option[Error] =
    if s.nonEmpty then None
    else Some(new IllegalArgumentException("UserId cannot be empty"))
```

For types where equality comparison should be forbidden, omit the `Eq` mixin:

```scala
opaque type SecretToken = String

object SecretToken extends OpaqueType[SecretToken, String]:
  type Error = IllegalArgumentException
  // ...
  // SecretToken values cannot be compared with == (compile error under strictEquality)
```

#### Construction

```scala
// Via companion
val direct: UserId                                 = UserId("user-123")
val safe: Either[IllegalArgumentException, UserId] = UserId.from("user-123")

// Via extension syntax
val ext1: Either[IllegalArgumentException, UserId]  = "user-123".as[UserId]
val ext2: UserId                                    = "user-123".asUnsafe[UserId]
val ext3: UserId                                    = "user-123".const[UserId]
```

#### Extraction

```scala
val underlying: String = direct.unwrap
```

The `unwrap` extension resolves the concrete underlying type across module boundaries.

#### Compile-time validation

Companions may override `apply` with `inline if` + `compiletime.error` to reject invalid literals at compile time:

```scala
opaque type PositiveInt = Int

object PositiveInt extends OpaqueType[PositiveInt, Int], OpaqueType.Eq[PositiveInt]:
  type Error = IllegalArgumentException

  inline def wrap(n: Int): PositiveInt    = n
  inline def unwrap(p: PositiveInt): Int  = p

  inline def apply(inline value: Int): PositiveInt =
    inline if value <= 0 then compiletime.error("value must be positive")
    else wrap(value)

  protected inline def validate(n: Int): Option[Error] =
    if n > 0 then None
    else Some(new IllegalArgumentException(s"$n must be positive"))

PositiveInt(42)   // compiles
PositiveInt(-1)   // compile-time error: "value must be positive"
```

#### API summary

| Member / Extension  | Description                                              |
|---------------------|----------------------------------------------------------|
| `Repr` (type param) | Underlying representation type                           |
| `type Error`        | Validation error type (must extend `Throwable`)          |
| `wrap(value)`       | Wraps without validation                                 |
| `unwrap(value)`     | Extracts the underlying value                            |
| `apply(value)`      | Direct construction; override for compile-time checks    |
| `validate(value)`   | Returns `None` on success, `Some(error)` on failure      |
| `from(value)`       | Safe construction returning `Either[Error, A]`           |
| `fromUnsafe(value)` | Throws `Error` on validation failure                     |
| `value.as[A]`       | Extension for `from`                                     |
| `value.asUnsafe[A]` | Extension for `fromUnsafe`                               |
| `value.const[A]`    | Extension for `apply`                                    |
| `value.unwrap`      | Extension for `unwrap`                                   |
| `OpaqueType.Eq[A]`  | Mixin providing `CanEqual[A, A]` (opt-in equality)       |

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
lifetime the caller owns; `Slice.borrowing(ptr, len) { s => ... }` scopes that view to the block.

| Member                         | Description                                            |
|--------------------------------|--------------------------------------------------------|
| `Slice.of(array[, off, len])`  | Bounds-checked view over an array (or sub-range)       |
| `Slice.of(ptr, len)`           | Pointer-backed view (Scala Native only)                |
| `Slice.borrowing(ptr, len)(f)` | Scoped pointer-backed view (Scala Native only)         |
| `Slice.empty`                  | The zero-length view                                   |
| `length` / `isEmpty`           | Size of the view                                       |
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

## Codecs

```scala
import boilerplate.codec.Base64
```

### Base64

Encoding and decoding per [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648). Supports the standard alphabet
(section 4: `+`, `/`, `=` padding) and the URL-safe alphabet (section 5: `-`, `_`, no padding).

Decoding is strict - invalid characters and malformed padding produce `Left(Base64.Error)`.

```scala
// Standard (RFC 4648 section 4)
val encoded: String                          = Base64.encode("foobar".getBytes("UTF-8"))
val decoded: Either[Base64.Error, Array[Byte]] = Base64.decode("Zm9vYmFy")

// URL-safe (RFC 4648 section 5) - suitable for JWT, URI parameters, filenames
val urlEncoded: String                          = Base64.encode(data, urlSafe = true)
val urlDecoded: Either[Base64.Error, Array[Byte]] = Base64.decode(urlEncoded, urlSafe = true)
```

| Method   | Signature                                                   | Description                        |
|----------|-------------------------------------------------------------|------------------------------------|
| `encode` | `(Array[Byte]): String`                                     | Standard encoding with padding     |
| `encode` | `(Array[Byte], urlSafe: Boolean): String`                   | Standard or URL-safe encoding      |
| `decode` | `(String): Either[Error, Array[Byte]]`                      | Standard decoding                  |
| `decode` | `(String, urlSafe: Boolean): Either[Error, Array[Byte]]`    | Standard or URL-safe decoding      |

---

## Effect

Zero-cost typed-error effects atop cats-effect. `Eff` and `EffIO` track a compile-time error type
`E <: Throwable` as a **phantom** over the base effect's own error channel: the representation is
exactly `F[A]` (or `IO[A]`), a typed failure rides `F`'s native `Throwable` channel, and no `Either`
is ever allocated. The result is exhaustive, statically-tracked error handling with full cats-effect
integration and no runtime wrapper.

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.syntax.all.*
```

### Core types

| Type           | Representation         | Purpose                                   |
|----------------|------------------------|-------------------------------------------|
| `Eff[F, E, A]` | `F[A]`                 | Typed-error effect (phantom `E`)          |
| `EffIO[E, A]`  | `IO[A]`                | Covariant, `IO`-specialised typed effect  |
| `UEff[F, A]`   | `Eff[F, Nothing, A]`   | Infallible effect                         |
| `TEff[F, A]`   | `Eff[F, Throwable, A]` | Throwable-errored effect                  |
| `UEffIO[A]`    | `EffIO[Nothing, A]`    | Infallible `IO`-specialised effect        |
| `TEffIO[A]`    | `EffIO[Throwable, A]`  | Throwable-errored `IO`-specialised effect |

The typed error `E` is bounded by `Throwable`: it rides the base effect's native error channel, so
every domain error is a `Throwable` subtype - typically a sealed `Exception` root with `NoStackTrace`.
`E` is a phantom, absent from the representation, so all opaque types erase to their base effect at
runtime: no wrapper allocation ever occurs and the happy path IS the base effect.

### Quick start

```scala
import scala.util.control.NoStackTrace

sealed abstract class AppError(msg: String) extends Exception(msg) with NoStackTrace derives CanEqual
object AppError:
  final case class NotFound(id: String) extends AppError(s"not found: $id")
  final case class Invalid(reason: String) extends AppError(s"invalid: $reason")

case class User(id: String, name: String)

def findUser(id: String): Eff[IO, AppError.NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(AppError.NotFound(id))

def validateUser(user: User): Eff[IO, AppError.Invalid, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(AppError.Invalid("name required"))

// for-comprehension: the error channel widens to the union automatically - no widenError
val workflow: Eff[IO, AppError, User] = for
  user      <- findUser("1")
  validated <- validateUser(user)
yield validated

// Exhaustive error handling - fold both channels back to the base effect
val message: IO[String] = workflow.fold(
  {
    case AppError.NotFound(id) => s"user $id not found"
    case AppError.Invalid(msg) => s"invalid: $msg"
  },
  user => s"welcome ${user.name}"
)

// Reify the typed channel on demand
val io: IO[Either[AppError, User]] = workflow.either
```

### Eff constructors

Partially-applied constructors minimise type annotations:

```scala
Eff[IO].succeed(42)                    // UEff[IO, Int]
Eff[IO].fail(AppError.NotFound("u1"))  // Eff[IO, AppError.NotFound, Nothing]
Eff[IO].from(Right(1))                 // Eff[IO, Nothing, Int]
Eff[IO].liftF(IO.pure(42))             // UEff[IO, Int]
Eff[IO].unit                           // UEff[IO, Unit]
Eff[IO].suspend(sideEffect())          // UEff[IO, A]
```

| Category     | Methods                                                                         |
|--------------|---------------------------------------------------------------------------------|
| Pure         | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful    | `lift(F[Either])`, `lift(F[Option], ifNone)`, `liftF(F[A])`                     |
| Suspended    | `delay(=> Either)`, `defer(=> Eff)`, `suspend(=> A)`, `blocking`, `suspendBlocking` |
| Values       | `succeed`, `fail`, `unit`, `attempt`, `attempt(pf)`                             |
| Temporal     | `sleep(duration)`, `monotonic`, `realTime`                                      |
| Primitives   | `ref(initial)`, `deferred`                                                      |
| Cancellation | `canceled`, `cede`, `never`                                                     |
| Async        | `fromFuture(F[Future], ifFailure)`, `fromFuture(pf)`, `async`, `asyncAttempt(ifDefect)` |
| Conditional  | `when`, `unless`, `raiseWhen`, `raiseUnless`, `cond(pred, ifTrue, ifFalse)`     |
| Collection   | `traverse`, `sequence`, `parTraverse`, `parSequence` (each with a `_` discard variant) |
| Retry        | `retry(eff, maxRetries)`, `retryWithBackoff(eff, maxRetries, delay, maxDelay)`  |

### Eff combinators

| Category     | Methods                                                                      |
|--------------|------------------------------------------------------------------------------|
| Mapping      | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                   |
| Composition  | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap`       |
| Recovery     | `valueOr`, `catchAll`, `catchSome`, `catchOnly`                             |
| Error mapping| `mapError`, `mapErrorPartial`                                                |
| Alternative  | `alt`, `orElseSucceed`, `orElseFail`                                         |
| Folding      | `fold`, `foldF`, `redeemAll`                                                 |
| Observation  | `tap`, `tapError`, `flatTapError`, `attemptTap`                              |
| Variance     | `assume`, `assumeError`                                                       |
| Extraction   | `option`, `collectSome`, `collectRight`                                      |
| Conversion   | `either`, `absolve`, `eitherT`                                               |
| Resource     | `bracket`, `bracketCase`, `timeout`                                          |
| Concurrency  | `start`, `race`, `both`, `background`                                        |
| Temporal     | `delayBy(duration)`, `andWait(duration)`, `timed`, `timeoutTo(dur, fallback)` |
| Cancellation | `onCancel(fin)`, `guarantee(fin)`, `guaranteeCase(fin)`                      |
| Parallel     | `&>`, `<&`                                                                   |

**Observing the typed channel.** The combinators that observe or transform the error - `either`,
`catchAll`, `mapError`, `fold`, `catchOnly`, `option`, `redeemAll`, `orElseFail`, `valueOr`, `alt`,
`tapError`, `attemptTap`, `retry`, ... - filter the caught `Throwable` through a
`TypeTest[Throwable, E]`, re-raising any non-`E` defect unchanged. For a concrete `E` (a sealed
`Throwable` root, or a union of them) the compiler **synthesises** that `TypeTest`, so nothing is
written at the call site; a library `given TypeTest[Throwable, Nothing]` covers the infallible
(`E = Nothing`) case. For the generic `Eff` these combinators additionally need `MonadThrow[F]` -
introducing a failure requires `F`'s `Throwable` channel - whereas the success-only ops
(`succeed`/`map`/`flatMap`/`liftF`) compose over any `Monad`/`Functor` `F`:

```scala
// success-only composition over a non-IO Monad - no MonadThrow, no TypeTest
val overOption: Eff[Option, Nothing, Int] = for
  a <- Eff.succeed[Option, Nothing, Int](20)
  b <- Eff.liftF[Option, Nothing, Int](Some(22))
yield a + b
// overOption.absolve == Some(42)
```

### cats interop

Every cats and cats-effect instance for `F` is available on `Eff.Of[F, E]` (the type lambda
`[A] =>> Eff[F, E, A]`) at no cost - `E` is a phantom, so `F`'s own `Async[F]` **is** the
`Async[Eff.Of[F, E]]`. The one bespoke instance is the typed `MonadError[_, E]`, whose
`handleErrorWith` filters `F`'s `Throwable` channel through a `TypeTest[Throwable, E]`, catching only
a genuine `E` and re-raising any other defect:

<details>
<summary><strong>Effect typeclasses</strong></summary>

| Typeclass                     | Requirement on `F`                       | Capability                           |
|-------------------------------|------------------------------------------|--------------------------------------|
| `Functor`                     | `Functor[F]`                             | `map`                                |
| `Monad`                       | `Monad[F]`                               | `flatMap`, `pure`                    |
| `MonadError[_, E]`            | `MonadThrow[F]`, `TypeTest[Throwable, E]`| Typed error channel `E`              |
| `MonadError[_, EE]`           | `MonadError[F, EE]`                       | Defect channel (e.g. `Throwable`)    |
| `MonadCancel[_, EE]`          | `MonadCancel[F, EE]`                      | Cancellation, `bracket`              |
| `GenSpawn[_, Throwable]`      | `GenSpawn[F, Throwable]`                  | `start`, `race`, fibres              |
| `GenConcurrent[_, Throwable]` | `GenConcurrent[F, Throwable]`             | `Ref`, `Deferred`, `memoize`         |
| `GenTemporal[_, Throwable]`   | `GenTemporal[F, Throwable]`               | `sleep`, `timeout`                   |
| `Sync`                        | `Sync[F]`                                 | `delay`, `blocking`, `interruptible` |
| `Async`                       | `Async[F]`                                | `async`, `evalOn`, `fromFuture`      |
| `Parallel`                    | `Parallel[F]`                             | `.parMapN`, `.parTraverse`           |
| `Clock`                       | `Clock[F]`                                | `monotonic`, `realTime`              |
| `Unique`                      | `Unique[F]`                               | Unique token generation              |
| `Defer`                       | `Defer[F]`                                | Lazy evaluation                      |
| `SemigroupK`                  | `MonadThrow[F]`, `TypeTest[Throwable, E]`| `combineK` / `<+>` (choice via `alt`)|
| `Semigroup`                   | `Monad[F]`, `Semigroup[A]`                | `combine` on success values          |
| `Monoid`                      | `Monad[F]`, `Monoid[A]`                   | `combine` with `empty`               |

</details>

<details>
<summary><strong>Data typeclasses</strong></summary>

| Typeclass      | Requirement on `F`   | Behaviour                                 |
|----------------|----------------------|-------------------------------------------|
| `Show`         | `Show[F[A]]`         | Textual representation (delegates to base)|
| `Eq`           | `Eq[F[A]]`           | Equality comparison                       |
| `PartialOrder` | `PartialOrder[F[A]]` | Partial ordering                          |

Because the error is a `Throwable` in `F`'s channel rather than a foldable value, there are no
`Bifunctor`, `Foldable`, `Traverse`, `Bifoldable`, or `Bitraverse` instances - mapping the error to a
non-`Throwable` would be unsound - and `Show`/`Eq`/`PartialOrder` delegate straight to the base
`F[A]`.

</details>

With `cats.syntax.all.*` in scope, standard cats syntax is available on the typed `MonadError[_, E]`:

| Source             | Methods                                                |
|--------------------|--------------------------------------------------------|
| `ApplicativeError` | `recover`, `recoverWith`, `onError`, `adaptError`      |
| `MonadError`       | `ensure`, `ensureOr`, `rethrow`, `redeem`, `redeemWith`|

### EffIO

`EffIO[E, A]` is the `cats.effect.IO`-specialised sibling of `Eff`, a **phantom over `IO`'s own
error channel**, represented as `IO[A]`. `IO` is covariant and `E` is a phantom absent from the
representation, so `EffIO` is **covariant in both `E` and `A`**: a value of `EffIO[Narrow, A]` is
usable wherever `EffIO[Wide, A]` is expected when `Narrow <: Wide`, with no call-site method.

```scala
def findUser(id: String): EffIO[AppError.NotFound, User] =
  if id == "1" then EffIO.succeed(User("1", "Alice"))
  else EffIO.fail(AppError.NotFound(id))

def validateUser(user: User): EffIO[AppError.Invalid, User] =
  if user.name.nonEmpty then EffIO.succeed(user)
  else EffIO.fail(AppError.Invalid("name required"))

// Distinct error types unify into their union automatically
val workflow: EffIO[AppError, User] = for
  user      <- findUser("1")
  validated <- validateUser(user)
yield validated
```

`Eff[IO, E, A]` behaves identically here: it too is covariant in `E`, so distinct error steps unify
without a call-site cast (the quick start above needs no `widenError`). `EffIO` adds two things over
the generic `Eff`: it fixes `F = IO`, so call sites need neither `using` clauses nor an `[IO]` type
argument, and it is additionally covariant in `A`.

```scala
EffIO.succeed(42)               // UEffIO[Int]
EffIO.fail(AppError.Timeout)    // EffIO[AppError.Timeout.type, Nothing]
EffIO.liftF(IO.pure(42))        // UEffIO[Int]

workflow.map(user => user.name) // EffIO[AppError, String]
workflow.either                 // IO[Either[AppError, User]]
```

Covariance subsumes error widening, so neither `EffIO` nor `Eff` has `widen`/`widenError`; the
trusted narrowing casts `assume`/`assumeError` remain on both. The flip side is shared too: a
`flatMap`/for-comprehension over steps with distinct error types silently infers their union
(`E1 | E2 | ...`), which can grow wider than intended with no compile error - ascribe the result
type, or use `mapError`/`catchOnly`, to contain it.

**Narrowing partial recovery (`catchOnly`).** Covariance lets you handle one arm of a union error
while keeping the rest typed. Given `EffIO[IoError | AppError, A]`, recover the `AppError` arm and the
residual `IoError` is inferred - no annotation needed:

```scala
val consumed: EffIO[IoError | AppError, Unit] = ...
val handled: EffIO[IoError, Unit] = consumed.catchOnly((app: AppError) => log(app))
```

The handler may itself fail into the residual channel. The handled arm must be runtime-testable; an
erasure-ambiguous choice is rejected at the call site. `catchOnly` is available on both `Eff` and
`EffIO` - covariance in `E` drives the residual inference on each.

**Conversion to and from `Eff`.** `EffIO[E, A]` and `Eff[IO, E, A]` share the runtime
representation `IO[A]`, so conversion is a zero-cost identity:

```scala
val asEff: Eff[IO, AppError, User] = workflow.toEff
val asEffIO: EffIO[AppError, User] = EffIO.fromEff(asEff)
```

`EffIO.Of[E]` (the type lambda `[A] =>> EffIO[E, A]`) carries the same cats and cats-effect type
class instances as `Eff.Of[IO, E]`. Natural transformations bridge invariant positions such as
`Resource`:

```scala
val widen: EffIO.Of[AppError.NotFound] ~> EffIO.Of[AppError] = EffIO.widenK[AppError.NotFound, AppError]
val liftK: IO ~> EffIO.Of[Nothing]                           = EffIO.liftK
```

`.effIO` lifting extensions mirror `.eff`, specialised to `IO`:

| Extension                     | Result Type                |
|-------------------------------|----------------------------|
| `IO[A].effIO(ifFailure)`      | `EffIO[E, A]`              |
| `IO[A].effIO`                 | `UEffIO[A]`                |
| `IO[Either[E, A]].effIO`      | `EffIO[E, A]`              |
| `Either[E, A].effIO`          | `EffIO[E, A]`              |
| `Option[A].effIO(ifNone)`     | `EffIO[E, A]`              |
| `IO[Option[A]].effIO(ifNone)` | `EffIO[E, A]`              |
| `Try[A].effIO(ifFailure)`     | `EffIO[E, A]`              |
| `Resource[IO, A].effIO[E]`    | `Resource[EffIO.Of[E], A]` |
| `Resource[IO, A].useEffIO(f)` | `EffIO[E, B]`              |
| `Ref[IO, A].effIO[E]`         | `Ref[EffIO.Of[E], A]`      |

`Deferred`, `Queue`, `Semaphore`, `CountDownLatch`, `CyclicBarrier`, `AtomicCell`, and `Supervisor`
lift the same way.

### Cats-effect primitive interop

**Summon typeclasses directly (preferred):**

```scala
import cats.effect.kernel.GenConcurrent
import scala.concurrent.duration.*

val C = summon[GenConcurrent[Eff.Of[IO, AppError], Throwable]]

val program: Eff[IO, AppError, Int] = for
  ref      <- C.ref(0)
  deferred <- C.deferred[Int]
  _        <- ref.update(_ + 1)
  _        <- deferred.complete(42)
  result   <- deferred.get
yield result
```

**Use Eff factory methods:**

```scala
val convenient: Eff[IO, AppError, Int] = for
  ref  <- Eff.ref[IO, AppError, Int](0)
  _    <- Eff.sleep[IO, AppError](10.millis)
  time <- Eff.monotonic[IO, AppError]
yield 42
```

**Transform existing primitives:**

```scala
// Named lift methods on the Eff companion
Eff.liftResource(resource)   // Resource[Eff.Of[IO, E], A]
Eff.liftRef(ref)             // Ref[Eff.Of[IO, E], A]
Eff.liftDeferred(deferred)   // Deferred[Eff.Of[IO, E], A]
Eff.liftQueue(queue)         // Queue[Eff.Of[IO, E], A]
Eff.liftSemaphore(semaphore) // Semaphore[Eff.Of[IO, E]]
Eff.liftLatch(latch)         // CountDownLatch[Eff.Of[IO, E]]
Eff.liftBarrier(barrier)     // CyclicBarrier[Eff.Of[IO, E]]
Eff.liftCell(cell)           // AtomicCell[Eff.Of[IO, E], A]
Eff.liftSupervisor(sup)      // Supervisor[Eff.Of[IO, E]]

// .eff[E] extension syntax (equivalent) - E must be a Throwable subtype
resource.eff[AppError]
ref.eff[AppError]
deferred.eff[AppError]
queue.eff[AppError]
semaphore.eff[AppError]

// Natural transformations for mapK
val fk: IO ~> Eff.Of[IO, AppError]                              = Eff.functionK[IO, AppError]
val widen: Eff.Of[IO, AppError.NotFound] ~> Eff.Of[IO, AppError] = Eff.widenK[IO, AppError.NotFound, AppError]
```

### Erasing secrets

`boilerplate-effect` depends on core, so a secret [`Slice`](#slice) can be tied to an effect's
lifecycle. `IO[Slice].wiping` is a `Resource` that acquires the slice and wipes it on release - on
success, error, or cancellation:

```scala
import boilerplate.Slice

// make a working copy, use it, and zero it once `use` completes - however it completes
IO(Slice.of(secret.toArray)).wiping.useEffIO(use) // : EffIO[E, A]
```

Keep the copy allocation inside the acquire, so the slice is erased from the moment it exists; it
must not escape `use`.

### Syntax extensions

Importing `boilerplate.effect.*` provides lifting extensions:

| Extension                   | Result Type           |
|------------------------------|-----------------------|
| `Either[E, A].eff[F]`        | `Eff[F, E, A]`        |
| `F[Either[E, A]].eff`        | `Eff[F, E, A]`        |
| `Option[A].eff[F, E](err)`   | `Eff[F, E, A]`        |
| `F[Option[A]].eff[E](err)`   | `Eff[F, E, A]`        |
| `Try[A].eff[F, E](f)`        | `Eff[F, E, A]`        |
| `F[A].eff[E](f)`             | `Eff[F, E, A]`        |
| `F[A].eff`                   | `UEff[F, A]`          |
| `Resource[F, A].eff[E]`      | `Resource[Of[F,E],A]` |
| `Resource[F, A].useEff(f)`   | `Eff[F, E, B]`        |
| `Ref[F, A].eff[E]`           | `Ref[Of[F, E], A]`    |
| `Deferred[F, A].eff[E]`      | `Deferred[Of[F,E],A]` |
| `Queue[F, A].eff[E]`         | `Queue[Of[F, E], A]`  |
| `Semaphore[F].eff[E]`        | `Semaphore[Of[F,E]]`  |

### Fibre join extensions

When working with `Fiber[Eff.Of[F, E], Throwable, A]` (e.g. from `Supervisor.supervise`). A fibre
that failed with a typed error completes as `Outcome.Errored(e)`; the join re-raises `e` on the
effect's channel, while a `Succeeded` returns its value:

| Extension               | Result Type     | On Cancellation        |
|-------------------------|-----------------|------------------------|
| `fiber.joinNever`       | `Eff[F, E, A]`  | Never completes        |
| `fiber.joinOrFail(err)` | `Eff[F, E, A]`  | Fails with typed error |

The same joins are provided for `Fiber[EffIO.Of[E], Throwable, A]`, returning `EffIO[E, A]`.

### Complete example

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.{GenConcurrent, Outcome}
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

sealed abstract class AppError(msg: String) extends Exception(msg) with NoStackTrace derives CanEqual
object AppError:
  final case class NotFound(id: String) extends AppError(s"not found: $id")
  final case class ValidationError(reason: String) extends AppError(s"invalid: $reason")
  case object Cancelled extends AppError("cancelled")
  case object Timeout extends AppError("timed out")

case class User(id: String, name: String)

given C: GenConcurrent[Eff.Of[IO, AppError], Throwable] =
  summon[GenConcurrent[Eff.Of[IO, AppError], Throwable]]

def fetchUser(id: String): Eff[IO, AppError.NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(AppError.NotFound(id))

def validateUser(user: User): Eff[IO, AppError.ValidationError, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(AppError.ValidationError("name required"))

// Distinct typed errors unify into their union automatically - no widenError
val workflow: Eff[IO, AppError, User] = for
  user      <- fetchUser("1")
  validated <- validateUser(user)
yield validated

// Concurrency with typed errors; a fibre's typed failure is Outcome.Errored
val concurrent: Eff[IO, AppError, User] = for
  ref    <- C.ref(0)
  _      <- ref.update(_ + 1)
  fiber  <- workflow.start
  result <- fiber.joinOrFail(AppError.Cancelled)
yield result

// Racing, parallel composition, and timeout
val raced: Eff[IO, AppError, Either[User, User]] =
  workflow.race(workflow)

val parallel: Eff[IO, AppError, (User, User)] =
  workflow.both(workflow)

val withTimeout: Eff[IO, AppError, User] =
  workflow.timeout(5.seconds, AppError.Timeout)

// Guaranteed cleanup - a typed failure surfaces as Outcome.Errored
val withCleanup: Eff[IO, AppError, User] =
  workflow.guaranteeCase {
    case Outcome.Succeeded(_) => Eff.liftF(IO.println("success"))
    case Outcome.Errored(_)   => Eff.liftF(IO.println("error"))
    case Outcome.Canceled()   => Eff.liftF(IO.println("cancelled"))
  }

val io: IO[Either[AppError, User]] = concurrent.either
```

---

## Licence

MIT
