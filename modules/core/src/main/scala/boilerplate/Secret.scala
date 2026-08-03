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

import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.language.experimental.captureChecking

/** An OWNING carrier for secret bytes, responsible for erasing them. Where [[Slice]] borrows a
  * region someone else keeps alive, a `Secret` allocates its own and is the thing that wipes it.
  *
  * The bytes are reachable only inside a scoped view: there is no accessor, no copy-out, and no
  * rendering of the contents. `toString` reports the length alone, `hashCode` is constant so a
  * secret cannot seed a hash oracle, and equality is constant-time over the contents.
  *
  * The class is a bare carrier; every operation lives as an extension in [[Secret$]]. Construct
  * with [[Secret$.fill]], read with `use`, erase with `destroy`.
  */
final class Secret private (private val bytes: Array[Byte]):
  // One atomic cell carries the whole lifecycle: 0 idle, n > 0 that many concurrent reads in
  // flight, Int.MinValue destroyed. A separate destroyed flag could not be read-and-acted-on
  // atomically with the reader count, which is exactly the destroy-during-read race.
  private val state: AtomicInteger = new AtomicInteger(Secret.Idle)

  override def toString: String = s"Secret(${bytes.length} bytes)"

  override def hashCode: Int = 0

  override def equals(that: Any): Boolean = that match
    case other: Secret => Slice.of(bytes).constantTimeEquals(Slice.of(other.bytes))
    case _             => false
end Secret

/** Provides the constructor and the scoped-read, erase, and lifecycle extensions for
  * [[boilerplate.Secret Secret]].
  */
object Secret:
  given CanEqual[Secret, Secret] = CanEqual.derived

  private val Idle: Int = 0
  private val Destroyed: Int = Int.MinValue

  /** Allocates `size` zeroed bytes, hands them to `init` as a scoped view, and takes ownership of
    * the result. The buffer exists only inside the carrier, so no unwiped copy is left behind; if
    * `init` throws, the buffer is erased before the throwable propagates.
    */
  def fill(size: Int)(init: Slice^ => Unit): Secret =
    require(size >= 0, "secret size")
    val buffer = new Array[Byte](size)
    try init(Slice.of(buffer))
    catch
      case t: Throwable =>
        // A failed init leaves whatever it managed to write; erase before the throwable escapes.
        Slice.of(buffer).wipe()
        throw t // scalafix:ok DisableSyntax.throw
    new Secret(buffer)

  extension (s: Secret)
    /** Runs `f` on a view of the bytes, valid for that call alone - the only read path.
      *
      * The `Slice^` continuation makes that enforced rather than documented: under
      * `import language.experimental.captureChecking` at the call site, letting the view escape `f`
      * is a compile error, and so is letting a view re-sliced from it escape. Reading a destroyed
      * secret raises `IllegalStateException` rather than silently returning zeroes, and a concurrent
      * [[destroy]] cannot erase the bytes mid-read.
      */
    def use[A](f: Slice^ => A): A =
      enter(s)
      try unguarded(s)(f)
      finally exit(s)

    /** Erases the bytes in place. Idempotent; raises `IllegalStateException` if a [[use]] is in
      * flight, since destroying a secret mid-read is a programmer error.
      */
    def destroy(): Unit = erase(s)
  end extension

  // The read guard, split so that `boilerplate.effect` can hold a borrow across a suspended effect
  // rather than only across a call.
  @tailrec private[boilerplate] def enter(s: Secret): Unit =
    val current = s.state.get()
    if current < 0 then throw new IllegalStateException("secret already destroyed") // scalafix:ok DisableSyntax.throw
    else if !s.state.compareAndSet(current, current + 1) then enter(s)

  private[boilerplate] def exit(s: Secret): Unit =
    val _ = s.state.decrementAndGet()

  private[boilerplate] def unguarded[A](s: Secret)(f: Slice^ => A): A = f(Slice.of(s.bytes))

  @tailrec private def erase(s: Secret): Unit =
    val current = s.state.get()
    if current == Destroyed then ()
    else if current > 0 then throw new IllegalStateException("secret is in use") // scalafix:ok DisableSyntax.throw
    else if s.state.compareAndSet(Idle, Destroyed) then Slice.of(s.bytes).wipe()
    else erase(s)
end Secret
