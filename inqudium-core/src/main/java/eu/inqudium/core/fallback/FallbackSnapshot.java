package eu.inqudium.core.fallback;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable snapshot of a single fallback-protected execution's state.
 *
 * <p>Each execution gets its own snapshot tracking the lifecycle from
 * primary attempt through to final outcome (success, recovery, or failure).
 *
 * @param state             the current fallback state
 * @param primaryFailure    the exception from the primary operation ({@code null} if succeeded)
 * @param fallbackFailure   the exception from the fallback handler ({@code null} if not attempted or succeeded)
 * @param handlerName       the name of the fallback handler that was invoked ({@code null} if none)
 * @param startTime         when the primary execution started
 * @param fallbackStartTime when the fallback handler started ({@code null} if not attempted)
 * @param endTime           when the execution reached a terminal state
 */
public record FallbackSnapshot(
    FallbackState state,
    Throwable primaryFailure,
    Throwable fallbackFailure,
    String handlerName,
    Instant startTime,
    Instant fallbackStartTime,
    Instant endTime
) {

  /**
   * Creates the initial snapshot in IDLE state.
   */
  public static FallbackSnapshot idle() {
    return new FallbackSnapshot(
        FallbackState.IDLE, null, null, null, null, null, null);
  }

  // --- Wither methods for immutable state transitions ---

  /**
   * Transitions to EXECUTING.
   */
  public FallbackSnapshot withExecuting(Instant now) {
    return new FallbackSnapshot(
        FallbackState.EXECUTING, null, null, null, now, null, null);
  }

  /**
   * Transitions to SUCCEEDED (primary operation completed successfully).
   */
  public FallbackSnapshot withSucceeded(Instant now) {
    return new FallbackSnapshot(
        FallbackState.SUCCEEDED, null, null, null, startTime, null, now);
  }

  /**
   * Transitions to FALLING_BACK (a matching handler was found and is being invoked).
   */
  public FallbackSnapshot withFallingBack(Throwable primaryFailure, String handlerName, Instant now) {
    return new FallbackSnapshot(
        FallbackState.FALLING_BACK, primaryFailure, null, handlerName, startTime, now, null);
  }

  /**
   * Transitions to RECOVERED (fallback handler succeeded).
   */
  public FallbackSnapshot withRecovered(Instant now) {
    return new FallbackSnapshot(
        FallbackState.RECOVERED, primaryFailure, null, handlerName, startTime, fallbackStartTime, now);
  }

  /**
   * Transitions to FALLBACK_FAILED (fallback handler itself threw).
   */
  public FallbackSnapshot withFallbackFailed(Throwable fallbackFailure, Instant now) {
    return new FallbackSnapshot(
        FallbackState.FALLBACK_FAILED, primaryFailure, fallbackFailure,
        handlerName, startTime, fallbackStartTime, now);
  }

  /**
   * Transitions to UNHANDLED (no matching handler was found).
   */
  public FallbackSnapshot withUnhandled(Throwable primaryFailure, Instant now) {
    return new FallbackSnapshot(
        FallbackState.UNHANDLED, primaryFailure, null, null, startTime, null, now);
  }

  // --- Query helpers ---

  /**
   * Returns the total elapsed duration from start to end (or to now if not terminal).
   */
  public Duration elapsed(Instant now) {
    if (startTime == null) {
      return Duration.ZERO;
    }
    Instant end = endTime != null ? endTime : now;
    return Duration.between(startTime, end);
  }

  /**
   * Returns the elapsed duration of the fallback handler execution.
   */
  public Duration fallbackElapsed(Instant now) {
    if (fallbackStartTime == null) {
      return Duration.ZERO;
    }
    Instant end = endTime != null ? endTime : now;
    return Duration.between(fallbackStartTime, end);
  }

  /**
   * Returns {@code true} if a fallback handler was invoked.
   */
  public boolean fallbackInvoked() {
    return handlerName != null;
  }
}
