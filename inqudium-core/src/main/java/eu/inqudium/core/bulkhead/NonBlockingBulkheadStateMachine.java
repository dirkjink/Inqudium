package eu.inqudium.core.bulkhead;

public interface NonBlockingBulkheadStateMachine extends BulkheadStateMachine {
  /**
   * Attempts to acquire a permit immediately without blocking.
   * This is used by reactive/coroutine paradigms that cannot block the thread.
   * * @param callId the unique call identifier for tracing/events
   *
   * @return {@code true} if acquired, {@code false} if full
   */
  boolean tryAcquire(String callId);
}
