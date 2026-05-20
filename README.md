# Boilerplate

Foundational Scala 3 utilities for opaque type construction, null-safe handling, native platform detection, cross-platform codecs, and zero-cost typed-error effects - targeting JVM, JS, and Native.

## Installation

Each module is published independently. Add the ones you need:

```scala
// Core: opaque types, nullable extensions, platform detection (Native)
libraryDependencies += "io.github.arashi01" %% "boilerplate" % "<version>"

// Codecs: Base64 (JVM, JS, Native)
libraryDependencies += "io.github.arashi01" %% "boilerplate-codecs" % "<version>"

// Effect: typed-error effects atop cats-effect
libraryDependencies += "io.github.arashi01" %% "boilerplate-effect" % "<version>"
```

Use `%%%` for Scala.js or Scala Native cross-builds.

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

### Platform (Scala Native only)

Compile-time operating system detection for Scala Native targets.

```scala
import boilerplate.Platform

// Compile-time branching - unreachable branches are eliminated
inline if Platform.linux then linuxImpl()
else inline if Platform.mac then macImpl()
else windowsImpl()

// Enum value for runtime dispatch
Platform.current match
  case Platform.Linux   => // ...
  case Platform.Mac     => // ...
  case Platform.Windows => // ...
```

| Member    | Type       | Description                                      |
|-----------|------------|--------------------------------------------------|
| `linux`   | `Boolean`  | `true` when building on Linux                    |
| `mac`     | `Boolean`  | `true` when building on macOS                    |
| `windows` | `Boolean`  | `true` when building on Windows                  |
| `current` | `Platform` | Enum value for the build-host OS                 |

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

Zero-cost typed-error effects atop cats-effect. `Eff` and `EffIO` add a compile-time-tracked error
channel `E` separate from `Throwable`, enabling exhaustive error handling with full cats-effect
integration.

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.syntax.all.*
```

### Core types

| Type           | Representation         | Purpose                                   |
|----------------|------------------------|-------------------------------------------|
| `Eff[F, E, A]` | `F[Either[E, A]]`      | Typed-error effect                        |
| `EffIO[E, A]`  | `IO[Either[E, A]]`     | Covariant, `IO`-specialised typed effect  |
| `UEff[F, A]`   | `Eff[F, Nothing, A]`   | Infallible effect                         |
| `TEff[F, A]`   | `Eff[F, Throwable, A]` | Throwable-errored effect                  |
| `UEffIO[A]`    | `EffIO[Nothing, A]`    | Infallible `IO`-specialised effect        |
| `TEffIO[A]`    | `EffIO[Throwable, A]`  | Throwable-errored `IO`-specialised effect |

All opaque types erase at runtime. No wrapper allocation occurs.

### Quick start

```scala
sealed trait AppError
case class NotFound(id: String) extends AppError
case class InvalidInput(msg: String) extends AppError
case object Timeout extends AppError

case class User(id: String, name: String)

def findUser(id: String): Eff[IO, NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(NotFound(id))

def validateUser(user: User): Eff[IO, InvalidInput, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(InvalidInput("name required"))

// for-comprehension with error unification
val workflow: Eff[IO, AppError, User] = for
  user      <- findUser("1").widenError[AppError]
  validated <- validateUser(user).widenError[AppError]
yield validated

// Exhaustive error handling
val result: IO[String] = workflow.fold(
  {
    case NotFound(id)      => s"User $id not found"
    case InvalidInput(msg) => s"Invalid: $msg"
    case Timeout           => "timed out"
  },
  user => s"Welcome ${user.name}"
)

// Run the underlying effect
val io: IO[Either[AppError, User]] = workflow.either
```

### Eff constructors

Partially-applied constructors minimise type annotations:

```scala
Eff[IO].succeed(42)           // UEff[IO, Int]
Eff[IO].fail("boom")          // Eff[IO, String, Nothing]
Eff[IO].from(Right(1))        // Eff[IO, Nothing, Int]
Eff[IO].liftF(IO.pure(42))    // UEff[IO, Int]
Eff[IO].unit                  // UEff[IO, Unit]
Eff[IO].suspend(sideEffect()) // UEff[IO, A]
```

| Category     | Methods                                                                         |
|--------------|---------------------------------------------------------------------------------|
| Pure         | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful    | `lift(F[Either])`, `lift(F[Option], ifNone)`, `liftF(F[A])`                     |
| Suspended    | `delay(=> Either)`, `defer(=> Eff)`, `suspend(=> A)`                            |
| Values       | `succeed`, `fail`, `unit`, `attempt`                                            |
| Temporal     | `sleep(duration)`, `monotonic`, `realTime`                                      |
| Primitives   | `ref(initial)`, `deferred`                                                      |
| Cancellation | `canceled`, `cede`, `never`                                                     |
| Async        | `fromFuture(F[Future], ifFailure)`                                              |
| Conditional  | `when(cond)(eff)`, `unless(cond)(eff)`, `raiseWhen(cond)(err)`, `raiseUnless`   |
| Collection   | `traverse`, `sequence`, `parTraverse`, `parSequence`                            |
| Retry        | `retry(eff, maxRetries)`, `retryWithBackoff(eff, maxRetries, delay, maxDelay)`  |

### Eff combinators

| Category     | Methods                                                                      |
|--------------|------------------------------------------------------------------------------|
| Mapping      | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                   |
| Composition  | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap`       |
| Recovery     | `valueOr`, `catchAll`                                                        |
| Error mapping| `mapError`, `mapErrorPartial`                                                |
| Alternative  | `alt`, `orElseSucceed`, `orElseFail`                                         |
| Folding      | `fold`, `foldF`, `redeemAll`                                                 |
| Observation  | `tap`, `tapError`, `flatTapError`, `attemptTap`                              |
| Variance     | `widen`, `widenError`, `assume`, `assumeError`                               |
| Extraction   | `option`, `collectSome`, `collectRight`                                      |
| Conversion   | `either`, `absolve`, `eitherT`                                               |
| Resource     | `bracket`, `bracketCase`, `timeout`                                          |
| Concurrency  | `start`, `race`, `both`, `background`                                        |
| Temporal     | `delayBy(duration)`, `andWait(duration)`, `timed`, `timeoutTo(dur, fallback)` |
| Cancellation | `onCancel(fin)`, `guarantee(fin)`, `guaranteeCase(fin)`                      |
| Parallel     | `&>`, `<&`                                                                   |

### cats interop

`Eff.Of[F, E]` (the type lambda `[A] =>> Eff[F, E, A]`) derives typeclass instances from the underlying `F`:

<details>
<summary><strong>Effect typeclasses</strong></summary>

| Typeclass                     | Requirement on `F`            | Capability                           |
|-------------------------------|-------------------------------|--------------------------------------|
| `Functor`                     | `Functor[F]`                  | `map`                                |
| `Bifunctor`                   | `Functor[F]`                  | `bimap`, `leftMap`                   |
| `Monad`                       | `Monad[F]`                    | `flatMap`, `pure`                    |
| `MonadError[_, E]`            | `Monad[F]`                    | Typed error channel                  |
| `MonadError[_, EE]`           | `MonadError[F, EE]`           | Defect channel (e.g. `Throwable`)    |
| `MonadCancel[_, EE]`          | `MonadCancel[F, EE]`          | Cancellation, `bracket`              |
| `GenSpawn[_, Throwable]`      | `GenSpawn[F, Throwable]`      | `start`, `race`, fibres              |
| `GenConcurrent[_, Throwable]` | `GenConcurrent[F, Throwable]` | `Ref`, `Deferred`, `memoize`         |
| `GenTemporal[_, Throwable]`   | `GenTemporal[F, Throwable]`   | `sleep`, `timeout`                   |
| `Sync`                        | `Sync[F]`                     | `delay`, `blocking`, `interruptible` |
| `Async`                       | `Async[F]`                    | `async`, `evalOn`, `fromFuture`      |
| `Parallel`                    | `Parallel[F]`                 | `.parMapN`, `.parTraverse`           |
| `Clock`                       | `Clock[F]`                    | `monotonic`, `realTime`              |
| `Unique`                      | `Unique[F]`                   | Unique token generation              |
| `Defer`                       | `Defer[F]`                    | Lazy evaluation                      |
| `SemigroupK`                  | `Monad[F]`                    | `combineK` / `<+>`                   |
| `Semigroup`                   | `Monad[F]`, `Semigroup[A]`    | `combine` on success values          |
| `Monoid`                      | `Monad[F]`, `Monoid[A]`       | `combine` with `empty`               |

</details>

<details>
<summary><strong>Data typeclasses</strong></summary>

| Typeclass      | Requirement on `F`              | Behaviour                                      |
|----------------|---------------------------------|------------------------------------------------|
| `Show`         | `Show[F[Either[E, A]]]`        | Textual representation                         |
| `Eq`           | `Eq[F[Either[E, A]]]`          | Equality comparison                            |
| `PartialOrder` | `PartialOrder[F[Either[E,A]]]` | Partial ordering                               |
| `Foldable`     | `Foldable[F]`                  | Fold over success channel; errors fold empty   |
| `Traverse`     | `Traverse[F]`                  | Traverse success channel                       |
| `Bifoldable`   | `Foldable[F]`                  | Fold over both channels                        |
| `Bitraverse`   | `Traverse[F]`                  | Traverse both channels                         |

</details>

With `cats.syntax.all.*` in scope, standard cats syntax is available:

| Source             | Methods                                                |
|--------------------|--------------------------------------------------------|
| `Bifunctor`        | `bimap`, `leftMap`                                     |
| `ApplicativeError` | `recover`, `recoverWith`, `onError`, `adaptError`      |
| `MonadError`       | `ensure`, `ensureOr`, `rethrow`, `redeem`, `redeemWith`|

### EffIO

`EffIO[E, A]` is a covariant, `cats.effect.IO`-specialised sibling of `Eff`, represented as
`IO[Either[E, A]]`. `IO` and `Either` are both covariant, so `EffIO` is **covariant in `E` and
`A`**: a value of `EffIO[Narrow, A]` is usable wherever `EffIO[Wide, A]` is expected, with no
call-site method.

```scala
def findUser(id: String): EffIO[NotFound, User] =
  if id == "1" then EffIO.succeed(User("1", "Alice"))
  else EffIO.fail(NotFound(id))

def validateUser(user: User): EffIO[InvalidInput, User] =
  if user.name.nonEmpty then EffIO.succeed(user)
  else EffIO.fail(InvalidInput("name required"))

// Distinct error types unify automatically - no widenError
val workflow: EffIO[AppError, User] = for
  user      <- findUser("1")
  validated <- validateUser(user)
yield validated
```

`Eff` is invariant in `E` - it stays `F`-polymorphic, so `F[Either[E, A]]` cannot be proven
covariant - and needs an explicit `widenError[AppError]` on each step (compare the quick start
above). Fixing `F = IO` removes that obstacle.

`EffIO` mirrors `Eff`'s constructor and combinator surface with `F` fixed to `IO`, so call sites
need neither `using` clauses nor an `[IO]` type argument:

```scala
EffIO.succeed(42)               // UEffIO[Int]
EffIO.fail("boom")              // EffIO[String, Nothing]
EffIO.liftF(IO.pure(42))        // UEffIO[Int]

workflow.map(user => user.name) // EffIO[AppError, String]
workflow.mapError(translate)    // EffIO[E2, User]
workflow.either                 // IO[Either[AppError, User]]
```

Covariance subsumes error widening, so `EffIO` has no `widen`/`widenError`; the trusted narrowing
casts `assume`/`assumeError` remain.

**Conversion to and from `Eff`.** `EffIO[E, A]` and `Eff[IO, E, A]` share the runtime
representation `IO[Either[E, A]]`, so conversion is a zero-cost identity:

```scala
val asEff: Eff[IO, AppError, User] = workflow.toEff
val asEffIO: EffIO[AppError, User] = EffIO.fromEff(asEff)
```

`EffIO.Of[E]` (the type lambda `[A] =>> EffIO[E, A]`) carries the same cats and cats-effect type
class instances as `Eff.Of[IO, E]`. Natural transformations bridge invariant positions such as
`Resource`:

```scala
val widen: EffIO.Of[NotFound] ~> EffIO.Of[AppError] = EffIO.widenK[NotFound, AppError]
val liftK: IO ~> EffIO.Of[Nothing]                  = EffIO.liftK
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
| `Ref[IO, A].effIO[E]`         | `Ref[EffIO.Of[E], A]`      |

`Deferred`, `Queue`, `Semaphore`, `CountDownLatch`, `CyclicBarrier`, `AtomicCell`, and `Supervisor`
lift the same way.

> **Known limitation.** Observing the outcome or fibre of an *infallible* `EffIO` (error type
> `Nothing`) via `start`, `guaranteeCase`, `background`, or `bracketCase` triggers a Scala 3.8
> pickler bug. Ascribe a concrete error type at the call site - `(eff: EffIO[MyError, A]).start` -
> as a workaround.

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

// .eff[E] extension syntax (equivalent)
resource.eff[MyError]
ref.eff[MyError]
deferred.eff[MyError]
queue.eff[MyError]
semaphore.eff[MyError]

// Natural transformations for mapK
val fk: IO ~> Eff.Of[IO, MyError]                      = Eff.functionK[IO, MyError]
val widen: Eff.Of[IO, MyError] ~> Eff.Of[IO, AppError] = Eff.widenK[IO, MyError, AppError]
```

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
| `Resource[F, A].eff[E]`      | `Resource[Of[F,E],A]` |
| `Ref[F, A].eff[E]`           | `Ref[Of[F, E], A]`    |
| `Deferred[F, A].eff[E]`      | `Deferred[Of[F,E],A]` |
| `Queue[F, A].eff[E]`         | `Queue[Of[F, E], A]`  |
| `Semaphore[F].eff[E]`        | `Semaphore[Of[F,E]]`  |

### Fibre join extensions

When working with `Fiber[Eff.Of[F, E], Throwable, A]` (e.g. from `Supervisor.supervise`):

| Extension               | Result Type     | On Cancellation        |
|-------------------------|-----------------|------------------------|
| `fiber.joinNever`       | `Eff[F, E, A]`  | Never completes        |
| `fiber.joinOrFail(err)` | `Eff[F, E, A]`  | Fails with typed error |

### Complete example

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.{GenConcurrent, Outcome}
import cats.syntax.all.*
import scala.concurrent.duration.*

sealed trait AppError
case class NotFound(id: String) extends AppError
case class ValidationError(msg: String) extends AppError
case object Cancelled extends AppError
case object Timeout extends AppError

case class User(id: String, name: String)

given C: GenConcurrent[Eff.Of[IO, AppError], Throwable] =
  summon[GenConcurrent[Eff.Of[IO, AppError], Throwable]]

def fetchUser(id: String): Eff[IO, NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(NotFound(id))

def validateUser(user: User): Eff[IO, ValidationError, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(ValidationError("name required"))

val workflow: Eff[IO, AppError, User] = for
  user      <- fetchUser("1").widenError[AppError]
  validated <- validateUser(user).widenError[AppError]
yield validated

// Concurrency with typed errors
val concurrent: Eff[IO, AppError, User] = for
  ref    <- C.ref(0)
  _      <- ref.update(_ + 1)
  fiber  <- workflow.start
  result <- fiber.join.flatMap {
              case Outcome.Succeeded(fa) => fa
              case Outcome.Errored(e)    => Eff.liftF(IO.raiseError(e))
              case Outcome.Canceled()    => Eff.fail(Cancelled)
            }
yield result

// Racing, parallel composition, and timeout
val raced: Eff[IO, AppError, Either[User, User]] =
  workflow.race(workflow)

val parallel: Eff[IO, AppError, (User, User)] =
  workflow.both(workflow)

val withTimeout: Eff[IO, AppError, User] =
  workflow.timeout(5.seconds, Timeout)

// Guaranteed cleanup
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
