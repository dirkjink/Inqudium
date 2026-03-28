package eu.inqudium.core.timelimiter;

import java.time.Duration;
import java.util.Collection;

/**
 * {@link TimeoutCalculator} implementation that uses the maximum configured
 * timeout component as the result.
 *
 * <p>Selects the single largest duration from all components and applies the
 * safety margin factor directly to that value:
 * <pre>
 *   result = max(t₁, t₂, …, tₙ) × safetyMarginFactor
 * </pre>
 *
 * <p>All other components are ignored. Use when one timeout component dominates
 * and the remaining ones are negligible by comparison — e.g. a multi-second
 * response timeout alongside a sub-100 ms connect timeout.
 *
 * @see RssTimeoutCalculator
 * @see WorstCaseTimeoutCalculator
 * @since 0.1.0
 */
public final class MaxTimeoutCalculator implements TimeoutCalculator {

  /**
   * {@inheritDoc}
   *
   * <p>Iterates over all components, picks the maximum duration, and multiplies
   * it by {@code safetyMarginFactor}. Returns {@link #FALLBACK} for an empty
   * component collection.
   */
  @Override
  public Duration calculate(Collection<Duration> components, double safetyMarginFactor) {
    if (components.isEmpty()) {
      return FALLBACK;
    }

    // Find the maximum duration across all components
    Duration max = components.stream()
        .max(Duration::compareTo)
        .orElseThrow(); // unreachable — emptiness is guarded above

    double totalMs = max.toMillis() * safetyMarginFactor;
    return Duration.ofMillis(Math.round(totalMs));
  }
}
