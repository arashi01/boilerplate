/*
 * Copyright (c) 2025, 2026 Boilerplate contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package boilerplate.effect

import scala.compiletime.testing.typeCheckErrors
import scala.reflect.TypeTest

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IOError.*

// The wiring graph is a diamond: Config <- Db, Config <- Cache, (Db, Cache) <- Server. Config must
// be constructed exactly once however many nodes depend on it.
class Config(val url: String)
final class Db(val config: Config)
final class Cache(val config: Config)
final class Server(val db: Db, val cache: Cache)
final class Metrics()
final class PgConfig(url: String) extends Config(url)

// A kernel-scale graph: twelve services, three embedded diamonds (joins at K4, K9, K12), depth
// eight - the size and shape of a real application service kernel, which the four-node diamond
// above does not exercise.
final class K1
final class K2(val a: K1)
final class K3(val a: K1)
final class K4(val a: K2, val b: K3)
final class K5(val a: K1)
final class K6(val a: K4, val b: K5)
final class K7(val a: K6)
final class K8(val a: K6)
final class K9(val a: K7, val b: K8)
final class K10(val a: K9)
final class K11(val a: K9)
final class K12(val a: K10, val b: K11)

class ProviderSuite extends CatsEffectSuite:
  private def run[E <: Throwable, A](eff: Eff[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] = eff.either.absolve

  private def node[A](trace: Ref[IO, List[String]], label: String)(value: => A): EffResource[Nothing, A] =
    EffResource.make(trace.update(_ :+ s"acquire $label").map(_ => value))(_ => trace.update(_ :+ s"release $label"))

  private def providers(trace: Ref[IO, List[String]]) =
    (
      Provider(node(trace, "Config")(Config("u"))),
      Provider((c: Config) => node(trace, "Db")(Db(c))),
      Provider((c: Config) => node(trace, "Cache")(Cache(c))),
      Provider((d: Db, c: Cache) => node(trace, "Server")(Server(d, c)))
    )

  test("wire assembles the graph whatever order the providers are passed in"):
    for
      trace <- IO.ref(List.empty[String])
      (config, db, cache, server) = providers(trace)
      forwards <- Provider.wire[Server](config, db, cache, server).use(s => Eff.succeed(s.db.config.url)).absolve
      backwards <- Provider.wire[Server](server, cache, db, config).use(s => Eff.succeed(s.db.config.url)).absolve
    yield
      assertEquals(forwards, "u")
      assertEquals(backwards, "u")

  test("a service two nodes depend on is constructed once and shared"):
    for
      trace <- IO.ref(List.empty[String])
      (config, db, cache, server) = providers(trace)
      shared <- Provider.wire[Server](server, db, cache, config).use(s => Eff.succeed(s.db.config eq s.cache.config)).absolve
      acquisitions <- trace.get.map(_.count(_ == "acquire Config"))
    yield
      assert(shared, "the diamond's shared node was constructed twice")
      assertEquals(acquisitions, 1)

  test("acquisition follows the dependency order and release is its exact reverse"):
    for
      trace <- IO.ref(List.empty[String])
      (config, db, cache, server) = providers(trace)
      _ <- Provider.wire[Server](server, cache, db, config).use(_ => Eff.succeed(())).absolve
      seen <- trace.get
    yield
      val acquired = seen.filter(_.startsWith("acquire")).map(_.stripPrefix("acquire "))
      val released = seen.filter(_.startsWith("release")).map(_.stripPrefix("release "))
      // Order among independent nodes follows the argument order; what the graph fixes is that a
      // dependency precedes its dependents, and that teardown is the exact reverse.
      assertEquals(acquired.head, "Config")
      assertEquals(acquired.last, "Server")
      assertEquals(acquired.toSet, Set("Config", "Db", "Cache", "Server"))
      assertEquals(released, acquired.reverse)

  test("the wired error channel is the union of the providers' own"):
    val config: Provider[EmptyTuple, NotFound, Config] = Provider(EffResource.eval(Eff.succeed(Config("u"))): EffResource[NotFound, Config])
    val db: Provider[Tuple1[Config], IOError, Db] = Provider((c: Config) => EffResource.eval(Eff.succeed(Db(c))): EffResource[IOError, Db])
    val wired: EffResource[NotFound | IOError, Db] = Provider.wire[Db](config, db)
    run(wired.use(d => Eff.succeed(d.config.url))).map(assertEquals(_, Right("u")))

  test("a mid-graph acquisition failure propagates typed and releases the acquired prefix in reverse"):
    for
      trace <- IO.ref(List.empty[String])
      config = Provider(node(trace, "Config")(Config("u")))
      acquireDb: Eff[IOError, Db] = Eff.flatMap(trace.update(_ :+ "acquire Db"))(_ => Eff.fail(Closed))
      db = Provider((_: Config) => EffResource.make(acquireDb)(_ => trace.update(_ :+ "release Db")))
      outcome <- run(Provider.wire[Db](config, db).use(d => Eff.succeed(d.config.url)))
      seen <- trace.get
    yield
      assertEquals(outcome, Left(Closed))
      assertEquals(seen, List("acquire Config", "acquire Db", "release Config"))

  test("a provider with no dependencies wires on its own"):
    val only = Provider(EffResource.pure(Config("solo")))
    Provider.wire[Config](only).use(c => Eff.succeed(c.url)).absolve.map(assertEquals(_, "solo"))

  test("wire reports a dependency no provider supplies, naming who required it"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      val cache = Provider((c: Config) => EffResource.pure(Cache(c)))
      val server = Provider((d: Db, c: Cache) => EffResource.pure(Server(d, c)))
      Provider.wire[Server](server, db, cache)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("missing: no provider for Config"), message)
    assert(message.contains("required by:"), message)
    assert(message.contains("Db (argument 2)"), message)
    assert(message.contains("Cache (argument 3)"), message)

  test("wire notes a declared output that is a strict subtype of the missing requirement"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      val pg = Provider(EffResource.pure(PgConfig("u")))
      Provider.wire[Db](db, pg)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("missing: no provider for Config"), message)
    assert(message.contains("argument 2 provides PgConfig <: Config"), message)
    assert(message.contains("Provider[?, ?, Config]"), message)

  test("wire reports a target no provider supplies"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val config = Provider(EffResource.pure(Config("u")))
      Provider.wire[Server](config)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("missing: no provider for Server - the wire target"), message)

  test("wire reports a service provided twice, naming both arguments"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val config = Provider(EffResource.pure(Config("u")))
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      val other = Provider((c: Config) => EffResource.pure(Db(c)))
      Provider.wire[Db](config, db, other)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("duplicate: Db is provided by arguments 2 and 3"), message)

  test("wire reports a provider unreachable from the target"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val config = Provider(EffResource.pure(Config("u")))
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      val metrics = Provider(EffResource.pure(Metrics()))
      Provider.wire[Db](config, db, metrics)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("unused: argument 3 (provides Metrics) is not reachable from Db"), message)

  test("wire reports a provider whose dependencies are not concrete types"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val config = Provider(EffResource.pure(Config("u")))
      def wireIn[R <: Tuple](db: Provider[R, Nothing, Db]) = Provider.wire[Db](config, db)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("abstract: argument 2 has non-concrete dependencies"), message)

  test("wire reports a dependency cycle with its full path"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val config = Provider((d: Db) => EffResource.pure(Config("u")))
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      Provider.wire[Db](config, db)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("cycle:"), message)
    assert(message.contains("Db"), message)
    assert(message.contains("Config"), message)

  test("a three-service cycle is reported with every participant on the path"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val config = Provider((s: Server) => EffResource.pure(Config("u")))
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      val server = Provider((d: Db) => EffResource.pure(Server(d, Cache(Config("c")))))
      Provider.wire[Server](config, db, server)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("cycle:"), message)
    assert(message.contains("Config"), message)
    assert(message.contains("Db"), message)
    assert(message.contains("Server"), message)

  test("a twelve-service, three-diamond graph wires from shuffled arguments with each shared node built once"):
    for
      trace <- IO.ref(List.empty[String])
      k1 = Provider(node(trace, "K1")(K1()))
      k2 = Provider((a: K1) => node(trace, "K2")(K2(a)))
      k3 = Provider((a: K1) => node(trace, "K3")(K3(a)))
      k4 = Provider((a: K2, b: K3) => node(trace, "K4")(K4(a, b)))
      k5 = Provider((a: K1) => node(trace, "K5")(K5(a)))
      k6 = Provider((a: K4, b: K5) => node(trace, "K6")(K6(a, b)))
      k7 = Provider((a: K6) => node(trace, "K7")(K7(a)))
      k8 = Provider((a: K6) => node(trace, "K8")(K8(a)))
      k9 = Provider((a: K7, b: K8) => node(trace, "K9")(K9(a, b)))
      k10 = Provider((a: K9) => node(trace, "K10")(K10(a)))
      k11 = Provider((a: K9) => node(trace, "K11")(K11(a)))
      k12 = Provider((a: K10, b: K11) => node(trace, "K12")(K12(a, b)))
      shared <- Provider
                  .wire[K12](k7, k12, k3, k9, k1, k11, k5, k2, k10, k8, k6, k4)
                  .use(k => Eff.succeed((k.a.a eq k.b.a) && (k.a.a.a.a eq k.a.a.b.a) && (k.a.a.a.a.a.a.a eq k.a.a.a.a.b.a)))
                  .absolve
      seen <- trace.get
    yield
      assert(shared, "a diamond join saw two instances of its shared dependency")
      val acquired = seen.filter(_.startsWith("acquire")).map(_.stripPrefix("acquire "))
      val released = seen.filter(_.startsWith("release")).map(_.stripPrefix("release "))
      assertEquals(acquired.size, 12)
      assertEquals(acquired.distinct.size, 12)
      assertEquals(released, acquired.reverse)

  test("every finding of a malformed call is reported together, not one per compilation"):
    val errors = typeCheckErrors("""
      import boilerplate.effect.*
      val db = Provider((c: Config) => EffResource.pure(Db(c)))
      val other = Provider((c: Config) => EffResource.pure(Db(c)))
      val metrics = Provider(EffResource.pure(Metrics()))
      Provider.wire[Server](db, other, metrics)
    """)
    val message = errors.map(_.message).mkString("\n")
    assert(message.contains("Provider.wire[Server] failed:"), message)
    assert(message.contains("missing: no provider for Config"), message)
    assert(message.contains("missing: no provider for Server - the wire target"), message)
    assert(message.contains("duplicate: Db is provided by arguments 1 and 2"), message)
end ProviderSuite
