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

import scala.concurrent.duration.*
import scala.reflect.TypeTest

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import munit.CatsEffectSuite

import boilerplate.effect.AppError.*
import boilerplate.effect.IOError.*

class EffResourceSuite extends CatsEffectSuite:
  private def run[E <: Throwable, A](eff: Eff[E, A])(using TypeTest[Throwable, E]): IO[Either[E, A]] = eff.either.absolve

  private def traced(trace: Ref[IO, List[String]], label: String): EffResource[Nothing, String] =
    EffResource.make(trace.update(_ :+ s"acquire $label").map(_ => label))(_ => trace.update(_ :+ s"release $label"))

  test("a raw cats-effect Resource is an EffResource by subtyping, and widens covariantly"):
    val raw: EffResource[Nothing, Int] = Resource.pure[IO, Int](1)
    val widened: EffResource[AppError, Int] = raw
    run(widened.use(n => Eff.succeed(n + 1))).map(assertEquals(_, Right(2)))

  test("eval holds the result of an effect with no finaliser"):
    run(EffResource.eval(Eff.succeed(7)).use(n => Eff.succeed(n))).map(assertEquals(_, Right(7)))

  test("eval propagates a typed acquisition failure"):
    run(EffResource.eval(Eff.fail(Closed)).use(n => Eff.succeed(n))).map(assertEquals(_, Left(Closed)))

  test("pure and unit hold a value with no finaliser"):
    for
      p <- run(EffResource.pure(3).use(n => Eff.succeed(n)))
      u <- run(EffResource.unit.use(_ => Eff.succeed(())))
    yield
      assertEquals(p, Right(3))
      assertEquals(u, Right(()))

  test("make releases after a successful use, a typed failure, and a defect alike"):
    for
      trace <- IO.ref(List.empty[String])
      _ <- run(traced(trace, "a").use(_ => Eff.succeed(())))
      _ <- run(traced(trace, "b").use(_ => Eff.fail(Closed)))
      _ <- traced(trace, "c").use(_ => IO.raiseError[Unit](RuntimeException("boom"))).absolve.attempt
      seen <- trace.get
    yield assertEquals(seen, List("acquire a", "release a", "acquire b", "release b", "acquire c", "release c"))

  test("make propagates a typed acquisition failure and never releases"):
    for
      released <- IO.ref(false)
      outcome <- run(EffResource.make(Eff.fail(Closed))(_ => released.set(true)).use(_ => Eff.succeed(())))
      wasReleased <- released.get
    yield
      assertEquals(outcome, Left(Closed))
      assert(!wasReleased)

  test("makeFull acquires uncancelably outside the polled region"):
    for
      trace <- IO.ref(List.empty[String])
      resource = EffResource.makeFull[IOError, String](poll => trace.update(_ :+ "acquire").flatMap(_ => poll(IO.pure("held"))))(_ =>
                   trace.update(_ :+ "release")
                 )
      outcome <- run(resource.use(v => Eff.succeed(v)))
      seen <- trace.get
    yield
      assertEquals(outcome, Right("held"))
      assertEquals(seen, List("acquire", "release"))

  test("use_ acquires and releases without exposing the value"):
    for
      trace <- IO.ref(List.empty[String])
      _ <- traced(trace, "a").use_.absolve
      seen <- trace.get
    yield assertEquals(seen, List("acquire a", "release a"))

  test("surround runs the effect with the resource held"):
    for
      trace <- IO.ref(List.empty[String])
      result <- run(traced(trace, "a").surround(trace.update(_ :+ "body").map(_ => 42)))
      seen <- trace.get
    yield
      assertEquals(result, Right(42))
      assertEquals(seen, List("acquire a", "body", "release a"))

  test("both yields the pair and holds both until the scope exits"):
    // `both` allocates the two concurrently, so the acquisition interleaving is not fixed; what is
    // guaranteed is that neither is released until the body has had both.
    for
      trace <- IO.ref(List.empty[String])
      result <- run(traced(trace, "a").both(traced(trace, "b")).use(pair => Eff.succeed(pair)))
      seen <- trace.get
    yield
      assertEquals(result, Right(("a", "b")))
      assertEquals(seen.take(2).toSet, Set("acquire a", "acquire b"))
      assertEquals(seen.drop(2).toSet, Set("release a", "release b"))

  test("onFinalize runs its finaliser after the resource's own"):
    for
      trace <- IO.ref(List.empty[String])
      _ <- run(traced(trace, "a").onFinalize(trace.update(_ :+ "extra")).use(_ => Eff.succeed(())))
      seen <- trace.get
    yield assertEquals(seen, List("acquire a", "release a", "extra"))

  test("evalMap transforms the acquired value and widens the error channel"):
    val resource: EffResource[Nothing, Int] = EffResource.pure(2)
    val mapped: EffResource[IOError, Int] = resource.evalMap(n => Eff.succeed(n * 3))
    for
      ok <- run(mapped.use(n => Eff.succeed(n)))
      ko <- run(resource.evalMap(_ => Eff.fail(Closed)).use(n => Eff.succeed(n)))
    yield
      assertEquals(ok, Right(6))
      assertEquals(ko, Left(Closed))

  test("evalTap observes the acquired value, keeps it, and releases when the observer fails"):
    for
      trace <- IO.ref(List.empty[String])
      ok <- run(traced(trace, "a").evalTap(v => trace.update(_ :+ s"saw $v")).use(v => Eff.succeed(v)))
      ko <- run(traced(trace, "b").evalTap(_ => Eff.fail(Closed)).use(v => Eff.succeed(v)))
      seen <- trace.get
    yield
      assertEquals(ok, Right("a"))
      assertEquals(ko, Left(Closed))
      assertEquals(seen, List("acquire a", "saw a", "release a", "acquire b", "release b"))

  test("map transforms the acquired value without touching the lifecycle"):
    run(EffResource.pure(4).map(_ * 5).use(n => Eff.succeed(n))).map(assertEquals(_, Right(20)))

  test("flatMap sequences resources, infers the error union, and releases in reverse order"):
    val first: EffResource[NotFound, Int] = EffResource.eval(Eff.succeed(1))
    val second: EffResource[Invalid, Int] = EffResource.eval(Eff.succeed(2))
    val composed: EffResource[NotFound | Invalid, Int] = first.flatMap(a => second.map(b => a + b))
    for
      trace <- IO.ref(List.empty[String])
      sum <- run(composed.use(n => Eff.succeed(n)))
      _ <- run(traced(trace, "outer").flatMap(_ => traced(trace, "inner")).use(_ => Eff.succeed(())))
      seen <- trace.get
    yield
      assertEquals(sum, Right(3))
      assertEquals(seen, List("acquire outer", "acquire inner", "release inner", "release outer"))

  test("a mid-graph acquisition failure propagates typed and releases the acquired prefix"):
    for
      trace <- IO.ref(List.empty[String])
      failing = traced(trace, "a").flatMap(_ => EffResource.make(Eff.fail(Closed))(_ => trace.update(_ :+ "release b")))
      outcome <- run(failing.use(_ => Eff.succeed(())))
      seen <- trace.get
    yield
      assertEquals(outcome, Left(Closed))
      assertEquals(seen, List("acquire a", "release a"))

  test("use releases when the body is cancelled"):
    for
      trace <- IO.ref(List.empty[String])
      started <- IO.deferred[Unit]
      fiber <- traced(trace, "a").use(_ => started.complete(()).flatMap(_ => IO.never[Unit])).absolve.start
      _ <- started.get
      _ <- fiber.cancel
      seen <- trace.get
    yield assertEquals(seen, List("acquire a", "release a"))

  test("absolve returns the underlying cats-effect Resource"):
    val resource: EffResource[IOError, Int] = EffResource.eval(Eff.fail(Closed))
    val raw: Resource[IO, Int] = resource.absolve
    raw.use(IO.pure).attempt.map(r => assertEquals(r.left.toOption, Some(Closed)))

  test("the transferred Async instance drives cats combinators, not just summons"):
    import cats.syntax.traverse.*
    val gathered: EffResource.Of[IOError][List[Int]] =
      List(1, 2, 3).traverse(n => EffResource.pure(n): EffResource.Of[IOError][Int])
    gathered.use(ns => Eff.succeed(ns.sum)).absolve.map(assertEquals(_, 6))

  test("retry re-acquires per policy, runs the consumer once, and releases once"):
    for
      attempts <- IO.ref(0)
      released <- IO.ref(0)
      used <- IO.ref(0)
      acquire: Eff[IOError, Int] =
        Eff.flatMap(attempts.updateAndGet(_ + 1))(n => if n < 3 then Eff.fail(Closed) else Eff.succeed(n))
      retried = EffResource.retry(
                  EffResource.make(acquire)(_ => released.update(_ + 1)),
                  RetryPolicy.constant(1.milli).withMaxAttempts(5)
                )
      out <- run(retried.use(n => Eff.flatMap(used.update(_ + 1))(_ => Eff.succeed(n))))
      a <- attempts.get
      r <- released.get
      u <- used.get
    yield
      assertEquals(out, Right(3))
      assertEquals(a, 3)
      assertEquals(r, 1)
      assertEquals(u, 1)

  test("retry releases each failed attempt's acquired prefix and exhausts to the typed error"):
    for
      trace <- IO.ref(List.empty[String])
      good = EffResource.make(trace.update(_ :+ "acquire a").map(_ => "a"))(_ => trace.update(_ :+ "release a"))
      bad: EffResource[IOError, String] =
        EffResource.make(Eff.flatMap(trace.update(_ :+ "attempt b"))(_ => Eff.fail(Closed)))(_ => trace.update(_ :+ "release b"))
      out <- run(EffResource.retry(good.flatMap(_ => bad), RetryPolicy.constant(1.milli).withMaxAttempts(3)).use(Eff.succeed))
      seen <- trace.get
    yield
      assertEquals(out, Left(Closed))
      assertEquals(seen, List.fill(3)(List("acquire a", "attempt b", "release a")).flatten)

  test("retry honours retryOn and the hook observes acquisition attempts"):
    for
      rejectCount <- IO.ref(0)
      rejected <- run(
                    EffResource
                      .retry(
                        EffResource.eval(Eff.flatMap(rejectCount.update(_ + 1))(_ => Eff.fail[IOError](Closed))),
                        RetryPolicy.constant(1.milli).withMaxAttempts(4),
                        (_: IOError) => false
                      )
                      .use(Eff.succeed)
                  )
      n <- rejectCount.get
      seen <- IO.ref(List.empty[Int])
      hook = (attempt: Int, _: IOError, _: FiniteDuration) => seen.update(_ :+ attempt)
      _ <- run(
             EffResource
               .retry(
                 EffResource.eval(Eff.fail[IOError](Closed): Eff[IOError, Int]),
                 RetryPolicy.constant(1.milli).withMaxAttempts(3),
                 hook
               )
               .use(Eff.succeed)
           )
      observed <- seen.get
    yield
      assertEquals(rejected, Left(Closed))
      assertEquals(n, 1)
      assertEquals(observed, List(1, 2))
end EffResourceSuite
