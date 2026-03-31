package eu.inqudium.core.retry;

import java.util.List;

/**
 * Exception thrown when all retry attempts have been exhausted.
 *
 * <p>The {@link #getCause()} returns the last recorded failure.
 * All failures are available via {@link #getFailures()}.
 */
public class RetryException extends RuntimeException {

  private final String retryName;
  private final int attempts;
  private final List<Throwable> failures;

  public RetryException(String retryName, int attempts, Throwable lastFailure, List<Throwable> failures) {
    super("Retry '%s' exhausted all %d attempts. Last failure: %s"
        .formatted(retryName, attempts, lastFailure.getMessage()), lastFailure);
    this.retryName = retryName;
    this.attempts = attempts;
    this.failures = List.copyOf(failures);
  }

  public String getRetryName() {
    return retryName;
  }

  /**
   * Returns the total number of attempts made.
   */
  public int getAttempts() {
    return attempts;
  }

  /**
   * Returns all recorded failures in order.
   */
  public List<Throwable> getFailures() {
    return failures;
  }
}
