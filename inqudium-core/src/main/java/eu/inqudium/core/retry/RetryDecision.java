package eu.inqudium.core.retry;

import java.time.Duration;

/**
 * The decision made by the retry core after an attempt fails.
 *
 * <p>This sealed hierarchy represents the three possible outcomes:
 * <ul>
 *   <li>{@link DoRetry} — a retry should be attempted after the given delay.</li>
 *   <li>{@link DoNotRetry} — the exception is not retryable; propagate it.</li>
 *   <li>{@link RetriesExhausted} — all attempts consumed; propagate the last exception.</li>
 * </ul>
 */
public sealed interface RetryDecision {

  /**
   * Returns the updated snapshot reflecting this decision.
   */
  RetrySnapshot snapshot();

  /**
   * Retry after the given delay.
   *
   * @param snapshot   the updated snapshot (in WAITING_FOR_RETRY state)
   * @param delay      the backoff delay before the next attempt
   * @param retryIndex the zero-based index of this retry (0 = first retry)
   */
  record DoRetry(RetrySnapshot snapshot, Duration delay, int retryIndex) implements RetryDecision {
  }

  /**
   * Do not retry — the exception is not retryable.
   *
   * @param snapshot the updated snapshot (in FAILED state)
   * @param failure  the non-retryable exception
   */
  record DoNotRetry(RetrySnapshot snapshot, Throwable failure) implements RetryDecision {
  }

  /**
   * All retries exhausted — propagate the last failure.
   *
   * @param snapshot the updated snapshot (in EXHAUSTED state)
   * @param failure  the last recorded failure
   */
  record RetriesExhausted(RetrySnapshot snapshot, Throwable failure) implements RetryDecision {
  }
}
