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

/** Compile-time platform constants for the Scala Native build target.
  *
  * Exactly one of [[linux]]/[[mac]]/[[windows]] and one of [[x86_64]]/[[aarch64]] is `true` per
  * build, determined by the OS/arch-specific source directory selected at compile time.
  *
  * {{{
  * import boilerplate.Platform
  *
  * inline if Platform.linux then linuxImpl()
  * else inline if Platform.mac then macImpl()
  * else windowsImpl()
  * }}}
  */
object Platform:

  /** `true` when the build-target operating system is Linux. */
  inline val linux = false

  /** `true` when the build-target operating system is macOS. */
  inline val mac = true

  /** `true` when the build-target operating system is Windows. */
  inline val windows = false

  /** `true` when the build-target architecture is x86-64. */
  inline val x86_64 = true

  /** `true` when the build-target architecture is AArch64. */
  inline val aarch64 = false

  /** The [[Os]] for the current build target. Reduced to a single constant at compile time. */
  inline def os: Os =
    inline if linux then Os.Linux
    else inline if mac then Os.Mac
    else Os.Windows

  /** The [[Arch]] for the current build target. Reduced to a single constant at compile time. */
  inline def arch: Arch =
    inline if x86_64 then Arch.X86_64
    else Arch.Aarch64
end Platform
