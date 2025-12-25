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

Zero-cost typed-error effects layered atop Cats / Cats Effect. The module provides `Eff[F[_], E, A]`, an opaque wrapper over `F[Either[E, A]]`, and `EffR[F[_], R, E, A]`, a reader-style variant adding an environment channel. Both erase at runtime whilst maintaining compile-time awareness of error and environment types.

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

Full constructors with explicit type parameters remain available:
- **Pure conversions** (`from`): `Eff.from(Either)`, `Eff.from(Option, ifNone)`, `Eff.from(Try, ifFailure)`, `Eff.from(EitherT)`
- **Effectful conversions** (`lift`): `Eff.lift(F[Either])`, `Eff.lift(F[Option], ifNone)`
- **Value constructors**: `Eff.succeed`, `Eff.fail`, `Eff.unit`, `Eff.liftF`, `Eff.attempt`, `Eff.defer`

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

##### Example

```scala
val service: Eff[IO, String, Int] =
  for
    a <- Eff[IO].succeed(21)
    b <- Eff[IO].liftF(IO.pure(21))
  yield a + b

service.either  // IO[Either[String, Int]]
```

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
- **Pure conversions** (`from`): `EffR.from(Either)`, `EffR.from(Option, ifNone)`, `EffR.from(Try, ifFailure)`, `EffR.from(EitherT)`
- **Effectful conversions** (`lift`): `EffR.lift(Eff)`, `EffR.lift(F[Either])`, `EffR.lift(F[Option], ifNone)`
- **Value constructors**: `EffR.succeed`, `EffR.fail`, `EffR.unit`, `EffR.attempt`, `EffR.defer`
- **Environment**: `EffR.service`, `EffR.ask`, `EffR.wrap`, `EffR.fromContext`

##### Combinators

Mirrors `Eff` combinators, plus environment-specific operations:

| Category       | Methods                                          |
|----------------|--------------------------------------------------|
| Environment    | `provide`, `contramap`, `andThen`, `run`         |
| Recovery       | `catchAll`, `recover`, `recoverWith`, `onError`  |
| Folding        | `fold`, `foldF`, `redeem`, `redeemAll`           |
| Alternative    | `alt`                                            |
| Conversion     | `either`, `rethrow`, `absolve`, `kleisli`        |

##### Layer Composition

```scala
// Narrow environment via contramap
val narrow: EffR[IO, AppConfig, E, A] = prog.contramap[AppConfig](_.database)

// Chain readers: output of first becomes environment of second
val composed: EffR[IO, Int, E, String] = intToDouble.andThen(doubleToString)

// Effectful environment provision
val provided: Eff[IO, E, A] = program.provide(buildDatabase)
```

##### Kleisli Interop

`EffR` is representationally equivalent to `Kleisli[Eff.Of[F, E], R, A]`. For ecosystem familiarity:

```scala
import cats.data.Kleisli

type MyApp[A] = Kleisli[Eff.Of[IO, AppError], AppEnv, A]

val program: MyApp[User] =
  for
    env  <- Kleisli.ask[Eff.Of[IO, AppError], AppEnv]
    user <- Kleisli.liftF(Eff.succeed[IO, AppError, User](env.defaultUser))
  yield user

program.run(appEnv).either  // IO[Either[AppError, User]]
```

#### Syntax Extensions

Importing `boilerplate.effect.*` provides inline extensions for common conversions:

| Extension                  | Result Type        |
|----------------------------|--------------------|
| `Either[E, A].eff[F]`      | `Eff[F, E, A]`     |
| `Either[E, A].effR[F, R]`  | `EffR[F, R, E, A]` |
| `F[Either[E, A]].eff`      | `Eff[F, E, A]`     |
| `F[Either[E, A]].effR[R]`  | `EffR[F, R, E, A]` |
| `Option[A].eff[F, E](err)` | `Eff[F, E, A]`     |
| `Option[A].effR[F,R,E](e)` | `EffR[F, R, E, A]` |
| `F[Option[A]].eff[E](err)` | `Eff[F, E, A]`     |
| `F[Option[A]].effR[R,E](e)`| `EffR[F, R, E, A]` |
| `Try[A].eff[F, E](f)`      | `Eff[F, E, A]`     |
| `Try[A].effR[F, R, E](f)`  | `EffR[F, R, E, A]` |
| `F[A].eff[E](f)`           | `Eff[F, E, A]`     |
| `F[A].effR[R, E](f)`       | `EffR[F, R, E, A]` |

---

## Licence

MIT
