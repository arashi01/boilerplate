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

import boilerplate.nullable.*

// scalafix:off DisableSyntax.null
class NullableTests extends FunSuite:

  // --- option ---

  test("option: returns Some for non-null value") {
    val value: String | Null = "hello"
    assertEquals(value.option, Some("hello"))
  }

  test("option: returns None for null") {
    val value: String | Null = null
    assertEquals(value.option, None)
  }

  test("option: does not treat falsy values as null") {
    assertEquals((0: Int | Null).option, Some(0))
    assertEquals((false: Boolean | Null).option, Some(false))
    assertEquals(("": String | Null).option, Some(""))
  }

  // --- either ---

  test("either: returns Right for non-null value") {
    val value: String | Null = "hello"
    assertEquals(value.either("error"), Right("hello"))
  }

  test("either: returns Left for null") {
    val value: String | Null = null
    assertEquals(value.either("error"), Left("error"))
  }

  // --- mapOpt ---

  test("mapOpt: applies function to non-null value") {
    val value: String | Null = "hello"
    assertEquals(value.mapOpt(_.length), Some(5))
  }

  test("mapOpt: returns None for null") {
    val value: String | Null = null
    assertEquals(value.mapOpt(_.length), None)
  }

  // --- flatMapOpt ---

  test("flatMapOpt: applies function returning Some") {
    val value: String | Null = "hello"
    assertEquals(value.flatMapOpt(s => Some(s.length)), Some(5))
  }

  test("flatMapOpt: applies function returning None") {
    val value: String | Null = "hello"
    assertEquals(value.flatMapOpt(_ => None), None)
  }

  test("flatMapOpt: returns None for null input") {
    val value: String | Null = null
    assertEquals(value.flatMapOpt(s => Some(s.length)), None)
  }

  // --- flattenNull ---

  test("flattenNull: returns Some for Some(non-null)") {
    val opt: Option[String | Null] = Some("hello")
    assertEquals(opt.flattenNull, Some("hello"))
  }

  test("flattenNull: returns None for Some(null)") {
    val opt: Option[String | Null] = Some(null)
    assertEquals(opt.flattenNull, None)
  }

  test("flattenNull: returns None for None") {
    val opt: Option[String | Null] = None
    assertEquals(opt.flattenNull, None)
  }

  test("flattenNull: does not treat falsy values as null") {
    assertEquals(Some(0: Int | Null).flattenNull, Some(0))
    assertEquals(Some(false: Boolean | Null).flattenNull, Some(false))
    assertEquals(Some("": String | Null).flattenNull, Some(""))
  }

  // --- mapNull ---

  test("mapNull: applies function to Some(non-null)") {
    val opt: Option[String | Null] = Some("hello")
    assertEquals(opt.mapNull(_.length), Some(5))
  }

  test("mapNull: returns None for Some(null)") {
    val opt: Option[String | Null] = Some(null)
    assertEquals(opt.mapNull(_.length), None)
  }

  test("mapNull: returns None for None") {
    val opt: Option[String | Null] = None
    assertEquals(opt.mapNull(_.length), None)
  }

  // --- flatMapNull ---

  test("flatMapNull: applies function returning Some to Some(non-null)") {
    val opt: Option[String | Null] = Some("hello")
    assertEquals(opt.flatMapNull(s => Some(s.length)), Some(5))
  }

  test("flatMapNull: applies function returning None to Some(non-null)") {
    val opt: Option[String | Null] = Some("hello")
    assertEquals(opt.flatMapNull(_ => None), None)
  }

  test("flatMapNull: returns None for Some(null)") {
    val opt: Option[String | Null] = Some(null)
    assertEquals(opt.flatMapNull(s => Some(s.length)), None)
  }

  test("flatMapNull: returns None for None") {
    val opt: Option[String | Null] = None
    assertEquals(opt.flatMapNull(s => Some(s.length)), None)
  }

  // --- Either flattenNull ---

  test("Either flattenNull: returns Right for Right(non-null)") {
    val either: Either[String, String | Null] = Right("hello")
    assertEquals(either.flattenNull("error"), Right("hello"))
  }

  test("Either flattenNull: returns Left for Right(null)") {
    val either: Either[String, String | Null] = Right(null)
    assertEquals(either.flattenNull("error"), Left("error"))
  }

  test("Either flattenNull: preserves Left") {
    val either: Either[String, String | Null] = Left("original")
    assertEquals(either.flattenNull("error"), Left("original"))
  }

  // --- Either mapNull ---

  test("Either mapNull: applies function to Right(non-null)") {
    val either: Either[String, String | Null] = Right("hello")
    assertEquals(either.mapNull("error")(_.length), Right(5))
  }

  test("Either mapNull: returns Left for Right(null)") {
    val either: Either[String, String | Null] = Right(null)
    assertEquals(either.mapNull("error")(_.length), Left("error"))
  }

  test("Either mapNull: preserves Left") {
    val either: Either[String, String | Null] = Left("original")
    assertEquals(either.mapNull("error")(_.length), Left("original"))
  }

  // --- Either flatMapNull ---

  test("Either flatMapNull: applies function returning Right to Right(non-null)") {
    val either: Either[String, String | Null] = Right("hello")
    assertEquals(either.flatMapNull("error")(s => Right(s.length)), Right(5))
  }

  test("Either flatMapNull: applies function returning Left to Right(non-null)") {
    val either: Either[String, String | Null] = Right("hello")
    assertEquals(either.flatMapNull("error")(_ => Left("failed")), Left("failed"))
  }

  test("Either flatMapNull: returns Left for Right(null)") {
    val either: Either[String, String | Null] = Right(null)
    assertEquals(either.flatMapNull("error")(s => Right(s.length)), Left("error"))
  }

  test("Either flatMapNull: preserves Left") {
    val either: Either[String, String | Null] = Left("original")
    assertEquals(either.flatMapNull("error")(s => Right(s.length)), Left("original"))
  }
end NullableTests
