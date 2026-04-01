package eu.inqudium.core.circuitbreaker.metrics;

import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

/**
 * Immutable implementation of the gradual decay algorithm.
 * One success heals exactly one failure.
 */
public record GradualDecayMetrics(int failureCount) implements FailureMetrics {

  public static GradualDecayMetrics initial() {
    return new GradualDecayMetrics(0);
  }

  @Override
  public FailureMetrics recordSuccess() {
    // Prevent failure count from dropping below zero
    return new GradualDecayMetrics(Math.max(0, failureCount - 1));
  }

  @Override
  public FailureMetrics recordFailure() {
    return new GradualDecayMetrics(failureCount + 1);
  }

  @Override
  public boolean isThresholdReached(CircuitBreakerConfig config) {
    return failureCount >= config.failureThreshold();
  }

  @Override
  public FailureMetrics reset() {
    return initial();
  }
}
