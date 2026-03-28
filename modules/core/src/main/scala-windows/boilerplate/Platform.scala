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

/** Build-host operating system, resolved at compile time for Scala Native targets.
  *
  * See [[Platform$ Platform]] companion for compile-time constants.
  */
enum Platform:
  case Linux, Mac, Windows

/** Compile-time operating system constants for Scala Native targets.
  *
  * Exactly one of [[linux]], [[mac]], or [[windows]] is `true` per build,
  * determined by the OS-specific source directory selected at compile time.
  * Branches on these constants are eliminated by the compiler, producing
  * zero-overhead platform-conditional code.
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

  given CanEqual[Platform, Platform] = CanEqual.derived

  /** `true` when the build-host operating system is Linux. */
  inline val linux = false

  /** `true` when the build-host operating system is macOS. */
  inline val mac = false

  /** `true` when the build-host operating system is Windows. */
  inline val windows = true

  /** The [[Platform]] value for the current build-host operating system.
    *
    * Reduced to a single constant at compile time via `inline if`.
    */
  inline def current: Platform =
    inline if linux then Platform.Linux
    else inline if mac then Platform.Mac
    else Platform.Windows
