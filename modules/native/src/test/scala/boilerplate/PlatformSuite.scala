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

class PlatformSuite extends FunSuite:

  test("Platform.linux matches runtime detection"):
    assertEquals(Platform.linux, scala.scalanative.runtime.Platform.isLinux())

  test("Platform.mac matches runtime detection"):
    assertEquals(Platform.mac, scala.scalanative.runtime.Platform.isMac())

  test("Platform.windows matches runtime detection"):
    assertEquals(Platform.windows, scala.scalanative.runtime.Platform.isWindows())

  test("exactly one operating-system constant is true"):
    assertEquals(List(Platform.linux, Platform.mac, Platform.windows).count(identity), 1)

  test("Platform.os matches runtime platform"):
    val expected =
      if scala.scalanative.runtime.Platform.isLinux() then Os.Linux
      else if scala.scalanative.runtime.Platform.isMac() then Os.Mac
      else Os.Windows
    assertEquals(Platform.os, expected)

  test("exactly one architecture constant is true"):
    assertEquals(List(Platform.x86_64, Platform.aarch64).count(identity), 1)

  test("Platform.arch agrees with the architecture constants"):
    val expected = if Platform.x86_64 then Arch.X86_64 else Arch.Aarch64
    assertEquals(Platform.arch, expected)

end PlatformSuite
