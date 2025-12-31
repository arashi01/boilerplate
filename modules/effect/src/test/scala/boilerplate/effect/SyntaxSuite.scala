/*
 * Copyright (c) 2025 Boilerplate contributors.
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

import scala.util.Try

import cats.*
import cats.effect.*
import cats.syntax.all.*
import munit.CatsEffectSuite

class SyntaxSuite extends CatsEffectSuite:
  import boilerplate.effect.*

  private def runEff[E, A](eff: Eff[IO, E, A]): IO[Either[E, A]] = eff.either
  private def runEffR[R, E, A](eff: EffR[IO, R, E, A], env: R): IO[Either[E, A]] = eff.run(env).either

  test("Either.eff mirrors Eff.from"):
    val either: Either[String, Int] = Right(42)
    runEff(either.eff[IO]).map(result => assertEquals(result, Right(42)))

  test("Either.effR mirrors EffR.from"):
    val either: Either[String, Int] = Left("boom")
    runEffR(either.effR[IO, Unit], ()).map(result => assertEquals(result, Left("boom")))

  test("Option.effR injects custom error"):
    val none: Option[Int] = None
    runEffR(none.effR[IO, Unit, String]("missing"), ()).map(result => assertEquals(result, Left("missing")))

  test("F[Either].eff preserves structure"):
    val fea = IO.pure[Either[String, Int]](Right(7))
    runEff(fea.eff).map(result => assertEquals(result, Right(7)))

  test("Eff.from with Id is unambiguous"):
    val value: Eff[Id, String, Int] = Eff.from[Id, String, Int](Right(9))
    val failure: Eff[Id, String, Int] = Eff.from[Id, String, Int](Left("err"))
    assertEquals(value.either, Right(9))
    assertEquals(failure.either, Left("err"))

  test("Eff.from(Option) with Id lifts missing values"):
    val none: Option[Int] = None
    val some: Option[Int] = Some(3)
    val missing: Eff[Id, String, Int] = Eff.from[Id, String, Int](none, "missing")
    val present: Eff[Id, String, Int] = Eff.from[Id, String, Int](some, "missing")
    assertEquals(missing.either, Left("missing"))
    assertEquals(present.either, Right(3))

  test("F[Option].effR lifts missing values"):
    val fo = IO.pure(Option.empty[Int])
    runEffR(fo.effR[Unit, String]("missing"), ()).map(result => assertEquals(result, Left("missing")))

  test("Try.eff translates failure"):
    val boom = new RuntimeException("boom")
    runEff(Try(throw boom).eff[IO, String](_.getMessage)).map(result => assertEquals(result, Left("boom"))) // scalafix:ok DisableSyntax.throw

  test("Try.eff converts success"):
    runEff(Try(42).eff[IO, String](_.getMessage)).map(result => assertEquals(result, Right(42)))

  test("F[A].eff captures throwable failures"):
    val failing = IO.raiseError[Int](new RuntimeException("boom"))
    runEff(failing.eff[String](_.getMessage)).map(result => assertEquals(result, Left("boom")))

  test("F[A].eff passes through success"):
    val success = IO.pure(42)
    runEff(success.eff[String](_.getMessage)).map(result => assertEquals(result, Right(42)))

  test("F[A].effR captures throwable failures with environment"):
    val failing = IO.raiseError[Int](new RuntimeException("boom"))
    runEffR(failing.effR[Unit, String](_.getMessage), ()).map(result => assertEquals(result, Left("boom")))

  test("Option.eff converts present values"):
    val some: Option[Int] = Some(42)
    runEff(some.eff[IO, String]("missing")).map(result => assertEquals(result, Right(42)))

  test("F[Either].effR preserves structure"):
    val fea = IO.pure[Either[String, Int]](Left("boom"))
    runEffR(fea.effR[Unit], ()).map(result => assertEquals(result, Left("boom")))

  test("F[Option].eff converts present values"):
    val fo = IO.pure(Some(42))
    runEff(fo.eff[String]("missing")).map(result => assertEquals(result, Right(42)))

  test("F[Option].eff converts missing values to error"):
    val fo = IO.pure(Option.empty[Int])
    runEff(fo.eff[String]("missing")).map(result => assertEquals(result, Left("missing")))

  test("Try.effR translates failure with environment"):
    val boom = new RuntimeException("boom")
    runEffR(Try(throw boom).effR[IO, Unit, String](_.getMessage), ()).map(result => assertEquals(result, Left("boom"))) // scalafix:ok DisableSyntax.throw

  test("Try.effR converts success with environment"):
    runEffR(Try(42).effR[IO, Unit, String](_.getMessage), ()).map(result => assertEquals(result, Right(42)))

  // ===========================================================================
  // Fiber Join Extensions
  // ===========================================================================

  test("Fiber.joinNever returns value on success"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinNever.either
    yield assertEquals(result, Right(42))

  test("Fiber.joinNever propagates typed error"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinNever.either
    yield assertEquals(result, Left("boom"))

  test("Fiber.joinOrFail returns value on success"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinOrFail("canceled").either
    yield assertEquals(result, Right(42))

  test("Fiber.joinOrFail propagates typed error"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")
    for
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      result <- liftedFiber.joinOrFail("canceled").either
    yield assertEquals(result, Left("boom"))

  test("Fiber.joinOrFail returns error on cancellation"):
    for
      deferred <- Deferred[IO, Unit]
      eff: Eff[IO, String, Int] = Eff.liftF(deferred.get *> IO.pure(42))
      fiber <- GenSpawn[IO].start(eff.either)
      liftedFiber = Eff.fiber[IO, String, Int](fiber, Functor[IO])
      _ <- fiber.cancel
      result <- liftedFiber.joinOrFail("was canceled").either
    yield assertEquals(result, Left("was canceled"))
end SyntaxSuite
