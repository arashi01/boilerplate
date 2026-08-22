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
package boilerplate.stream

import scala.compiletime.testing.typeChecks

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite

import boilerplate.TypedError
import boilerplate.effect.Eff
import boilerplate.effect.TEff

final case class NetErr(detail: String) extends TypedError(detail, None)
final case class ParseErr(detail: String) extends TypedError(detail, None)
final case class Rogue(detail: String) extends Exception(detail)

class EffStreamSuite extends CatsEffectSuite:
  private val bytes: EffStream[NetErr, Int] = Stream(1, 2, 3) ++ Stream.raiseError[IO](NetErr("reset"))
  private val parse: EffPipe[ParseErr, Int, String] = _.map(_.toString)
  private val defect: EffStream[NetErr, Int] = Stream(1) ++ Stream.raiseError[IO](Rogue("boom"))

  // A narrow pipe widened, applied to a stream of the union: `through` takes its function's input at
  // the receiver's own effect, so the pipe has to be declared at the wider channel.
  private val piped: EffStream[NetErr | ParseErr, String] = bytes.through(parse.widen[NetErr | ParseErr])

  test("compiling a typed stream lands on Eff through the Async instance"):
    val compiled: Eff[NetErr | ParseErr, List[String]] = piped.compile.toList
    assert(typeChecks("val x: boilerplate.effect.TEff[Unit] = (fs2.Stream(1): boilerplate.stream.EffStream[Throwable, Int]).compile.drain"))
    assert(
      typeChecks(
        "val x: boilerplate.effect.Eff[boilerplate.stream.NetErr, Unit] = (fs2.Stream(1): boilerplate.stream.EffStream[boilerplate.stream.NetErr, Int]).compile.drain"
      )
    )
    compiled.either.map(r => assertEquals(r, Left(NetErr("reset"))))

  test("a typed failure surfaces on the compiled effect's channel"):
    piped.compile.toList.either.map(r => assertEquals(r, Left(NetErr("reset"))))

  test("catchAll recovers the typed arm"):
    bytes.catchAll(_ => Stream(-1)).compile.toList.absolve.map(r => assertEquals(r, List(1, 2, 3, -1)))

  test("reify appends the typed failure as a final Left"):
    bytes.reify.compile.toList.absolve.map(r => assertEquals(r, List(Right(1), Right(2), Right(3), Left(NetErr("reset")))))

  test("mapError transforms the typed channel"):
    bytes.mapError(e => ParseErr(e.detail)).compile.drain.either.map(r => assertEquals(r, Left(ParseErr("reset"))))

  test("a defect passes through catchAll and reify untouched"):
    for
      viaCatchAll <- defect.catchAll(_ => Stream(0)).compile.drain.absolve.attempt
      viaReify <- defect.reify.compile.drain.absolve.attempt
    yield
      assert(viaCatchAll.left.exists(_.isInstanceOf[Rogue]), s"catchAll captured a defect: $viaCatchAll") // scalafix:ok DisableSyntax.isInstanceOf
      assert(viaReify.left.exists(_.isInstanceOf[Rogue]), s"reify captured a defect: $viaReify") // scalafix:ok DisableSyntax.isInstanceOf

  test("HAZARD: fs2's own attempt is a member, wins over the extensions, and captures a defect"):
    // This row locks the claim the vocabulary's documentation makes: `attempt` and `handleErrorWith`
    // are fs2's, they take every `Throwable`, and no extension here can shadow them.
    defect.attempt.compile.toList.absolve.map { captured =>
      assert(
        captured.exists(_.left.exists(_.isInstanceOf[Rogue])), // scalafix:ok DisableSyntax.isInstanceOf
        s"fs2's attempt no longer captures a defect: $captured"
      )
    }

  test("a stream widens by subtyping and a raw IO stream lands in a typed position"):
    assert(typeChecks("val w: boilerplate.stream.EffStream[boilerplate.stream.NetErr | boilerplate.stream.ParseErr, Int] = boilerplate.stream.Fixture.bytes"))
    assert(typeChecks("val w: boilerplate.stream.EffStream[boilerplate.stream.NetErr, Int] = fs2.Stream.eval(cats.effect.IO.pure(1))"))
    // A pipe does not: its effect is invariant, so the cast-free widen is the door.
    assert(
      !typeChecks(
        "val w: boilerplate.stream.EffPipe[boilerplate.stream.NetErr | boilerplate.stream.ParseErr, Int, String] = boilerplate.stream.Fixture.parse"
      )
    )
    piped.compile.toList.either.map(r => assertEquals(r, Left(NetErr("reset"))))

  test(".eff marks an IO-stream generator so the chain stays on the typed surface"):
    val program: EffStream[NetErr, Int] =
      for
        a <- Stream.eval(IO.pure(1)).eff
        b <- bytes
      yield a + b
    program.compile.toList.either.map(r => assertEquals(r, Left(NetErr("reset"))))

  test("absolve is the one-directional exit back to IO"):
    val exited: Stream[IO, Int] = bytes.absolve
    exited.compile.toList.attempt.map(r => assert(r.left.exists(_.getMessage == "reset")))
end EffStreamSuite

// Stable paths for the compile-time rows above.
object Fixture:
  val bytes: EffStream[NetErr, Int] = Stream(1, 2, 3)
  val parse: EffPipe[ParseErr, Int, String] = _.map(_.toString)
