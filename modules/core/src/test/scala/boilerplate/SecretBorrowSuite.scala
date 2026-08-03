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

import scala.language.experimental.captureChecking

// The opted-in caller. Escape rejection is asserted by the build's `checkCaptureEscapes`, not here:
// `typeCheckErrors` compiles its snippet in a nested scope, where the language import is rejected
// and the body is never capture-checked, so such a row would pass without the `^` annotation.
class SecretBorrowSuite extends munit.FunSuite:
  test("a scoped read that keeps nothing compiles"):
    val secret = Secret.fill(4)(view => view(0) = 7)
    assertEquals(secret.use(view => view(0)), 7.toByte)

  test("a re-sliced view is readable inside the scope and its copy outlives it"):
    val secret = Secret.fill(4)(view => { view(1) = 8; view(2) = 9 })
    assertEquals(secret.use(view => view.drop(1).take(2).toArray).toList, List[Byte](8, 9))
end SecretBorrowSuite
