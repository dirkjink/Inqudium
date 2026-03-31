package eu.inqudium.core.timelimiter;

import java.time.Duration;

/**
 * Exception thrown when an operation exceeds its configured time limit.
 */
public class TimeLimiterException extends RuntimeException {

  private final String timeLimiterName;
  private final Duration timeout;

  public TimeLimiterException(String timeLimiterName, Duration timeout) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()));
    this.timeLimiterName = timeLimiterName;
    this.timeout = timeout;
  }

  public TimeLimiterException(String timeLimiterName, Duration timeout, Throwable cause) {
    super("TimeLimiter '%s' — operation timed out after %s ms"
        .formatted(timeLimiterName, timeout.toMillis()), cause);
    this.timeLimiterName = timeLimiterName;
    this.timeout = timeout;
  }

  public String getTimeLimiterName() {
    return timeLimiterName;
  }

  /**
   * Returns the configured timeout that was exceeded.
   */
  public Duration getTimeout() {
    return timeout;
  }
}
