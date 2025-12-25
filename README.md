# Boilerplate

A collection of utilities and common patterns that tend to be repeated across Scala 3 projects.

## Modules

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

### effect

Zero-cost typed-error effects layered atop Cats / Cats Effect. Standard `MonadError[F, Throwable]` conflates recoverable domain errors with fatal defects, forcing defensive `recover` blocks or losing type safety. `Eff[F[_], E, A]` provides an explicit, compile-time-tracked error channel `E` separate from `Throwable`, enabling exhaustive pattern matching on failure cases whilst preserving full Cats Effect integration.

The module provides:
- **`Eff[F, E, A]`** — an opaque wrapper over `F[Either[E, A]]` with zero runtime overhead
- **`EffR[F, R, E, A]`** — a reader-style variant adding an environment channel

Both erase at runtime whilst maintaining compile-time awareness of error and environment types.

```scala
import boilerplate.effect.*
import cats.effect.IO

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

// Or recover specific errors whilst preserving others
val recovered: Eff[IO, InvalidInput, ValidUser] =
  workflow.recover { case NotFound(_) => defaultUser }
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

Full constructors with explicit type parameters:

| Category   | Methods                                                                  |
|------------|--------------------------------------------------------------------------|
| Pure       | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful  | `lift(F[Either])`, `lift(F[Option], ifNone)`                             |
| Values     | `succeed`, `fail`, `unit`, `liftF`, `attempt`, `defer`                   |

##### Combinators

| Category       | Methods                                                                           |
|----------------|-----------------------------------------------------------------------------------|
| Mapping        | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `bimap`, `mapError`, `transform`   |
| Composition    | `*>`, `<*`, `productR`, `productL`, `product`, `void`, `as`, `flatTap`            |
| Recovery       | `recover` (total/partial), `catchAll`, `recoverWith`, `onError`, `adaptError`     |
| Folding        | `fold`, `foldF`, `redeem`, `redeemAll`                                            |
| Alternative    | `alt`                                                                             |
| Guards         | `ensure`, `ensureOr`                                                              |
| Observation    | `tapError`                                                                        |
| Variance       | `widen`, `widenError`, `assume`, `assumeError`                                    |
| Conversion     | `either`, `rethrow`, `absolve`, `eitherT`                                         |

##### Type Class Instances

`Eff.Of[F, E]` (a type lambda `[A] =>> Eff[F, E, A]`) derives `Functor`, `Monad`, `MonadError[_, E]`, and `MonadCancel` from the underlying `F`. This enables seamless integration with Cats Effect APIs such as `Resource` and `IOApp`.

#### `EffR[F, R, E, A]`

Adds an immutable environment channel, representationally equivalent to `R => Eff[F, E, A]`.

##### Constructors

```scala
type Config = String

EffR[IO, Config].succeed(42)   // UEffR[IO, Config, Int]
EffR[IO, Config].fail("err")   // EffR[IO, Config, String, Nothing]
EffR[IO, Config].service       // EffR[IO, Config, Nothing, Config] — retrieves environment
EffR[IO, Config].ask           // alias for service
```

Full constructors:

| Category    | Methods                                                                  |
|-------------|--------------------------------------------------------------------------|
| Pure        | `from(Either)`, `from(Option, ifNone)`, `from(Try, ifFailure)`, `from(EitherT)` |
| Effectful   | `lift(Eff)`, `lift(F[Either])`, `lift(F[Option], ifNone)`                |
| Values      | `succeed`, `fail`, `unit`, `attempt`, `defer`                            |
| Environment | `service`, `ask`, `wrap`, `fromContext`                                  |

##### Combinators

Mirrors `Eff` combinators, plus environment-specific operations:

| Category       | Methods                                          |
|----------------|--------------------------------------------------|
| Environment    | `provide`, `contramap`, `andThen`, `run`         |
| Recovery       | `catchAll`, `recover`, `recoverWith`, `onError`  |
| Folding        | `fold`, `foldF`, `redeem`, `redeemAll`           |
| Alternative    | `alt`                                            |
| Conversion     | `either`, `rethrow`, `absolve`, `kleisli`        |

#### Cats-Effect Primitive Interop

Transform cats-effect primitives to operate in the `Eff` context via `Eff.lift` overloads or extension methods:

```scala
import boilerplate.effect.*
import cats.effect.IO
import cats.effect.kernel.{Ref, Resource, Deferred}
import cats.effect.std.{Queue, Semaphore}

// Companion object methods
Eff.lift[IO, MyError, A](resource)   // Resource[Eff.Of[IO, MyError], A]
Eff.lift[IO, MyError, A](ref)        // Ref[Eff.Of[IO, MyError], A]
Eff.lift[IO, MyError, A](deferred)   // Deferred[Eff.Of[IO, MyError], A]
Eff.lift[IO, MyError, A](queue)      // Queue[Eff.Of[IO, MyError], A]
Eff.lift[IO, MyError](semaphore)     // Semaphore[Eff.Of[IO, MyError]]

// Extension syntax (equivalent)
resource.lift[MyError]
ref.lift[MyError]
deferred.lift[MyError]
queue.lift[MyError]
semaphore.lift[MyError]

// Natural transformation for custom mapK usage
val fk: IO ~> Eff.Of[IO, MyError] = Eff.functionK[IO, MyError]
```

| Primitive   | Lifted Type                          | Constraints                    |
|-------------|--------------------------------------|--------------------------------|
| `Resource`  | `Resource[Eff.Of[F, E], A]`          | `MonadCancel[F, Throwable]`    |
| `Ref`       | `Ref[Eff.Of[F, E], A]`               | `Functor[F]`                   |
| `Deferred`  | `Deferred[Eff.Of[F, E], A]`          | `Functor[F]`                   |
| `Queue`     | `Queue[Eff.Of[F, E], A]`             | `Functor[F]`                   |
| `Semaphore` | `Semaphore[Eff.Of[F, E]]`            | `MonadCancel[F, Throwable]`    |

Lifted primitives compose naturally with `Eff` for-comprehensions and preserve typed error semantics:

```scala
val workflow: Eff[IO, AppError, Unit] = for
  ref   <- Eff.liftF(Ref.of[IO, Int](0)).map(_.lift[AppError])
  _     <- ref.update(_ + 1)
  value <- ref.get
  _     <- if value < 0 then Eff.fail(AppError.InvalidState) else Eff.unit
yield ()
```

#### Syntax Extensions

Importing `boilerplate.effect.*` provides inline extensions:

| Extension                  | Result Type        |
|----------------------------|--------------------|
| `Either[E, A].eff[F]`      | `Eff[F, E, A]`     |
| `Either[E, A].effR[F, R]`  | `EffR[F, R, E, A]` |
| `F[Either[E, A]].eff`      | `Eff[F, E, A]`     |
| `F[Either[E, A]].effR[R]`  | `EffR[F, R, E, A]` |
| `Option[A].eff[F, E](err)` | `Eff[F, E, A]`     |
| `Try[A].eff[F, E](f)`      | `Eff[F, E, A]`     |
| `F[A].eff[E](f)`           | `Eff[F, E, A]`     |

---

## Licence

MIT
