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

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/** Declarative retry pacing, interpreted by the policy-driven
  * [[boilerplate.effect.Eff$ Eff]]`.retry` overloads.
  *
  * A policy describes only HOW attempts are paced and bounded; WHICH typed errors are worth
  * retrying is supplied at the interpretation site, so one policy value is shareable across
  * differently-typed effects. All bounds are optional and independent:
  *   - `maxAttempts` bounds TOTAL executions, the first included; `n <= 1` means a single run.
  *     `None` retries indefinitely - bound at least one of attempts or cumulative delay unless
  *     unbounded retrying is intended.
  *   - `maxDelay` caps each individual delay after the backoff computes it.
  *   - `maxCumulativeDelay` budgets total time SPENT SLEEPING (not wall-clock time of the attempts
  *     themselves): retrying stops - the error propagates - rather than sleep beyond the budget.
  *
  * Construct via the companion (`constant`, `exponential`, `fullJitter`, `decorrelated`) and refine
  * with `withMaxAttempts`, `withMaxDelay`, and `withMaxCumulativeDelay`.
  */
final case class RetryPolicy(
  backoff: RetryPolicy.Backoff,
  maxAttempts: Option[Int],
  maxDelay: Option[FiniteDuration],
  maxCumulativeDelay: Option[FiniteDuration]
) derives CanEqual

object RetryPolicy:

  /** Delay strategy for the retry following attempt `n` (1-based). Jitter is not an orthogonal flag
    * because decorrelated jitter replaces the exponential series rather than decorating it; each
    * case is therefore a complete strategy and no invalid combination is representable.
    */
  enum Backoff derives CanEqual:
    /** The same delay before every retry. */
    case Constant(delay: FiniteDuration)

    /** The deterministic series `initial * factor^(n-1)`. */
    case Exponential(initial: FiniteDuration, factor: Double)

    /** A delay drawn uniformly from `[0, initial * factor^(n-1)]` - the AWS "full jitter" strategy.
      * `FullJitter(d, 1.0)` draws from a constant `[0, d]` window every retry.
      */
    case FullJitter(initial: FiniteDuration, factor: Double)

    /** A delay drawn uniformly between `base` and `previous * factor`, starting from `base` - the
      * AWS "decorrelated jitter" strategy (canonically `factor = 3`). Bound its growth with
      * `withMaxDelay`.
      */
    case Decorrelated(base: FiniteDuration, factor: Double)
  end Backoff

  /** A policy with the same delay before every retry and no bounds. */
  def constant(delay: FiniteDuration): RetryPolicy =
    require(delay >= Duration.Zero, "delay must be non-negative")
    RetryPolicy(Backoff.Constant(delay), None, None, None)

  /** As
    * [[exponential(initial:scala\.concurrent\.duration\.FiniteDuration,factor:Double)* exponential]]
    * with the conventional doubling factor.
    */
  def exponential(initial: FiniteDuration): RetryPolicy = exponential(initial, 2.0)

  /** A policy pacing retries on the deterministic series `initial * factor^(n-1)`, unbounded until
    * refined.
    */
  def exponential(initial: FiniteDuration, factor: Double): RetryPolicy =
    require(initial >= Duration.Zero, "initial must be non-negative")
    require(factor > 0, "factor must be positive")
    RetryPolicy(Backoff.Exponential(initial, factor), None, None, None)

  /** As
    * [[fullJitter(initial:scala\.concurrent\.duration\.FiniteDuration,factor:Double)* fullJitter]]
    * with the conventional doubling factor.
    */
  def fullJitter(initial: FiniteDuration): RetryPolicy = fullJitter(initial, 2.0)

  /** A policy drawing each delay uniformly from `[0, initial * factor^(n-1)]`, unbounded until
    * refined.
    */
  def fullJitter(initial: FiniteDuration, factor: Double): RetryPolicy =
    require(initial >= Duration.Zero, "initial must be non-negative")
    require(factor > 0, "factor must be positive")
    RetryPolicy(Backoff.FullJitter(initial, factor), None, None, None)

  /** As
    * [[decorrelated(base:scala\.concurrent\.duration\.FiniteDuration,factor:Double)* decorrelated]]
    * with the canonical growth factor of 3.
    */
  def decorrelated(base: FiniteDuration): RetryPolicy = decorrelated(base, 3.0)

  /** A policy drawing each delay uniformly between `base` and the previous delay times `factor`,
    * unbounded until refined.
    */
  def decorrelated(base: FiniteDuration, factor: Double): RetryPolicy =
    require(base >= Duration.Zero, "base must be non-negative")
    require(factor > 0, "factor must be positive")
    RetryPolicy(Backoff.Decorrelated(base, factor), None, None, None)

  extension (self: RetryPolicy)
    /** Bounds total executions, the first included; `n <= 1` means a single run. */
    def withMaxAttempts(n: Int): RetryPolicy = self.copy(maxAttempts = Some(n))

    /** Caps each individual delay. */
    def withMaxDelay(d: FiniteDuration): RetryPolicy =
      require(d >= Duration.Zero, "maxDelay must be non-negative")
      self.copy(maxDelay = Some(d))

    /** Budgets total sleeping time; retrying stops rather than sleep beyond it. */
    def withMaxCumulativeDelay(d: FiniteDuration): RetryPolicy =
      require(d >= Duration.Zero, "maxCumulativeDelay must be non-negative")
      self.copy(maxCumulativeDelay = Some(d))
  end extension
end RetryPolicy
