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
package boilerplate.codec

import scala.scalajs.js

import boilerplate.nullable.getOrElse

/** JS Base64 implementation backed by `globalThis.atob`/`globalThis.btoa`. */
private[codec] object PlatformBase64:

  inline def encode(data: Array[Byte]): String =
    val binaryStr = new String(data.map(b => (b & 0xff).toChar))
    js.Dynamic.global.btoa(binaryStr).asInstanceOf[String] // scalafix:ok DisableSyntax.asInstanceOf

  inline def decode(input: String): Either[Base64.Error, Array[Byte]] =
    try
      val binary = js.Dynamic.global.atob(input).asInstanceOf[String] // scalafix:ok DisableSyntax.asInstanceOf
      Right(Array.tabulate(binary.length)(i => binary.charAt(i).toByte))
    catch
      case e: Exception =>
        Left(new Base64.Error(e.getMessage.getOrElse("base64 decode failed")))
end PlatformBase64
