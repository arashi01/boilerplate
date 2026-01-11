# Boilerplate

Foundational Scala 3 utilities: opaque type construction, null-safe handling, and zero-cost typed-error effects.

## Modules

- **`boilerplate`** — Core utilities for opaque types and nullable values
- **`boilerplate-effect`** — Optional typed-error effects atop cats-effect

---

### boilerplate (core)

```scala
libraryDependencies += "io.github.arashi01" %% "boilerplate" % "<version>"
```

#### OpaqueType

Base trait for opaque type companions providing validated construction.

```scala
import boilerplate.OpaqueType

opaque type UserId = String
object UserId extends OpaqueType[UserId]:
  type Type  = String
  type Error = IllegalArgumentException

  inline def wrap(s: String): UserId    = s
  inline def unwrap(id: UserId): String = id

  def validate(s: String): Error | Unit =
    if s.nonEmpty then () else new IllegalArgumentException("UserId cannot be empty")

// Construction via companion
val id: Either[IllegalArgumentException, UserId] = UserId.from("user-123")
val direct: UserId = UserId.fromUnsafe("user-123")  // Throws on invalid input

// Construction via extension syntax
val id2: Either[IllegalArgumentException, UserId] = "user-123".as[UserId]
val direct2: UserId = "user-123".asUnsafe[UserId]

// Extraction via unwrap (defined in companion)
val underlying: String = UserId.unwrap(direct)
```

The trait provides:

| Member                  | Description                                                      |
|-------------------------|------------------------------------------------------------------|
| `type Type`             | The underlying representation type                               |
| `type Error`            | The validation error type (must extend `Throwable`)              |
| `wrap(value)`           | Wraps a raw value as the opaque type (no validation)             |
| `unwrap(value)`         | Extracts the underlying value from the opaque type               |
| `validate(value)`       | Returns `()` on success or the error instance on failure         |
| `from(value)`           | Validated construction returning `Either[Error, A]`              |
| `fromUnsafe(value)`     | Throws `Error` on validation failure                             |
| `value.as[A]`           | Extension syntax for `from`: `"x".as[UserId]`                    |
| `value.asUnsafe[A]`     | Extension syntax for `fromUnsafe`: `"x".asUnsafe[UserId]`        |
| `value.unwrap`          | Extension syntax for `unwrap`: `userId.unwrap`                   |

#### nullable

Extensions for Scala 3's explicit nulls feature. Inline null checks become verbose without a `CanEqual` instance; these
extensions provide concise, type-safe null handling.

**Extensions on `A | Null`:**

| Method              | Description                                                   |
|---------------------|---------------------------------------------------------------|
| `option`            | `Some(value)` if non-null, `None` otherwise                   |
| `either(leftError)` | `Right(value)` if non-null, `Left(leftError)` otherwise       |
| `mapOpt(f)`         | Maps through `f` if non-null, returning `Option[B]`           |
| `flatMapOpt(f)`     | FlatMaps through `f: A => Option[B]` if non-null              |

**Extensions on `Option[A | Null]`:**

Useful when combining `Option`-returning operations with nullable values from Java interop.

| Method           | Description                                                         |
|------------------|---------------------------------------------------------------------|
| `flattenNull`    | Converts `Some(null)` to `None`                                     |
| `mapNull(f)`     | Maps through `f`, treating inner null as `None`                     |
| `flatMapNull(f)` | FlatMaps through `f: A => Option[B]`, treating inner null as `None` |

**Extensions on `Either[E, A | Null]`:**

| Method                      | Description                                                            |
|-----------------------------|------------------------------------------------------------------------|
| `flattenNull(leftError)`    | Converts `Right(null)` to `Left(leftError)`                            |
| `mapNull(leftError)(f)`     | Maps through `f`, treating inner null as `Left`                        |
| `flatMapNull(leftError)(f)` | FlatMaps through `f: A => Either[E, B]`, treating inner null as `Left` |

```scala
import boilerplate.nullable.*

// Basic null handling
val value: String | Null = possiblyNullValue()
value.option              // Option[String]
value.either("was null")  // Either[String, String]

// Option[A | Null] from Java interop
val opt: Option[String | Null] = Some(javaMethod())
opt.flattenNull           // Option[String] — Some(null) becomes None

// Chaining with Either
val result: Either[String, String | Null] = Right(javaMethod())
result.flattenNull("null value")  // Either[String, String]
```

---

### boilerplate-effect

Zero-cost typed-error effects layered atop cats-effect.

Standard `MonadError[F, Throwable]` conflates recoverable domain errors with fatal defects. `Eff[F, E, A]` provides an
explicit, compile-time-tracked error channel `E` separate from `Throwable`, enabling exhaustive pattern matching on
failure cases whilst preserving full cats-effect integration.

**Core abstractions:**

- **`Eff[F, E, A]`** — opaque type equal to `F[Either[E, A]]` with zero runtime overhead
- **`EffR[F, R, E, A]`** — reader-style variant equal to `R => Eff[F, E, A]`

Both erase at runtime whilst maintaining compile-time awareness of error and environment types.

**Note:** `Eff`/`EffR` are not ZIO replacements. They provide a minimal-cost wrapper for cats-effect codebases wanting
compile-time typed errors without ecosystem changes—cleaner syntax than manually threading `EitherT` or composing
`Kleisli[EitherT[F, E, *], R, A]`.

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.{GenConcurrent, GenTemporal}
import cats.syntax.all.*
import scala.concurrent.duration.*

// Domain errors are explicit in the type signature
sealed trait AppError
case class NotFound(id: String) extends AppError
case class InvalidInput(msg: String) extends AppError
case object Timeout extends AppError

case class User(id: String, name: String, age: Int)
case class ValidUser(user: User)

def findUser(id: String): Eff[IO, NotFound, User] = ???
def validateAge(user: User): Eff[IO, InvalidInput, ValidUser] = ???

// Compose with for-comprehensions; errors are tracked and unified
val workflow: Eff[IO, AppError, ValidUser] = for
  user  <- findUser("123").widenError[AppError]
  valid <- validateAge(user).widenError[AppError]
yield valid

// Handle each error case exhaustively
val result: IO[String] = workflow.fold(
  {
    case NotFound(id)      => s"User $id not found"
    case InvalidInput(msg) => s"Invalid: $msg"
    case Timeout           => "Request timed out"
  },
  user => s"Welcome ${user.user.name}"
)

// Recover from all errors with a fallback value
val defaultUser = ValidUser(User("default", "Guest", 0))
val fallback: UEff[IO, ValidUser] = workflow.valueOr(_ => defaultUser)

// Recover from specific errors via cats ApplicativeError syntax
val recovered: Eff[IO, AppError, ValidUser] =
  workflow.recover { case NotFound(_) => defaultUser }

// Transform the error channel via cats Bifunctor syntax
val mapped: Eff[IO, String, ValidUser] = workflow.leftMap(_.toString)

// Stay entirely in Eff using cats-effect typeclasses
val C = summon[GenConcurrent[Eff.Of[IO, AppError], Throwable]]
val T = summon[GenTemporal[Eff.Of[IO, AppError], Throwable]]

// Create primitives directly in Eff context
val program: Eff[IO, AppError, Int] = for
  ref      <- C.ref(0)                              // Ref[Eff.Of[IO, AppError], Int]
  deferred <- C.deferred[Int]                       // Deferred[Eff.Of[IO, AppError], Int]
  _        <- ref.update(_ + 1)
  _        <- deferred.complete(42)
  value    <- deferred.get
  _        <- T.sleep(10.millis)                    // Temporal operations
yield value

// Or use Eff's convenience factories
val convenient: Eff[IO, AppError, Int] = for
  ref   <- Eff.ref[IO, AppError, Int](0)            // Direct factory
  _     <- Eff.sleep[IO, AppError](10.millis)       // Temporal factory
  time  <- Eff.monotonic[IO, AppError]              // Clock access
  _     <- Eff.when[IO, AppError](time.toMillis > 0)(Eff.unit)
yield 42

// Concurrency with typed errors
val concurrent: Eff[IO, AppError, (ValidUser, Int)] = for
  fiber  <- workflow.start                          // Start as fibre
  value  <- Eff.succeed[IO, AppError, Int](42)
  result <- fiber.join.flatMap {
              case cats.effect.kernel.Outcome.Succeeded(fa) => fa
              case cats.effect.kernel.Outcome.Errored(e)    => Eff.liftF(IO.raiseError(e))
              case cats.effect.kernel.Outcome.Canceled()    => Eff.fail(Timeout)
            }
yield (result, value)

// Racing and parallel composition
val raced: Eff[IO, AppError, Either[ValidUser, Int]] =
  workflow.race(Eff.succeed(42))

val both: Eff[IO, AppError, (ValidUser, Int)] =
  workflow.both(Eff.succeed(42))

// Timeout with typed error
val withTimeout: Eff[IO, AppError, ValidUser] =
  workflow.timeout(5.seconds, Timeout)
```

#### Dependency

```scala
libraryDependencies += "io.github.arashi01" %% "boilerplate-effect" % "<version>"
```

#### Type Aliases

| Alias            | Expansion                  | Description                     |
|------------------|----------------------------|---------------------------------|
| `UEff[F, A]`     | `Eff[F, Nothing, A]`       | Infallible effect               |
| `TEff[F, A]`     | `Eff[F, Throwable, A]`     | Throwable-errored effect        |
| `UEffR[F, R, A]` | `EffR[F, R, Nothing, A]`   | Infallible reader effect        |
| `TEffR[F, R, A]` | `EffR[F, R, Throwable, A]` | Throwable-errored reader effect |

#### `Eff[F, E, A]`

##### Constructors

Partially-applied constructors minimise type annotations:

```scala
import boilerplate.effect.*
import cats.effect.IO

Eff[IO].succeed(42)           // UEff[IO, Int]
Eff[IO].fail("boom")          // Eff[IO, String, Nothing]
Eff[IO].from(Right(1))        // Eff[IO, Nothing, Int]
Eff[IO].liftF(IO.pure(42))    // UEff[IO, Int]
Eff[IO].unit                  // UEff[IO, Unit]
Eff[IO].suspend(sideEffect()) // UEff[IO, A] — synchronous side effect
```

Full constructors:

| Category    | Methods                                                                         |
|-------------|---------------------------------------------------------------------------------|
| Pure        | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful   | `lift(F[Either])`, `lift(F[Option], ifNone)`, `liftF(F[A])`                     |
| Suspended   | `delay(=> Either)`, `defer(=> Eff)`, `suspend(=> A)`                            |
| Values      | `succeed`, `fail`, `unit`, `attempt`                                            |
| Temporal    | `sleep(duration)`, `monotonic`, `realTime`                                      |
| Primitives  | `ref(initial)`, `deferred`                                                      |
| Cancellation| `canceled`, `cede`, `never`                                                     |
| Async       | `fromFuture(F[Future], ifFailure)`                                              |
| Conditional | `when(cond)(eff)`, `unless(cond)(eff)`, `raiseWhen(cond)(err)`, `raiseUnless`   |
| Collection  | `traverse`, `sequence`, `parTraverse`, `parSequence`                            |
| Retry       | `retry(eff, maxRetries)`, `retryWithBackoff(eff, maxRetries, delay, maxDelay)`  |

##### Combinators

| Category    | Methods                                                                    |
|-------------|----------------------------------------------------------------------------|
| Mapping     | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                 |
| Composition | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap`     |
| Recovery    | `valueOr`, `catchAll`                                                      |
| Alternative | `alt`, `orElseSucceed`, `orElseFail`                                       |
| Folding     | `fold`, `foldF`, `redeemAll`                                               |
| Observation | `tap`, `tapError`, `flatTapError`, `attemptTap`                            |
| Variance    | `widen`, `widenError`, `assume`, `assumeError`                             |
| Extraction  | `option`, `collectSome`, `collectRight`                                    |
| Conversion  | `either`, `absolve`, `eitherT`                                             |
| Resource    | `bracket`, `bracketCase`, `timeout`                                        |
| Concurrency | `start`, `race`, `both`, `background`                                      |
| Temporal    | `delayBy(duration)`, `andWait(duration)`, `timed`, `timeoutTo(dur, fallback)` |
| Cancellation| `onCancel(fin)`, `guarantee(fin)`, `guaranteeCase(fin)`                    |
| Parallel    | `&>`, `<&` (parallel product operators)                                    |

**cats syntax methods** (available via `cats.syntax.all.*` on our typeclass instances):

| Category  | Methods                                               | Typeclass         |
|-----------|-------------------------------------------------------|-------------------|
| Bifunctor | `bimap`, `leftMap`                                    | `Bifunctor`       |
| Recovery  | `recover`, `recoverWith`, `onError`, `adaptError`     | `ApplicativeError`|
| Guards    | `ensure`, `ensureOr`                                  | `MonadError`      |
| Folding   | `redeem`, `redeemWith`                                | `ApplicativeError`|
| Conversion| `rethrow`                                             | `MonadError`      |

**Convenience method naming notes:**

- `valueOr(f: E => A)` — total recovery mapping all errors to success; named to avoid collision with cats'
  `recover(pf: PartialFunction)` which uses `PartialFunction`
- `catchAll(f: E => Eff)` — total recovery switching to alternative computation
- `redeemAll(fe, fa)` — effectful fold allowing error type change `E => E2`; named to distinguish from cats'
  `redeemWith` which preserves error type

##### Typeclass Instances

`Eff.Of[F, E]` (the type lambda `[A] =>> Eff[F, E, A]`) derives instances based on the underlying `F`:

**Effect Typeclasses**

| Typeclass                     | Requirement on `F`            | Capability                           |
|-------------------------------|-------------------------------|--------------------------------------|
| `Functor`                     | `Functor[F]`                  | `map`                                |
| `Bifunctor`                   | `Functor[F]`                  | `bimap`, `leftMap`                   |
| `Monad`                       | `Monad[F]`                    | `flatMap`, `pure`                    |
| `MonadError[_, E]`            | `Monad[F]`                    | Typed error channel (`E`)            |
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

**Data Typeclasses**

| Typeclass                     | Requirement on `F`            | Behaviour                                        |
|-------------------------------|-------------------------------|--------------------------------------------------|
| `Show`                        | `Show[F[Either[E, A]]]`       | Textual representation via `show`                |
| `Eq`                          | `Eq[F[Either[E, A]]]`         | Equality comparison                              |
| `PartialOrder`                | `PartialOrder[F[Either[E,A]]]`| Partial ordering comparison                      |
| `Foldable`                    | `Foldable[F]`                 | Fold over success channel; errors treated empty  |
| `Traverse`                    | `Traverse[F]`                 | Traverse success channel; errors pass through    |
| `Bifoldable`                  | `Foldable[F]`                 | Fold over both error and success channels        |
| `Bitraverse`                  | `Traverse[F]`                 | Traverse both error and success channels         |

`EffR.Of[F, R, E]` mirrors effect typeclass instances, threading the environment through all operations.
Note that data typeclasses (`Show`, `Eq`, `Foldable`, etc.) are not available for `EffR` as it is representationally
a function type (`R => Eff[F, E, A]`).


#### `EffR[F, R, E, A]`

Adds an immutable environment channel, representationally equal to `R => Eff[F, E, A]`.

##### Constructors

```scala
type Config = String

EffR[IO, Config].succeed(42)   // UEffR[IO, Config, Int]
EffR[IO, Config].fail("err")   // EffR[IO, Config, String, Nothing]
EffR[IO, Config].ask           // EffR[IO, Config, Nothing, Config] — retrieves environment
```

Full constructors:

| Category    | Methods                                                                         |
|-------------|---------------------------------------------------------------------------------|
| Pure        | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)`, `from(Kleisli)` |
| Effectful   | `lift(Eff)`, `lift(F[Either])`, `lift(F[Option], ifNone)`                       |
| Suspended   | `delay(=> Either)`, `defer(=> EffR)`, `suspend(=> A)`                           |
| Values      | `succeed`, `fail`, `unit`, `attempt`                                            |
| Environment | `ask`, `wrap`, `fromContext`                                                    |
| Temporal    | `sleep(duration)`, `monotonic`, `realTime`                                      |
| Primitives  | `ref(initial)`, `deferred`                                                      |
| Cancellation| `canceled`, `cede`, `never`                                                     |
| Async       | `fromFuture(F[Future], ifFailure)`                                              |
| Conditional | `when(cond)(eff)`, `unless(cond)(eff)`, `raiseWhen(cond)(err)`, `raiseUnless`   |

##### Combinators

Mirrors `Eff` combinators, plus environment-specific operations:

| Category    | Methods                                                                    |
|-------------|----------------------------------------------------------------------------|
| Environment | `provide`, `run`, `contramap`, `andThen`                                   |
| Mapping     | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                 |
| Composition | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap`     |
| Recovery    | `valueOr`, `catchAll`                                                      |
| Alternative | `alt`, `orElseSucceed`, `orElseFail`                                       |
| Folding     | `fold`, `foldF`, `redeemAll`                                               |
| Observation | `tap`, `tapError`, `flatTapError`, `attemptTap`                            |
| Variance    | `widen`, `widenError`, `widenEnv`, `assume`, `assumeError`, `assumeEnv`    |
| Extraction  | `option`, `collectSome`, `collectRight`                                    |
| Conversion  | `either`, `absolve`, `kleisli`                                             |
| Resource    | `bracket`, `bracketCase`, `timeout`                                        |
| Concurrency | `start`, `race`, `both`                                                    |
| Temporal    | `delayBy(duration)`, `andWait(duration)`, `timed`, `timeoutTo(dur, fallback)` |
| Cancellation| `onCancel(fin)`, `guarantee(fin)`, `guaranteeCase(fin)`                    |
| Parallel    | `&>`, `<&` (parallel product operators)                                    |

With `cats.syntax.all.*` in scope, additional methods from cats typeclasses are available:

| Typeclass          | Methods                                                  |
|--------------------|----------------------------------------------------------|
| `Bifunctor`        | `bimap`, `leftMap`                                       |
| `ApplicativeError` | `recover`, `recoverWith`, `onError`, `adaptError`, `redeem` |
| `MonadError`       | `ensure`, `ensureOr`, `rethrow`, `redeemWith`            |

#### Cats-Effect Primitive Interop

There are two approaches to working with cats-effect primitives in the `Eff` context:

**1. Use typeclasses directly (preferred for staying in Eff):**

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.{GenConcurrent, GenTemporal}
import cats.syntax.all.*
import scala.concurrent.duration.*

// Summon the cats-effect typeclasses parameterised over Eff
val C = summon[GenConcurrent[Eff.Of[IO, AppError], Throwable]]
val T = summon[GenTemporal[Eff.Of[IO, AppError], Throwable]]

// Create primitives that already operate in Eff context
val program: Eff[IO, AppError, Int] = for
  ref      <- C.ref(0)                    // Ref[Eff.Of[IO, AppError], Int]
  deferred <- C.deferred[Int]             // Deferred[Eff.Of[IO, AppError], Int]
  _        <- ref.update(_ + 1)           // Operations stay in Eff
  _        <- deferred.complete(42)
  _        <- T.sleep(10.millis)          // Temporal operations
  result   <- deferred.get
yield result
```

**2. Use Eff factory methods (convenience wrappers):**

```scala
// Direct factory methods on Eff companion
val convenient: Eff[IO, AppError, Int] = for
  ref   <- Eff.ref[IO, AppError, Int](0)
  def   <- Eff.deferred[IO, AppError, Int]
  _     <- Eff.sleep[IO, AppError](10.millis)
  time  <- Eff.monotonic[IO, AppError]
yield 42
```

**3. Transform existing primitives via lift methods or `.eff[E]` extension:**

```scala
import cats.effect.kernel.{Ref, Resource, Deferred}
import cats.effect.std.{Queue, Semaphore}

// Named companion object methods
Eff.liftResource(resource)   // Resource[Eff.Of[IO, MyError], A]
Eff.liftRef(ref)             // Ref[Eff.Of[IO, MyError], A]
Eff.liftDeferred(deferred)   // Deferred[Eff.Of[IO, MyError], A]
Eff.liftQueue(queue)         // Queue[Eff.Of[IO, MyError], A]
Eff.liftSemaphore(semaphore) // Semaphore[Eff.Of[IO, MyError]]
Eff.liftLatch(latch)         // CountDownLatch[Eff.Of[IO, MyError]]
Eff.liftBarrier(barrier)     // CyclicBarrier[Eff.Of[IO, MyError]]
Eff.liftCell(cell)           // AtomicCell[Eff.Of[IO, MyError], A]
Eff.liftSupervisor(sup)      // Supervisor[Eff.Of[IO, MyError]]

// Extension syntax (equivalent)
resource.eff[MyError]
ref.eff[MyError]
deferred.eff[MyError]
queue.eff[MyError]
semaphore.eff[MyError]

// Natural transformation for custom mapK usage
val fk: IO ~> Eff.Of[IO, MyError] = Eff.functionK[IO, MyError]
```

| Primitive        | Lifted Type                    | Constraints                 |
|------------------|--------------------------------|-----------------------------|
| `Resource`       | `Resource[Eff.Of[F, E], A]`    | `MonadCancel[F, Throwable]` |
| `Ref`            | `Ref[Eff.Of[F, E], A]`         | `Functor[F]`                |
| `Deferred`       | `Deferred[Eff.Of[F, E], A]`    | `Functor[F]`                |
| `Queue`          | `Queue[Eff.Of[F, E], A]`       | `Functor[F]`                |
| `Semaphore`      | `Semaphore[Eff.Of[F, E]]`      | `MonadCancel[F, Throwable]` |
| `CountDownLatch` | `CountDownLatch[Eff.Of[F, E]]` | `Functor[F]`                |
| `CyclicBarrier`  | `CyclicBarrier[Eff.Of[F, E]]`  | `Functor[F]`                |
| `AtomicCell`     | `AtomicCell[Eff.Of[F, E], A]`  | `Monad[F]`                  |
| `Supervisor`     | `Supervisor[Eff.Of[F, E]]`     | `Functor[F]`                |

#### Complete Example: Staying Entirely in Eff

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

// Summon typeclasses for Eff operations
given C: GenConcurrent[Eff.Of[IO, AppError], Throwable] =
  summon[GenConcurrent[Eff.Of[IO, AppError], Throwable]]

// Domain operations returning Eff
def fetchUser(id: String): Eff[IO, NotFound, User] =
  if id == "1" then Eff.succeed(User("1", "Alice"))
  else Eff.fail(NotFound(id))

def validateUser(user: User): Eff[IO, ValidationError, User] =
  if user.name.nonEmpty then Eff.succeed(user)
  else Eff.fail(ValidationError("Name cannot be empty"))

// Compose operations with typed error unification
val workflow: Eff[IO, AppError, User] = for
  user      <- fetchUser("1").widenError[AppError]
  validated <- validateUser(user).widenError[AppError]
yield validated

// Use cats-effect primitives via typeclass instances
val concurrent: Eff[IO, AppError, User] = for
  ref    <- C.ref(0)                               // Create Ref in Eff context
  _      <- ref.update(_ + 1)                      // Update stays in Eff
  fiber  <- workflow.start                         // Start as fibre
  result <- fiber.join.flatMap {
              case Outcome.Succeeded(fa) => fa
              case Outcome.Errored(e)    => Eff.liftF(IO.raiseError(e))
              case Outcome.Canceled()    => Eff.fail(Cancelled)
            }
yield result

// Racing and parallel operations
val raced: Eff[IO, AppError, Either[User, User]] =
  workflow.race(workflow)                          // First to complete wins

val parallel: Eff[IO, AppError, (User, User)] =
  workflow.both(workflow)                          // Run in parallel

// Temporal operations with typed timeout
val withTimeout: Eff[IO, AppError, User] =
  workflow.timeout(5.seconds, Timeout)

// Guarantee cleanup on any outcome
val withCleanup: Eff[IO, AppError, User] =
  workflow.guaranteeCase {
    case Outcome.Succeeded(_) => Eff.liftF(IO.println("Success"))
    case Outcome.Errored(_)   => Eff.liftF(IO.println("Error"))
    case Outcome.Canceled()   => Eff.liftF(IO.println("Cancelled"))
  }

// Run the final computation
val result: IO[Either[AppError, User]] = concurrent.either
```

#### Syntax Extensions

Importing `boilerplate.effect.*` provides inline extensions:

| Extension                   | Result Type           |
|-----------------------------|-----------------------|
| `Either[E, A].eff[F]`       | `Eff[F, E, A]`        |
| `Either[E, A].effR[F, R]`   | `EffR[F, R, E, A]`    |
| `F[Either[E, A]].eff`       | `Eff[F, E, A]`        |
| `F[Either[E, A]].effR[R]`   | `EffR[F, R, E, A]`    |
| `Option[A].eff[F, E](err)`  | `Eff[F, E, A]`        |
| `F[Option[A]].eff[E](err)`  | `Eff[F, E, A]`        |
| `Try[A].eff[F, E](f)`       | `Eff[F, E, A]`        |
| `F[A].eff[E](f)`            | `Eff[F, E, A]`        |
| `Kleisli[Of[F,E],R,A].effR` | `EffR[F, R, E, A]`    |
| `Resource[F, A].eff[E]`     | `Resource[Of[F,E],A]` |
| `Ref[F, A].eff[E]`          | `Ref[Of[F, E], A]`    |
| `Deferred[F, A].eff[E]`     | `Deferred[Of[F,E],A]` |
| `Queue[F, A].eff[E]`        | `Queue[Of[F, E], A]`  |
| `Semaphore[F].eff[E]`       | `Semaphore[Of[F,E]]`  |

#### Fiber Join Extensions

When working with `Fiber[Eff.Of[F, E], Throwable, A]` (e.g., from `Supervisor.supervise`), extension methods provide
ergonomic join semantics:

| Extension               | Result Type    | On Cancellation        |
|-------------------------|----------------|------------------------|
| `fiber.joinNever`       | `Eff[F, E, A]` | Never completes        |
| `fiber.joinOrFail(err)` | `Eff[F, E, A]` | Fails with typed error |

```scala
Supervisor[IO](await = true).use { sup =>
  val liftedSup = sup.eff[AppError]
  for
    fiber  <- liftedSup.supervise(longRunningTask)
    result <- fiber.joinNever                       // or fiber.joinOrFail(AppError.Cancelled)
  yield result
}.either
```

---

## Licence

MIT
