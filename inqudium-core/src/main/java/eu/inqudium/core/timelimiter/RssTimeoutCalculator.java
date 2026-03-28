package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * {@link TimeoutCalculator} implementation for the RSS (Root Sum of Squares) strategy.
 *
 * <p>Combines the individual tolerance bands as a quadratic sum:
 * <pre>
 *   combined_tolerance = √(tolerance_1² + tolerance_2² + … + tolerance_n²)
 * </pre>
 *
 * <p>Produces tighter, more realistic timeout budgets than worst-case addition
 * because it accounts for the statistical independence of individual delays.
 * Use when the timeout components are independent — the common case and the
 * recommended default (ADR-012).
 *
 * @see WorstCaseTimeoutCalculator
 * @since 0.1.0
 */
public final class RssTimeoutCalculator implements TimeoutCalculator {

  /**
   * {@inheritDoc}
   *
   * <p>Accumulates the squared tolerances of all components, then takes the
   * square root to obtain the combined RSS tolerance before applying the
   * safety margin.
   */
  @Override
  public Duration calculate(Collection<Duration> components, double safetyMarginFactor) {
    if (components.isEmpty()) {
      return FALLBACK;
    }

    double nominalSumMs = 0.0;
    double squaredToleranceSum = 0.0;

    for (Duration component : components) {
      double ms = component.toMillis();
      nominalSumMs += ms * NOMINAL_FRACTION;
      double tolerance = ms * TOLERANCE_FRACTION;
      squaredToleranceSum += tolerance * tolerance; // accumulate squared values for √ below
    }

    double combinedTolerance = Math.sqrt(squaredToleranceSum);
    double totalMs = (nominalSumMs + combinedTolerance) * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
