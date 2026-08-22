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
package boilerplate

import java.util.concurrent.atomic.AtomicReference

// Deliberately not capture-checked: an opted-out consumer compiles unchanged, which is what lets
// the wipe rows hold on to a view and read the buffer after the erase.
class SecretSuite extends munit.FunSuite:
  private def filled(bytes: Byte*): Secret =
    Secret.fill(bytes.length)(view => bytes.zipWithIndex.foreach((b, i) => view(i) = b))

  test("fill writes through the scoped view and use reads the result back"):
    assertEquals(filled(1, 2, 3, 4).use(v => List(v(0), v(1), v(2), v(3))), List[Byte](1, 2, 3, 4))

  test("fill rejects a negative size"):
    val _ = intercept[IllegalArgumentException](Secret.fill(-1)(_ => ()))

  test("fill erases the buffer before propagating a throwable raised by init"):
    val escaped = new AtomicReference[Option[Slice]](None)
    val raised = intercept[IllegalStateException]:
      Secret.fill(4) { view =>
        view(0) = 1
        view(1) = 2
        view(2) = 3
        view(3) = 4
        escaped.set(Some(view))
        throw new IllegalStateException("init failed") // scalafix:ok DisableSyntax.throw
      }
    val seen = escaped.get.getOrElse(fail("init never received a view"))
    assertEquals(raised.getMessage, "init failed")
    assertEquals(List(seen(0), seen(1), seen(2), seen(3)), List[Byte](0, 0, 0, 0))

  test("toString reports the length and never the contents"):
    assertEquals(filled(1, 2, 3, 4).toString, "Secret(4 bytes)")
    assertEquals(Secret.fill(0)(_ => ()).toString, "Secret(0 bytes)")

  test("equality is by content, so equal bytes compare equal and one differing byte does not"):
    assertEquals(filled(1, 2, 3, 4), filled(1, 2, 3, 4))
    assertNotEquals(filled(1, 2, 3, 4), filled(9, 2, 3, 4))
    assertNotEquals(filled(1, 2, 3, 4), filled(1, 2, 3, 5))
    assertNotEquals(filled(1, 2, 3), filled(1, 2, 3, 4))

  test("a destroyed secret is equal only to itself"):
    val a = filled(1, 2, 3, 4)
    a.destroy()
    assertEquals(a, a)
    val b = filled(9, 8, 7, 6)
    b.destroy()
    assertNotEquals(a, b)

  test("a destroyed secret and a live zero-filled one are unequal in both directions"):
    val destroyed = filled(1, 2, 3, 4)
    destroyed.destroy()
    val zeros = Secret.fill(4)(_ => ())
    assertNotEquals(destroyed, zeros)
    assertNotEquals(zeros, destroyed)

  test("equality releases the read guard, leaving both secrets destroyable"):
    val a = filled(1, 2)
    val b = filled(1, 2)
    assertEquals(a, b)
    a.destroy()
    b.destroy()

  test("equality inside an in-flight use still compares, readers stacking"):
    val a = filled(7, 7)
    val b = filled(7, 7)
    assert(a.use(_ => a == b))

  test("hashCode is constant, so a secret cannot seed a hash oracle"):
    assertEquals(filled(1, 2, 3, 4).hashCode, filled(9, 9).hashCode)
    assertEquals(filled(1, 2, 3, 4).hashCode, Secret.fill(0)(_ => ()).hashCode)

  test("destroy erases the bytes in place"):
    val escaped = new AtomicReference[Option[Slice]](None)
    val secret = filled(1, 2, 3, 4)
    secret.use(view => escaped.set(Some(view)))
    secret.destroy()
    val seen = escaped.get.getOrElse(fail("use never received a view"))
    assertEquals(List(seen(0), seen(1), seen(2), seen(3)), List[Byte](0, 0, 0, 0))

  test("use after destroy raises rather than silently reading zeroes"):
    val secret = filled(1, 2)
    secret.destroy()
    val raised = intercept[IllegalStateException](secret.use(_ => 0))
    assertEquals(raised.getMessage, "secret already destroyed")

  test("destroy is idempotent"):
    val secret = filled(1, 2)
    secret.destroy()
    secret.destroy()
    val _ = intercept[IllegalStateException](secret.use(_ => 0))

  test("destroy raises while a use is in flight, rather than erasing bytes mid-read"):
    val secret = filled(1, 2)
    val raised = secret.use(_ => intercept[IllegalStateException](secret.destroy()))
    assertEquals(raised.getMessage, "secret is in use")
    assertEquals(secret.use(v => v(0)), 1.toByte)

  test("of copies the source bytes, leaving the source the caller's to wipe"):
    val source = Array[Byte](1, 2, 3)
    val secret = Secret.of(Slice.of(source))
    assertEquals(secret.use(_.toArray.toList), List[Byte](1, 2, 3))
    assertEquals(source.toList, List[Byte](1, 2, 3))
    Slice.of(source).wipe()
    // Wiping the source must not reach the carrier: `of` copied, it did not adopt.
    assertEquals(secret.use(_.toArray.toList), List[Byte](1, 2, 3))

  test("of over an empty source yields an empty secret"):
    assertEquals(Secret.of(Slice.of(Array.empty[Byte])).use(_.length), 0)

  test("a use that throws still releases the read guard, leaving the secret destroyable"):
    val secret = filled(1, 2)
    val _ = intercept[IllegalStateException](secret.use(_ => throw new IllegalStateException("body"))) // scalafix:ok DisableSyntax.throw
    secret.destroy()
    val raised = intercept[IllegalStateException](secret.use(_ => 0))
    assertEquals(raised.getMessage, "secret already destroyed")
end SecretSuite
