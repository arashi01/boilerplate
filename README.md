# Boilerplate

A collection of utilities and common patterns that tend to be repeated across Scala 3 projects.

## Modules

### nullable

Utilities for working with Scala 3's explicit nulls feature. When strict equality is enabled, inline null checks become
verbose without a `CanEqual` instance. These extensions provide a concise, type-safe way to handle nullable values.

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

Zero-cost typed-error effects layered on top of Cats / Cats Effect. The module exposes an opaque representation for `Eff[F[_], E, A] = F[Either[E, A]]` with inline constructors, plus an environment-aware variant `EffR[F[_], R, E, A] = R => Eff[F, E, A]`. Both erase at runtime yet keep the typechecker aware of the error and environment channels.

#### Type Aliases

Convenience aliases reduce verbosity for common error channel configurations:

| Alias            | Expansion                  | Description                     |
|------------------|----------------------------|---------------------------------|
| `UEff[F, A]`     | `Eff[F, Nothing, A]`       | Infallible effect               |
| `TEff[F, A]`     | `Eff[F, Throwable, A]`     | Throwable-errored effect        |
| `UEffR[F, R, A]` | `EffR[F, R, Nothing, A]`   | Infallible reader effect        |
| `TEffR[F, R, A]` | `EffR[F, R, Throwable, A]` | Throwable-errored reader effect |

#### `Eff`: Typed Error Channel

Partially-applied constructors allow ergonomic value creation with minimal type annotations:

```scala
import boilerplate.effect.*
import cats.effect.IO

// Ergonomic syntax — effect type inferred, error channel polymorphic
val ok  = Eff[IO].succeed(42)        // UEff[IO, Int]
val err = Eff[IO].fail("boom")       // Eff[IO, String, Nothing]
val either = Eff[IO].fromEither(Right(1))
val lifted = Eff[IO].liftF(IO.pure(42))
val u   = Eff[IO].unit               // UEff[IO, Unit]

// Full constructors remain available when explicit types are needed
Eff.succeed[IO, String, Int](42)
Eff.fail[IO, String, Int]("boom")
```

**Constructors:** `succeed`, `fail`, `fromEither`, `fromOption`, `fromTry`, `liftF`, `attempt`, `defer`, `unit`.

**Combinators:**

| Category           | Methods                                                                    |
|--------------------|----------------------------------------------------------------------------|
| Mapping            | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                 |
| Error handling     | `catchAll`, `leftFlatMap`, `redeem`, `fold`, `foldF`, `tapError`, `orElse` |
| Guards             | `ensure`, `ensureOr`                                                       |
| Variance           | `widen`, `widenError`, `assume`, `assumeError`                             |
| Interop            | `eitherT`                                                                  |

**Type classes:** via the partially-applied `Eff.Of[F, E]`, the module derives `Functor`, `Monad`, `MonadError`, and `MonadCancel` instances automatically from the underlying `F`. This makes `Eff` slot into any Cats / Cats Effect API (e.g. `Resource`, `IOApp`).

Example:

```scala
import boilerplate.effect.*
import cats.effect.IO

val service: Eff[IO, String, Int] =
  for
    a <- Eff[IO].succeed(21)
    b <- Eff[IO].liftF(IO.pure(21))
  yield a + b

service.value // IO[Either[String, Int]] = IO.pure(Right(42))
```

#### `EffR`: Reader-Style Environment

Adds an immutable environment parameter without leaving the typed-error world. Partially-applied constructors pin both effect and environment types:

```scala
import boilerplate.effect.*
import cats.effect.IO

type Config = String

// Ergonomic syntax — effect and environment types fixed
val prog = EffR[IO, Config].succeed(42)
val env  = EffR[IO, Config].service  // retrieves the Config
```

**Constructors:** `succeed`, `fail`, `fromEither`, `lift`, `service` (alias: `ask`), `fromOption`, `fromTry`, `attempt`, `defer`, `unit`, `wrap`, `fromContext`.

**Combinators:**

| Category            | Methods                                                                            |
|---------------------|------------------------------------------------------------------------------------|
| Environment         | `provide` (overloaded for layers), `contramap`, `andThen`                          |
| Mapping             | `map`, `flatMap`, `semiflatMap`, `subflatMap`, `transform`                         |
| Error handling      | `leftFlatMap`                                                                      |
| Guards              | `ensure`, `ensureOr`                                                               |
| Variance            | `widen`, `widenError`, `assume`, `assumeError`, `widenEnv`, `assumeEnv`            |
| Interop             | `kleisli`                                                                          |

**Type classes:** `Functor`, `Monad`, `MonadError`, `MonadCancel` instances delegate via `Kleisli`, so any Cats Effect API expecting those abstractions works out of the box.

Example:

```scala
import boilerplate.effect.*
import cats.effect.IO

trait Database:
  def findUser(id: Int): IO[Option[User]]

val fetchUser: EffR[IO, Database, String, User] =
  for
    db   <- EffR[IO, Database].service
    user <- EffR[IO, Database].lift(
              Eff.fromOption(db.findUser(42), "not found")
            )
  yield user

fetchUser.provide(prodDatabase).value // IO[Either[String, User]]
```

#### Layer Composition

`EffR` supports ZIO-style layer composition via `provide` (overloaded for layers), `contramap`, and `andThen`:

```scala
// Transform environment
val narrow: EffR[IO, AppConfig, E, A] = base.contramap[AppConfig](_.database)

// Chain readers: output of first becomes environment of second
val composed: EffR[IO, Int, E, String] = intToDouble.andThen(doubleToString)

// Effectful environment construction via provide overload
val provided: EffR[IO, Unit, E, A] = program.provide(buildDatabase)
```

#### Using Kleisli Directly

`EffR[F, R, E, A]` is representationally equivalent to `Kleisli[Eff.Of[F, E], R, A]`. If you prefer the standard Cats abstraction, you can use `Kleisli` directly with `Eff`:

```scala
import cats.data.Kleisli
import cats.effect.IO
import boilerplate.effect.*

// Define your program type as Kleisli over Eff
type MyApp[A] = Kleisli[Eff.Of[IO, AppError], AppEnv, A]

// Kleisli provides the same capabilities with different naming:
// - Kleisli.ask       ≈ EffR.service (or EffR.ask)
// - Kleisli.local     ≈ EffR.contramap
// - Kleisli.liftF     ≈ EffR.lift

val program: MyApp[User] =
  for
    env  <- Kleisli.ask[Eff.Of[IO, AppError], AppEnv]
    user <- Kleisli.liftF(Eff.succeed[IO, AppError, User](env.defaultUser))
  yield user

// Run by providing the environment
program.run(appEnv).value // IO[Either[AppError, User]]
```

All `MonadCancel`, `MonadError`, and `Monad` instances compose automatically. Choose `EffR` for ZIO-style ergonomics or `Kleisli` for ecosystem familiarity.

#### Syntax Extensions

Importing `boilerplate.effect.*` provides top-level extensions that convert common datatypes into `Eff`/`EffR` without extra ceremony:

| Extension                 | Result Type                  |
|---------------------------|------------------------------|
| `Either[E, A].eff`        | `Eff[F, E, A]`               |
| `Either[E, A].effR`       | `EffR[F, R, E, A]`           |
| `F[Either[E, A]].eff`     | `Eff[F, E, A]`               |
| `F[Either[E, A]].effR`    | `EffR[F, R, E, A]`           |
| `Option[A].eff(ifNone)`   | `Eff[F, E, A]`               |
| `Option[A].effR(ifNone)`  | `EffR[F, R, E, A]`           |
| `F[Option[A]].eff(ifNone)`| `Eff[F, E, A]`               |
| `F[Option[A]].effR(ifNone)`| `EffR[F, R, E, A]`          |
| `Try[A].eff(mapper)`      | `Eff[F, E, A]`               |
| `Try[A].effR(mapper)`     | `EffR[F, R, E, A]`           |
| `F[A].eff(mapper)`        | `Eff[F, E, A]` (captures throwables) |
| `F[A].effR(mapper)`       | `EffR[F, R, E, A]`           |

These helpers are inline, introducing no runtime penalty.

## Installation

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.github.arashi01" %% "boilerplate" % "<version>"
```

For cross-platform projects (Scala.js or Scala Native):

```scala
libraryDependencies += "io.github.arashi01" %%% "boilerplate" % "<version>"
```

## Licence

MIT
