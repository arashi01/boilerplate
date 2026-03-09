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

/** Standard Base64 encoding and decoding per RFC 4648 §4.
  *
  * Uses the standard alphabet (`A`–`Z`, `a`–`z`, `0`–`9`, `+`, `/`) with `=` padding. Decoding is
  * strict: invalid characters and malformed padding are rejected.
  *
  * {{{
  * Base64.encode("foobar".getBytes) // "Zm9vYmFy"
  * Base64.decode("Zm9vYmFy")       // Right(Array[Byte](...))
  * }}}
  */
object Base64:

  /** Encodes binary data to a standard Base64 string with padding. */
  inline def encode(data: Array[Byte]): String = PlatformBase64.encode(data)

  /** Decodes a standard Base64 string to binary data. Returns `Left(Error)` for invalid input. */
  inline def decode(input: String): Either[Error, Array[Byte]] = PlatformBase64.decode(input)

  /** Error produced when decoding invalid Base64 input. */
  final class Error(message: String) extends IllegalArgumentException(message)

  object Error:
    given CanEqual[Error, Error] = CanEqual.derived

end Base64
