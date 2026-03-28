package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * {@link TimeoutCalculator} implementation for the worst-case (linear sum) strategy.
 *
 * <p>Combines the individual tolerance bands as a simple linear sum:
 * <pre>
 *   combined_tolerance = tolerance_1 + tolerance_2 + … + tolerance_n
 * </pre>
 *
 * <p>Equivalent to assuming every component simultaneously hits its maximum
 * tolerance. Produces conservative (larger) timeouts than RSS.
 * Use when timeout components are sequentially dependent (e.g. retry attempts)
 * or when a conservative upper bound is preferred over a statistical estimate
 * (ADR-012).
 *
 * @see RssTimeoutCalculator
 * @since 0.1.0
 */
public final class WorstCaseTimeoutCalculator implements TimeoutCalculator {

  /**
   * {@inheritDoc}
   *
   * <p>Accumulates all tolerances linearly (no squaring), then applies the
   * safety margin to the combined sum.
   */
  @Override
  public Duration calculate(Collection<Duration> components, double safetyMarginFactor) {
    if (components.isEmpty()) {
      return FALLBACK;
    }

    double nominalSumMs = 0.0;
    double toleranceSum = 0.0;

    for (Duration component : components) {
      double ms = component.toMillis();
      nominalSumMs += ms * NOMINAL_FRACTION;
      toleranceSum += ms * TOLERANCE_FRACTION; // accumulate linearly
    }

    double totalMs = (nominalSumMs + toleranceSum) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
