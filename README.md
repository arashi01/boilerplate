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

