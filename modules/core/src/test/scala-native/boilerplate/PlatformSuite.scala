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

import munit.FunSuite

/** Verifies compile-time [[Platform]] constants against Scala Native runtime detection.
  *
  * This suite lives in a Native-specific test directory because both [[Platform]] (OS-specific
  * source) and `scala.scalanative.runtime.Platform` (Native runtime) are only available when
  * compiling for Scala Native.
  */
class PlatformSuite extends FunSuite:

  // -------------------------------------------------------------------------
  // Compile-time constants match runtime detection
  // -------------------------------------------------------------------------

  test("Platform.linux matches runtime detection"):
    assertEquals(Platform.linux, scala.scalanative.runtime.Platform.isLinux())

  test("Platform.mac matches runtime detection"):
    assertEquals(Platform.mac, scala.scalanative.runtime.Platform.isMac())

  test("Platform.windows matches runtime detection"):
    assertEquals(Platform.windows, scala.scalanative.runtime.Platform.isWindows())

  // -------------------------------------------------------------------------
  // Exactly one platform is active
  // -------------------------------------------------------------------------

  test("exactly one platform constant is true"):
    val active = List(Platform.linux, Platform.mac, Platform.windows).count(identity)
    assertEquals(active, 1)

  // -------------------------------------------------------------------------
  // Platform.current agrees with runtime
  // -------------------------------------------------------------------------

  test("Platform.current matches runtime platform"):
    val expected =
      if scala.scalanative.runtime.Platform.isLinux() then Platform.Linux
      else if scala.scalanative.runtime.Platform.isMac() then Platform.Mac
      else Platform.Windows
    assertEquals(Platform.current, expected)

end PlatformSuite
