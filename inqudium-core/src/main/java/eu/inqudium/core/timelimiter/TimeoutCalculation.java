package eu.inqudium.core.timelimiter;

/**
 * Strategy for computing the TimeLimiter timeout from HTTP client timeout components.
 *
 * <p>Used by {@link InqTimeoutProfile} to derive a TimeLimiter timeout from
 * the individual HTTP layer timeouts (connect, response, etc.) (ADR-012).
 *
 * @since 0.1.0
 */
public enum TimeoutCalculation {

  /**
   * Root Sum of Squares — statistical tolerance analysis.
   *
   * <p>Computes the combined tolerance as the quadratic sum of individual tolerances:
   * {@code RSS = √(t₁² + t₂² + ... + tₙ²)}. Produces tighter, more realistic timeout
   * budgets than worst-case addition because it accounts for the statistical
   * independence of individual delays.
   *
   * <p>Use when the timeout components are independent (connect latency is independent
   * of response latency). This is the common case and the recommended default.
   */
  RSS,

  /**
   * Worst-case addition — linear sum of all timeouts.
   *
   * <p>Computes the total as the sum of all individual timeouts:
   * {@code total = t₁ + t₂ + ... + tₙ}. Equivalent to assuming every component
   * simultaneously hits its maximum tolerance. Produces conservative (large) timeouts.
   *
   * <p>Use when the timeout components are sequentially dependent (e.g. retry attempts)
   * or when a conservative upper bound is preferred over a statistical estimate.
   */
  WORST_CASE,

  /**
   * Maximum — uses the single largest configured timeout as the result.
   *
   * <p>Returns the maximum value among all individual timeout components, scaled
   * by the safety margin factor. All other components are ignored.
   *
   * <p>Use when only the dominant (slowest) timeout component matters and the
   * remaining ones are negligible in comparison — e.g. when one component is an
   * order of magnitude larger than all others.
   */
  MAX
}
