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

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.syntax.all.*
import munit.CatsEffectSuite

/** Test suite verifying correct method resolution when cats/cats-effect syntax is in scope.
  *
  * This suite does NOT test functionality - it tests that the correct method is selected when
  * there's potential ambiguity between our Eff methods and cats/cats-effect methods.
  *
  * Test naming convention:
  *   - "[method] on Eff selects Eff extension" - our method wins
  *   - "[method] on Eff selects cats [Typeclass]" - cats wins (acceptable if semantics match)
  *   - "[method] on Eff is unique to boilerplate-effect" - no collision possible
  *   - "[method] on IO selects cats when Eff syntax in scope" - cats IO methods unaffected
  *
  * Each test includes a CONTROL call via explicit `Eff.method(eff)(...)` syntax to verify our
  * implementation is accessible regardless of extension resolution.
  */
class OverloadDisambiguationSuite extends CatsEffectSuite:

  // ===========================================================================
  // FUNCTOR OPERATIONS - Eff extensions selected (no cats collision on Eff type)
  // ===========================================================================

  test("map on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[IO, String, Int] = Eff.succeed(21)

    val result = eff.map(_ * 2)
    val control = Eff.map(eff)(_ * 2)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("void on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)

    val result: Eff[IO, String, Unit] = eff.void
    val control: Eff[IO, String, Unit] = Eff.void(eff)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(()))
      assertEquals(c, Right(()))

  test("as on Eff selects Eff extension over Functor syntax"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)

    val result = eff.as("done")
    val control = Eff.as(eff)("done")

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("done"))
      assertEquals(c, Right("done"))

  // ===========================================================================
  // FLATMAP OPERATIONS - Eff extensions selected
  // ===========================================================================

  test("flatMap on Eff selects Eff extension over FlatMap syntax"):
    val eff: Eff[IO, String, Int] = Eff.succeed(21)

    val result = eff.flatMap(n => Eff.succeed(n * 2))
    val control = Eff.flatMap(eff)(n => Eff.succeed(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("product on Eff selects Eff extension over Apply syntax"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, String] = Eff.succeed("two")

    val result = left.product(right)
    val control = Eff.product(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right((1, "two")))
      assertEquals(c, Right((1, "two")))

  test("productL (<*) on Eff selects Eff extension over Apply syntax"):
    val left: Eff[IO, String, Int] = Eff.succeed(42)
    val right: Eff[IO, String, String] = Eff.succeed("ignored")

    val result = left <* right
    val control = Eff.productL(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("productR (*>) on Eff selects Eff extension over Apply syntax"):
    val left: Eff[IO, String, Int] = Eff.succeed(1)
    val right: Eff[IO, String, String] = Eff.succeed("kept")

    val result = left *> right
    val control = Eff.productR(left)(right)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("kept"))
      assertEquals(c, Right("kept"))

  test("flatTap on Eff selects Eff extension over FlatMap syntax"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.succeed(42)

    val result = eff.flatTap(n => Eff.liftF(IO { observed = Some(n) }))
    val control = Eff.flatTap(eff)(n => Eff.liftF(IO { observed = Some(n) }))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))
      assertEquals(observed, Some(42))

  // ===========================================================================
  // BIFUNCTOR - Eff extension selected
  // ===========================================================================

  test("bimap on Eff selects Eff extension over Bifunctor syntax"):
    val eff: Eff[IO, String, Int] = Eff.fail("err")

    val result = eff.bimap(_.toUpperCase, _ * 2)
    val control = Eff.bimap(eff)(_.toUpperCase, _ * 2)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("ERR"))
      assertEquals(c, Left("ERR"))

  // ===========================================================================
  // APPLICATIVEERROR - cats ApplicativeErrorOps wins for PartialFunction overloads
  // ===========================================================================

  test("recover on Eff selects cats ApplicativeError (PartialFunction) - matching error"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    // cats' recover from ApplicativeErrorOps is selected (uses PartialFunction)
    // Our Eff.recover also uses PartialFunction with compatible semantics
    val result: Eff[IO, String, Int] = eff.recover { case "boom" => 42 }
    val control: Eff[IO, String, Int] = Eff.recover(eff)[Int] { case "boom" => 42 }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("recover on Eff selects cats ApplicativeError (PartialFunction) - non-matching error"):
    val eff: Eff[IO, String, Int] = Eff.fail("other")

    val result: Eff[IO, String, Int] = eff.recover { case "boom" => 42 }
    val control: Eff[IO, String, Int] = Eff.recover(eff)[Int] { case "boom" => 42 }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("other"))
      assertEquals(c, Left("other"))

  test("recoverWith on Eff selects cats ApplicativeError (PartialFunction)"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    val result: Eff[IO, String, Int] = eff.recoverWith { case "boom" => Eff.succeed(42) }
    val control: Eff[IO, String, Int] = Eff.recoverWith(eff) { case "boom" => Eff.succeed(42) }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("onError on Eff selects cats ApplicativeError (PartialFunction)"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    val result = eff.onError { case e => Eff.liftF(IO { observed = Some(e) }) }
    val control = Eff.onError(eff) { case e => Eff.liftF(IO { observed = Some(e) }) }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("boom"))
      assertEquals(c, Left("boom"))
      assertEquals(observed, Some("boom"))

  test("adaptError on Eff selects cats ApplicativeError (PartialFunction)"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    val result = eff.adaptError { case "boom" => "BOOM" }
    val control = Eff.adaptError(eff) { case "boom" => "BOOM" }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("BOOM"))
      assertEquals(c, Left("BOOM"))

  // ===========================================================================
  // MONADERROR - cats MonadError wins for redeem
  // ===========================================================================

  test("redeem on Eff selects cats MonadError - control via Eff.redeem verifies our impl"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    // cats' redeem is selected via MonadError syntax
    // Our Eff.redeem has same semantics (eliminates error channel)
    val control: UEff[IO, String] = Eff.redeem(eff)(e => s"error: $e", a => s"value: $a")

    for c <- control.either
    yield assertEquals(c, Right("error: boom"))

  test("rethrow on Eff selects Eff extension"):
    val eff: Eff[IO, RuntimeException, Int] = Eff.fail(new RuntimeException("boom"))

    val result: IO[Int] = eff.rethrow
    val control: IO[Int] = Eff.rethrow(eff)

    for
      r <- result.attempt
      c <- control.attempt
    yield
      assert(r.isLeft)
      assert(r.left.exists(_.getMessage == "boom"))
      assert(c.isLeft)
      assert(c.left.exists(_.getMessage == "boom"))

  // ===========================================================================
  // UNIQUE NAMES - No collision possible (our methods only)
  // ===========================================================================

  test("valueOr on Eff is unique to boilerplate-effect (total error recovery)"):
    val eff: Eff[IO, String, Int] = Eff.fail("error")

    // valueOr is our unique name - no cats method with this name on ApplicativeError
    val result: UEff[IO, Int] = eff.valueOr(_.length)
    val control: UEff[IO, Int] = Eff.valueOr(eff)(_.length)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(5))
      assertEquals(c, Right(5))

  test("catchAll on Eff is unique to boilerplate-effect (total effectful recovery)"):
    val eff: Eff[IO, String, Int] = Eff.fail("error")

    // catchAll is our unique name - no collision with cats
    val result: Eff[IO, Nothing, Int] = eff.catchAll(e => Eff.succeed(e.length))
    val control: Eff[IO, Nothing, Int] = Eff.catchAll(eff)(e => Eff.succeed(e.length))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(5))
      assertEquals(c, Right(5))

  test("redeemAll on Eff is unique to boilerplate-effect (effectful fold with error type change)"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    // redeemAll is our unique name - cats uses redeemWith with different signature
    val result: Eff[IO, Int, String] = eff.redeemAll(
      e => Eff.succeed(s"recovered: $e"),
      a => Eff.succeed(s"value: $a")
    )
    val control: Eff[IO, Int, String] = Eff.redeemAll(eff)(
      e => Eff.succeed(s"recovered: $e"),
      a => Eff.succeed(s"value: $a")
    )

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right("recovered: boom"))
      assertEquals(c, Right("recovered: boom"))

  test("mapError on Eff is unique to boilerplate-effect"):
    val eff: Eff[IO, String, Int] = Eff.fail("error")

    val result = eff.mapError(_.toUpperCase)
    val control = Eff.mapError(eff)(_.toUpperCase)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("ERROR"))
      assertEquals(c, Left("ERROR"))

  test("tapError on Eff is unique to boilerplate-effect"):
    var observed: Option[String] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    val result = eff.tapError(e => IO { observed = Some(e) })
    val control = Eff.tapError(eff)(e => IO { observed = Some(e) })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("boom"))
      assertEquals(c, Left("boom"))
      assertEquals(observed, Some("boom"))

  test("tap on Eff is unique to boilerplate-effect"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val eff: Eff[IO, String, Int] = Eff.succeed(42)

    val result = eff.tap(n => IO { observed = Some(n) })
    val control = Eff.tap(eff)(n => IO { observed = Some(n) })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))
      assertEquals(observed, Some(42))

  test("alt on Eff is unique to boilerplate-effect (allows error type change)"):
    val eff: Eff[IO, String, Int] = Eff.fail("first error")
    val fallback: Eff[IO, Int, Int] = Eff.succeed(42)

    val result = eff.alt(fallback)
    val control = Eff.alt(eff)(fallback)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("orElseSucceed on Eff is unique to boilerplate-effect"):
    val eff: Eff[IO, String, Int] = Eff.fail("error")

    val result: UEff[IO, Int] = eff.orElseSucceed(0)
    val control: UEff[IO, Int] = Eff.orElseSucceed(eff)(0)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(0))
      assertEquals(c, Right(0))

  test("orElseFail on Eff is unique to boilerplate-effect"):
    val eff: Eff[IO, String, Int] = Eff.fail("error")

    val result: Eff[IO, Int, Int] = eff.orElseFail(42)
    val control: Eff[IO, Int, Int] = Eff.orElseFail(eff)(42)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left(42))
      assertEquals(c, Left(42))

  // ===========================================================================
  // EITHERT-DERIVED - Eff extensions selected (different type from EitherT)
  // ===========================================================================

  test("ensure on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, String, Int] = Eff.succeed(5)

    val result = eff.ensure("too small")(_ > 10)
    val control = Eff.ensure(eff)("too small")(_ > 10)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("too small"))
      assertEquals(c, Left("too small"))

  test("ensureOr on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, String, Int] = Eff.succeed(5)

    val result = eff.ensureOr(n => s"$n is too small")(_ > 10)
    val control = Eff.ensureOr(eff)(n => s"$n is too small")(_ > 10)

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Left("5 is too small"))
      assertEquals(c, Left("5 is too small"))

  test("semiflatMap on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, String, Int] = Eff.succeed(21)

    val result = eff.semiflatMap(n => IO.pure(n * 2))
    val control = Eff.semiflatMap(eff)(n => IO.pure(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("subflatMap on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, String, Int] = Eff.succeed(21)

    val result = eff.subflatMap(n => Right(n * 2))
    val control = Eff.subflatMap(eff)(n => Right(n * 2))

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  test("fold on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    val result: IO[String] = eff.fold(e => s"error: $e", a => s"value: $a")
    val control: IO[String] = Eff.fold(eff)(e => s"error: $e", a => s"value: $a")

    for
      r <- result
      c <- control
    yield
      assertEquals(r, "error: boom")
      assertEquals(c, "error: boom")

  test("foldF on Eff selects Eff extension (not EitherT)"):
    val eff: Eff[IO, String, Int] = Eff.fail("boom")

    val result: IO[String] = eff.foldF(e => IO.pure(s"error: $e"), a => IO.pure(s"value: $a"))
    val control: IO[String] = Eff.foldF(eff)(e => IO.pure(s"error: $e"), a => IO.pure(s"value: $a"))

    for
      r <- result
      c <- control
    yield
      assertEquals(r, "error: boom")
      assertEquals(c, "error: boom")

  // ===========================================================================
  // MONADCANCEL - Eff extensions selected
  // ===========================================================================

  test("bracket on Eff selects Eff extension (not MonadCancel)"):
    var released = false // scalafix:ok DisableSyntax.var
    val acquire: Eff[IO, String, Int] = Eff.succeed(42)

    val result = acquire.bracket(n => Eff.succeed(n * 2))(_ => IO { released = true })
    val control = Eff.bracket(acquire)(n => Eff.succeed(n * 2))(_ => IO { released = true })

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(84))
      assertEquals(c, Right(84))
      assert(released)

  test("bracketCase on Eff selects Eff extension (not MonadCancel)"):
    var outcome: Option[Outcome[IO, Throwable, Either[String, Int]]] = None // scalafix:ok DisableSyntax.var
    val acquire: Eff[IO, String, Int] = Eff.succeed(42)

    val result = acquire.bracketCase(n => Eff.succeed(n * 2)) { (_, oc) =>
      IO { outcome = Some(oc) }
    }
    val control = Eff.bracketCase(acquire)(n => Eff.succeed(n * 2)) { (_, oc) =>
      IO { outcome = Some(oc) }
    }

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(84))
      assertEquals(c, Right(84))
      assert(outcome.exists(_.isSuccess))

  // ===========================================================================
  // TEMPORAL - Eff extension selected
  // ===========================================================================

  test("timeout on Eff selects Eff extension (not Temporal)"):
    val eff: Eff[IO, String, Int] = Eff.succeed(42)

    val result = eff.timeout(1.second, "timed out")
    val control = Eff.timeout(eff)(1.second, "timed out")

    for
      r <- result.either
      c <- control.either
    yield
      assertEquals(r, Right(42))
      assertEquals(c, Right(42))

  // ===========================================================================
  // TRAVERSE - Eff companion methods (no extension collision)
  // ===========================================================================

  test("Eff.traverse is unique to boilerplate-effect"):
    val items = List(1, 2, 3)

    val result = Eff.traverse[IO, String, Int, Int](items)(n => Eff.succeed(n * 2))

    for r <- result.either
    yield assertEquals(r, Right(List(2, 4, 6)))

  test("Eff.sequence is unique to boilerplate-effect"):
    val effs: List[Eff[IO, String, Int]] =
      List(Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Int](2), Eff.succeed[IO, String, Int](3))

    val result = Eff.sequence[IO, String, Int](effs)

    for r <- result.either
    yield assertEquals(r, Right(List(1, 2, 3)))

  test("Eff.parTraverse is unique to boilerplate-effect"):
    val items = List(1, 2, 3)

    val result = Eff.parTraverse[IO, String, Int, Int](items)(n => Eff.succeed(n * 2))

    for r <- result.either
    yield assertEquals(r, Right(List(2, 4, 6)))

  test("Eff.parSequence is unique to boilerplate-effect"):
    val effs: List[Eff[IO, String, Int]] =
      List(Eff.succeed[IO, String, Int](1), Eff.succeed[IO, String, Int](2), Eff.succeed[IO, String, Int](3))

    val result = Eff.parSequence[IO, String, Int](effs)

    for r <- result.either
    yield assertEquals(r, Right(List(1, 2, 3)))

  // ===========================================================================
  // ORELSE - cats ApplicativeError wins when error types match
  // ===========================================================================

  // NOTE: orElse was intentionally NOT added to Eff API because it collides
  // with cats ApplicativeError.orElse which requires same error type.
  // We use `alt` instead which is unique to our API and allows error type change.
  // See alt tests above (lines 359-370).

  test("alt is our fallback method - avoids orElse collision with cats"):
    val eff: Eff[IO, String, Int] = Eff.fail("error")
    val fallback: Eff[IO, Int, Int] = Eff.succeed(42)

    // alt allows different error types - cats' orElse does not
    val result = eff.alt(fallback)

    for r <- result.either
    yield assertEquals(r, Right(42))

  // ===========================================================================
  // CATS IO OPERATIONS - verify cats methods on IO unaffected by Eff syntax
  // ===========================================================================

  test("recover on IO selects cats ApplicativeError when Eff syntax in scope"):
    val io: IO[Int] = IO.raiseError(new RuntimeException("boom"))

    val result: IO[Int] = io.recover { case _: RuntimeException => 42 }

    for r <- result
    yield assertEquals(r, 42)

  test("handleError on IO selects cats ApplicativeError when Eff syntax in scope"):
    val io: IO[Int] = IO.raiseError(new RuntimeException("boom"))

    val result: IO[Int] = io.handleError(_ => 42)

    for r <- result
    yield assertEquals(r, 42)

  test("redeemWith on IO selects cats MonadError when Eff syntax in scope"):
    val io: IO[Int] = IO.raiseError(new RuntimeException("boom"))

    val result: IO[String] = io.redeemWith(
      _ => IO.pure("recovered"),
      n => IO.pure(s"value: $n")
    )

    for r <- result
    yield assertEquals(r, "recovered")

  test("flatTap on IO selects cats FlatMap when Eff syntax in scope"):
    var observed: Option[Int] = None // scalafix:ok DisableSyntax.var
    val io: IO[Int] = IO.pure(42)

    val result: IO[Int] = io.flatTap(n => IO { observed = Some(n) })

    for r <- result
    yield
      assertEquals(r, 42)
      assertEquals(observed, Some(42))

end OverloadDisambiguationSuite
