package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.circuitbreaker.metrics.FailureMetrics;
import java.time.Instant;

/**
 * Immutable snapshot of the circuit breaker's internal state.
 *
 * <p>This is the central data structure of the functional core.
 * All state transitions produce a new snapshot rather than mutating in place.
 *
 * @param state            the current circuit state
 * @param failureMetrics   strategy for tracking failures and evaluating thresholds
 * @param successCount     accumulated success count in HALF_OPEN (reset on state transition)
 * @param halfOpenAttempts number of probe calls issued in HALF_OPEN
 * @param stateChangedAt   timestamp of the last state transition
 */
public record CircuitBreakerSnapshot(
    CircuitState state,
    FailureMetrics failureMetrics,
    int successCount,
    int halfOpenAttempts,
    Instant stateChangedAt
) {

  /**
   * Creates the initial snapshot in CLOSED state with the provided initial metrics.
   */
  public static CircuitBreakerSnapshot initial(Instant now, FailureMetrics initialMetrics) {
    return new CircuitBreakerSnapshot(CircuitState.CLOSED, initialMetrics, 0, 0, now);
  }

  // --- Wither methods for immutable updates ---

  /**
   * Transitions to a new state, automatically resetting the failure metrics
   * and the HALF_OPEN counters to their initial values.
   */
  public CircuitBreakerSnapshot withState(CircuitState newState, Instant now) {
    return new CircuitBreakerSnapshot(newState, failureMetrics.reset(), 0, 0, now);
  }

  /**
   * Applies an updated failure metrics state (e.g., after recording a success or failure).
   */
  public CircuitBreakerSnapshot withUpdatedFailureMetrics(FailureMetrics newMetrics) {
    return new CircuitBreakerSnapshot(state, newMetrics, successCount, halfOpenAttempts, stateChangedAt);
  }

  public CircuitBreakerSnapshot withIncrementedSuccessCount() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount + 1, halfOpenAttempts, stateChangedAt);
  }

  public CircuitBreakerSnapshot withIncrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount, halfOpenAttempts + 1, stateChangedAt);
  }

  /**
   * Allows releasing a HALF_OPEN attempt slot when an ignored exception occurs.
   */
  public CircuitBreakerSnapshot withDecrementedHalfOpenAttempts() {
    return new CircuitBreakerSnapshot(state, failureMetrics, successCount, Math.max(0, halfOpenAttempts - 1), stateChangedAt);
  }
}