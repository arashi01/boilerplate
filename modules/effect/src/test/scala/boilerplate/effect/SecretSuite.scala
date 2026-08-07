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

import cats.effect.IO
import munit.CatsEffectSuite

import boilerplate.Secret
import boilerplate.effect.IoError.*

class SecretSuite extends CatsEffectSuite:
  private def filled(bytes: Byte*): Secret =
    Secret.fill(bytes.length)(view => bytes.zipWithIndex.foreach((b, i) => view(i) = b))

  test("useEff reads the bytes and runs the effect the continuation returns"):
    filled(1, 2, 3).useEff(view => IO.pure(view(0) + view(2))).absolve.map(assertEquals(_, 4))

  test("useEff holds the read guard while the effect runs, not merely while the call runs"):
    val secret = filled(1, 2)
    secret
      .useEff(_ => IO(secret.destroy()).attempt)
      .absolve
      .map(outcome => assert(outcome.left.exists(_.getMessage == "secret is in use"), s"destroy was not blocked: $outcome"))

  test("useEff releases the read guard once the effect completes"):
    val secret = filled(1, 2)
    for
      _ <- secret.useEff(view => IO.pure(view(0))).absolve
      _ <- IO(secret.destroy())
      raised <- IO(secret.use(_ => 0)).attempt
    yield assert(raised.left.exists(_.getMessage == "secret already destroyed"))

  test("useEff releases the read guard when the effect fails typed"):
    val secret = filled(1, 2)
    for
      failed <- secret.useEff(_ => Eff.fail[IoError](Closed)).either
      _ <- IO(secret.destroy())
      raised <- IO(secret.use(_ => 0)).attempt
    yield
      assertEquals(failed, Left(Closed))
      assert(raised.left.exists(_.getMessage == "secret already destroyed"))

  test("useEff on a destroyed secret raises on IO's channel"):
    val secret = filled(1, 2)
    secret.destroy()
    secret
      .useEff(view => IO.pure(view(0)))
      .absolve
      .attempt
      .map(r => assert(r.left.exists(_.getMessage == "secret already destroyed"), s"expected a raise, got $r"))

  test("scoped destroys the secret on release after a successful use"):
    for
      secret <- Secret.scoped(2)(view => view(0) = 5).use(IO.pure).absolve
      raised <- IO(secret.use(_ => 0)).attempt
    yield assert(raised.left.exists(_.getMessage == "secret already destroyed"))

  test("scoped destroys the secret on release after a typed failure"):
    for
      captured <- IO.ref(Option.empty[Secret])
      use = (s: Secret) => Eff.flatMap(captured.set(Some(s)))(_ => Eff.fail[IoError](Closed))
      outcome <- Secret.scoped(2)(view => view(0) = 5).use(use).either.absolve
      secret <- captured.get
      raised <- IO(secret.map(_.use(_ => 0))).attempt
    yield
      assertEquals(outcome, Left(Closed))
      assert(raised.left.exists(_.getMessage == "secret already destroyed"))

  test("scoped destroys the secret on release after cancellation"):
    for
      captured <- IO.ref(Option.empty[Secret])
      started <- IO.deferred[Unit]
      body = (s: Secret) => captured.set(Some(s)).flatMap(_ => started.complete(())).flatMap(_ => IO.never[Unit])
      fiber <- Secret.scoped(2)(view => view(0) = 5).use(body).absolve.start
      _ <- started.get
      _ <- fiber.cancel
      secret <- captured.get
      raised <- IO(secret.map(_.use(_ => 0))).attempt
    yield assert(raised.left.exists(_.getMessage == "secret already destroyed"), s"secret survived cancellation: $raised")

end SecretSuite
