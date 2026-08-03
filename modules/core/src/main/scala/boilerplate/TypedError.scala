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

import scala.reflect.TypeTest
import scala.util.control.NoStackTrace

/** Base for a module's typed-error root: a stack-trace-free `Exception` carrying a message and an
  * optional cause, with multiversal equality derived.
  *
  * A typed error is a value on an error channel, raised at most once and matched on, never thrown
  * for a stack trace - so `NoStackTrace` is the shape, not an optimisation. The base is
  * deliberately not sealed: a module declares its own `sealed` root over it, and that root, not
  * this class, is what exhaustivity checks against.
  *
  * {{{
  * sealed abstract class StoreError(message: String, cause: Option[Throwable]) extends TypedError(message, cause)
  * }}}
  *
  * Refer to [[boilerplate.TypedError$ TypedError]] for the idempotent-wrap constructor.
  */
abstract class TypedError(message: String, cause: Option[Throwable])
    extends Exception(message, cause.orNull[Throwable | Null])
    with NoStackTrace derives CanEqual

/** Provides the idempotent-wrap constructor for [[boilerplate.TypedError TypedError]] roots. */
object TypedError:
  /** Wraps `cause` through `construct`, unless it already is a `Root` - in which case it is
    * returned unchanged, so wrapping an error that has already crossed the boundary does not nest
    * it.
    *
    * {{{
    * object Unexpected:
    *   def apply(cause: Throwable): StoreError =
    *     TypedError.idempotent[StoreError, Unexpected](cause)(new Unexpected(_))
    * }}}
    */
  def idempotent[Root <: Throwable, Arm <: Root](cause: Throwable)(construct: Throwable => Arm)(using
    tt: TypeTest[Throwable, Root]
  ): Root =
    cause match
      case tt(root) => root
      case other    => construct(other)
end TypedError
