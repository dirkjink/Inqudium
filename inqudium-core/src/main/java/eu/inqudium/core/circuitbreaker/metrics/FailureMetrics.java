package eu.inqudium.core.circuitbreaker.metrics;

import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;

/**
 * Strategy interface for tracking failures and determining if the circuit should open.
 * Implementations must be immutable to fit into the functional core.
 */
public interface FailureMetrics {

  /**
   * Records a successful call and returns the updated metrics state.
   */
  FailureMetrics recordSuccess();

  /**
   * Records a failed call and returns the updated metrics state.
   */
  FailureMetrics recordFailure();

  /**
   * Evaluates if the failure threshold has been reached based on the current state and configuration.
   */
  boolean isThresholdReached(CircuitBreakerConfig config);

  /**
   * Resets the metrics to their initial state (e.g., when transitioning to CLOSED).
   */
  FailureMetrics reset();
}
