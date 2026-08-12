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

import scala.util.control.NoStackTrace

// Two independent roots so the suites can exercise both the subtype lattice (covariance) and the
// union channel (`AppError | IOError`).
sealed abstract class AppError(message: String) extends Exception(message) with NoStackTrace derives CanEqual
object AppError:
  final case class NotFound(id: String) extends AppError(s"not found: $id")
  final case class Invalid(reason: String) extends AppError(s"invalid: $reason")
  case object Timeout extends AppError("timed out")

sealed abstract class IOError(message: String) extends Exception(message) with NoStackTrace derives CanEqual
object IOError:
  final case class Failed(code: Int) extends IOError(s"io failed: $code")
  case object Closed extends IOError("closed")
