package eu.inqudium.core.bulkhead;

import java.time.Duration;

public interface BlockingBulkheadStateMachine extends BulkheadStateMachine {
  /**
   * Attempts to acquire a permit, potentially blocking the thread up to the timeout.
   * This is strictly for imperative paradigms.
   * * @param callId the unique call identifier
   *
   * @param timeout the maximum duration to wait
   * @return {@code true} if acquired, {@code false} if full
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  boolean tryAcquire(String callId, Duration timeout) throws InterruptedException;
}
