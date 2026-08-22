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

/** Compile-time constants identifying the Scala Native build target.
  *
  * Exactly one of [[linux]]/[[darwin]]/[[windows]] and one of [[x86_64]]/[[aarch64]] is `true` per
  * build, because each target publishes as its own classified NIR jar. The names are sbt-snx's own
  * `snx.OS` and `snx.Arch` case for case, so a build definition and a consumed artefact agree on
  * what a target is called; the plugin's `osx` classifier token is this enum's `Darwin`.
  */
object Platform:

  inline val linux = false

  inline val darwin = true

  inline val windows = false

  inline val x86_64 = true

  inline val aarch64 = false

  /** The [[OS]] of the build target, as a compile-time constant. */
  inline def os: OS =
    inline if linux then OS.Linux
    else inline if darwin then OS.Darwin
    else OS.Windows

  /** The [[Arch]] of the build target, as a compile-time constant. */
  inline def arch: Arch =
    inline if x86_64 then Arch.X86_64
    else Arch.Aarch64
end Platform
