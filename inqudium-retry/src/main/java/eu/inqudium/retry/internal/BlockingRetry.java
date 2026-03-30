package eu.inqudium.retry.internal;

import eu.inqudium.core.retry.AbstractRetry;
import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.retry.Retry;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Imperative retry using {@link LockSupport#parkNanos} for blocking backoff waits (ADR-008).
 *
 * <p>All retry logic, event publishing, and exception handling live in
 * {@link AbstractRetry}. This class only provides the blocking wait mechanism.
 *
 * <p>Virtual-thread safe — {@link LockSupport#parkNanos} does not pin carrier threads.
 *
 * @since 0.1.0
 */
public final class BlockingRetry extends AbstractRetry implements Retry {

  public BlockingRetry(String name, RetryConfig config) {
    super(name, config);
  }

  @Override
  protected void waitBeforeRetry(Duration duration) {
    LockSupport.parkNanos(duration.toNanos());
  }
}
