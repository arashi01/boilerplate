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

object nullable:

  extension [A](value: A | Null)
    /** Converts a nullable value to an Option, eliminating null.
      *
      * @return Some(value) if non-null, None otherwise.
      */
    transparent inline def option: Option[A] =
      // scalafix:off
      if value.asInstanceOf[AnyRef] eq null then None
      else Some(value.asInstanceOf[A])
    // scalafix:on

    /** Converts a nullable value to an Either.
      *
      * @param leftError The error value to use if the value is null.
      * @return Right(value) if non-null, Left(leftError) otherwise.
      */
    transparent inline def either[E](leftError: E): Either[E, A] =
      // scalafix:off
      if value.asInstanceOf[AnyRef] eq null then Left(leftError)
      else Right(value.asInstanceOf[A])
    // scalafix:on

    /** Returns the value if non-null, or the provided default.
      *
      * Useful for Java interop where null is possible but a safe fallback exists.
      *
      * @param default The value to return if null (evaluated lazily).
      * @return The non-null value, or default.
      */
    transparent inline def getOrElse[B >: A](inline default: => B): B =
      // scalafix:off
      if value.asInstanceOf[AnyRef] eq null then default
      else value.asInstanceOf[B]
    // scalafix:on

    /** Asserts non-null, throwing NullPointerException if null.
      *
      * Like stdlib `.nn` but within the boilerplate API for a single-import experience.
      *
      * @return The non-null value.
      * @throws NullPointerException if the value is null.
      */
    transparent inline def unsafe: A =
      // scalafix:off
      if value.asInstanceOf[AnyRef] eq null then throw new NullPointerException("tried to cast away nullability, but value is null")
      else value.asInstanceOf[A]
    // scalafix:on

    /** Asserts non-null with a descriptive message, throwing NullPointerException if null.
      *
      * @param msg The message for the NullPointerException.
      * @return The non-null value.
      * @throws NullPointerException if the value is null.
      */
    transparent inline def unsafe(inline msg: String): A =
      // scalafix:off
      if value.asInstanceOf[AnyRef] eq null then throw new NullPointerException(msg)
      else value.asInstanceOf[A]
    // scalafix:on

    /** Folds over a nullable value, applying a function if non-null or returning a default.
      *
      * Unlike `mapOpt`, this avoids intermediate Option allocation.
      *
      * @param ifNull The value to return if null (evaluated lazily).
      * @param f The function to apply if the value is non-null.
      * @return The result of `f` applied to the non-null value, or `ifNull`.
      */
    transparent inline def fold[B](inline ifNull: => B)(inline f: A => B): B =
      // scalafix:off
      if value.asInstanceOf[AnyRef] eq null then ifNull
      else f(value.asInstanceOf[A])
    // scalafix:on

    /** Maps a nullable value through a function, returning None if null.
      *
      * @param f The function to apply if the value is non-null.
      * @return Some(f(value)) if non-null, None otherwise.
      */
    transparent inline def mapOpt[B](f: A => B): Option[B] =
      value.option.map(f)

    /** FlatMaps a nullable value through a function returning Option.
      *
      * @param f The function to apply if the value is non-null.
      * @return f(value) if non-null, None otherwise.
      */
    transparent inline def flatMapOpt[B](f: A => Option[B]): Option[B] =
      value.option.flatMap(f)
  end extension

  extension [A](optValue: Option[A | Null])
    /** Converts Option[A | Null] to Option[A], eliminating nested nulls.
      *
      * This is useful when combining Option-returning operations with nullable values from Java
      * interop.
      *
      * @return Option[A] with null values filtered out.
      */
    transparent inline def flattenNull: Option[A] = optValue match
      case Some(value) =>
        // scalafix:off
        if value.asInstanceOf[AnyRef] eq null then None
        else Some(value.asInstanceOf[A])
      // scalafix:on
      case _ => None

    /** Maps through a function and flattens null values.
      *
      * @param f The function to apply to non-null values.
      * @return Option[B] with nulls filtered out.
      */
    transparent inline def mapNull[B](f: A => B): Option[B] = optValue match
      case Some(value) =>
        // scalafix:off
        if value.asInstanceOf[AnyRef] eq null then None
        else Some(f(value.asInstanceOf[A]))
      // scalafix:on
      case _ => None

    /** FlatMaps through a function returning Option and flattens null values.
      *
      * @param f The function to apply to non-null values.
      * @return Option[B] with nulls filtered out.
      */
    transparent inline def flatMapNull[B](f: A => Option[B]): Option[B] = optValue match
      case Some(value) =>
        // scalafix:off
        if value.asInstanceOf[AnyRef] eq null then None
        else f(value.asInstanceOf[A])
      // scalafix:on
      case _ => None
  end extension

  extension [E, A](eitherValue: Either[E, A | Null])
    /** Converts Either[E, A | Null] to Either[E, A], treating Right(null) as Left.
      *
      * @param leftError The error value to use if the Right contains null.
      * @return Either[E, A] with null values converted to Left.
      */
    transparent inline def flattenNull(leftError: E): Either[E, A] = eitherValue match
      case Right(value) =>
        // scalafix:off
        if value.asInstanceOf[AnyRef] eq null then Left(leftError)
        else Right(value.asInstanceOf[A])
      // scalafix:on
      case Left(e) => Left(e)

    /** Maps through a function and flattens null values to Left.
      *
      * @param leftError The error value to use if the Right contains null.
      * @param f The function to apply to non-null values.
      * @return Either[E, B] with nulls converted to Left.
      */
    transparent inline def mapNull[B](leftError: E)(f: A => B): Either[E, B] = eitherValue match
      case Right(value) =>
        // scalafix:off
        if value.asInstanceOf[AnyRef] eq null then Left(leftError)
        else Right(f(value.asInstanceOf[A]))
      // scalafix:on
      case Left(e) => Left(e)

    /** FlatMaps through a function returning Either and flattens null values to Left.
      *
      * @param leftError The error value to use if the Right contains null.
      * @param f The function to apply to non-null values.
      * @return Either[E, B] with nulls converted to Left.
      */
    transparent inline def flatMapNull[B](leftError: E)(f: A => Either[E, B]): Either[E, B] = eitherValue match
      case Right(value) =>
        // scalafix:off
        if value.asInstanceOf[AnyRef] eq null then Left(leftError)
        else f(value.asInstanceOf[A])
      // scalafix:on
      case Left(e) => Left(e)
  end extension
end nullable
