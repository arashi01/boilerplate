# Boilerplate

Collection of utilities and common patterns useful across Scala 3 projects.

## Modules

### effect

Zero-cost typed-error effects layered atop cats / cats-effect.

Standard `MonadError[F, Throwable]` conflates recoverable domain errors with fatal defects, forcing defensive `recover`
blocks or losing type safety. `Eff[F, E, A]` provides an explicit, compile-time-tracked error channel `E` separate from
`Throwable`, enabling exhaustive pattern matching on failure cases whilst preserving full cats-effect integration.

**Core abstractions:**

- **`Eff[F, E, A]`** — opaque type equal to `F[Either[E, A]]` with zero runtime overhead
- **`EffR[F, R, E, A]`** — reader-style variant equal to `R => Eff[F, E, A]`

Both erase at runtime whilst maintaining compile-time awareness of error and environment types.

**Note on ZIO:** `Eff`/`EffR` are not replacements for ZIO. Eff/EffR is a minimal-cost wrapper over cats-effect—the environment
channel is a simple function argument with no injection or layer management. Its primary value is as a drop-in for
existing cats-effect codebases that want compile-time typed errors without switching ecosystems, whilst providing
cleaner syntax than manually threading `EitherT` or composing `Kleisli[EitherT[F, E, *], R, A]`.

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.syntax.all.*

// Domain errors are explicit in the type signature
sealed trait AppError
case class NotFound(id: String) extends AppError
case class InvalidInput(msg: String) extends AppError

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
  },
  user => s"Welcome ${user.name}"
)

// Recover from all errors with a fallback value (provided convenience method)
val fallback: UEff[IO, ValidUser] = workflow.valueOr(_ => defaultUser)

// Recover from specific errors via cats ApplicativeError syntax
val recovered: Eff[IO, AppError, ValidUser] =
  workflow.recover { case NotFound(_) => defaultUser }

// Transform the error channel via cats Bifunctor syntax
val mapped: Eff[IO, String, ValidUser] = workflow.leftMap(_.toString)
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
```

Full constructors:

| Category  | Methods                                                                         |
|-----------|---------------------------------------------------------------------------------|
| Pure      | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful | `lift(F[Either])`, `lift(F[Option], ifNone)`                                    |
| Suspended | `delay(=> Either)`, `defer(=> Eff)`                                             |
| Values    | `succeed`, `fail`, `unit`, `liftF`, `attempt`                                   |

##### Combinators

| Category    | Methods                                                         |
|-------------|-----------------------------------------------------------------|
| Mapping     | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`      |
| Composition | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap` |
| Recovery    | `valueOr`, `catchAll`                                           |
| Alternative | `alt`, `orElseSucceed`, `orElseFail`                            |
| Folding     | `fold`, `foldF`, `redeemAll`                                    |
| Observation | `tap`, `tapError`, `flatTapError`                               |
| Variance    | `widen`, `widenError`, `assume`, `assumeError`                  |
| Extraction  | `option`, `collectSome`, `collectRight`                         |
| Conversion  | `either`, `absolve`, `eitherT`                                  |
| Resource    | `bracket`, `bracketCase`, `timeout`                             |

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

`EffR.Of[F, R, E]` mirrors these instances, threading the environment through all operations.


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
| Pure        | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful   | `lift(Eff)`, `lift(F[Either])`, `lift(F[Option], ifNone)`                       |
| Suspended   | `delay(=> Either)`, `defer(=> EffR)`                                            |
| Values      | `succeed`, `fail`, `unit`, `attempt`                                            |
| Environment | `ask`, `wrap`, `fromContext`                                                    |

##### Combinators

Mirrors `Eff` combinators, plus environment-specific operations:

| Category    | Methods                                    |
|-------------|--------------------------------------------|
| Environment | `provide`, `run`, `contramap`, `andThen`   |
| Variance    | `widen`, `widenEnv`, `assume`, `assumeEnv` |
| Recovery    | `valueOr`, `catchAll`                      |
| Folding     | `fold`, `foldF`, `redeemAll`               |
| Alternative | `alt`, `orElseSucceed`, `orElseFail`       |
| Conversion  | `either`, `absolve`, `kleisli`             |

With `cats.syntax.all.*` in scope, additional methods from cats typeclasses are available:

| Typeclass        | Methods                                   |
|------------------|-------------------------------------------|
| `Bifunctor`      | `bimap`, `leftMap`                        |
| `ApplicativeError` | `recover`, `recoverWith`, `onError`, `adaptError`, `redeem` |
| `MonadError`     | `ensure`, `ensureOr`, `rethrow`           |

#### Cats-Effect Primitive Interop

Transform cats-effect primitives to operate in the `Eff` context via named lift methods or `.eff[E]` extension syntax:

```scala
import boilerplate.effect.*
import cats.effect.IO
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

Lifted primitives compose naturally with `Eff` for-comprehensions:

```scala
val workflow: Eff[IO, AppError, Unit] = for
  ref   <- Eff.liftF(Ref.of[IO, Int](0)).map(_.eff[AppError])
  _     <- ref.update(_ + 1)
  value <- ref.get
  _     <- if value < 0 then Eff.fail(AppError.InvalidState) else Eff.unit
yield ()
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

### nullable

Utilities for working with Scala 3's explicit nulls feature. When strict equality is enabled, inline null checks become
verbose without a `CanEqual` instance. These extensions provide a concise, type-safe way to handle nullable values.

#### Dependency Coordinates

```scala
libraryDependencies += "io.github.arashi01" %% /* or `%%%` */ "boilerplate" % "<version>"
```

#### Extensions on `A | Null`

| Method              | Description                                                                          |
|---------------------|--------------------------------------------------------------------------------------|
| `option`            | Converts to `Option[A]` — `Some(value)` if non-null, `None` otherwise                |
| `either(leftError)` | Converts to `Either[E, A]` — `Right(value)` if non-null, `Left(leftError)` otherwise |
| `mapOpt(f)`         | Maps through `f` if non-null, returning `Option[B]`                                  |
| `flatMapOpt(f)`     | FlatMaps through `f: A => Option[B]` if non-null                                     |

#### Extensions on `Option[A | Null]`

Useful when combining `Option`-returning operations with nullable values from Java interop.

| Method           | Description                                                         |
|------------------|---------------------------------------------------------------------|
| `flattenNull`    | Converts `Some(null)` to `None`                                     |
| `mapNull(f)`     | Maps through `f`, treating inner null as `None`                     |
| `flatMapNull(f)` | FlatMaps through `f: A => Option[B]`, treating inner null as `None` |

#### Extensions on `Either[E, A | Null]`

| Method                      | Description                                                            |
|-----------------------------|------------------------------------------------------------------------|
| `flattenNull(leftError)`    | Converts `Right(null)` to `Left(leftError)`                            |
| `mapNull(leftError)(f)`     | Maps through `f`, treating inner null as `Left`                        |
| `flatMapNull(leftError)(f)` | FlatMaps through `f: A => Either[E, B]`, treating inner null as `Left` |

#### Usage

```scala
import boilerplate.nullable.*

// Basic null handling
val value: String | Null = possiblyNullValue()
value.option // Option[String]
value.either("was null") // Either[String, String]

// Handling Option[A | Null] from Java interop
val opt: Option[String | Null] = Some(javaMethod())
opt.flattenNull // Option[String] — Some(null) becomes None

// Chaining with Either
val result: Either[String, String | Null] = Right(javaMethod())
result.flattenNull("null value") // Either[String, String]
```

---

## Licence

MIT
