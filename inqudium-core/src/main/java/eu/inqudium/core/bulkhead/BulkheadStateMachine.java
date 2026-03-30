package eu.inqudium.core.bulkhead;

import eu.inqudium.core.event.InqEventPublisher;

import java.time.Duration;

/**
 * The paradigm-agnostic state machine of a bulkhead.
 * * <p>It handles permit counting, telemetry (events), and adaptive limit calculations.
 * It strictly DOES NOT handle execution, thread-blocking, or exception catching.
 * * @since 0.2.0
 */
public interface BulkheadStateMachine {

  InqEventPublisher getEventPublisher();

  int getMaxConcurrentCalls();

  /**
   * Releases a previously acquired permit and reports the execution metrics.
   * * @param callId the unique call identifier
   *
   * @param rtt   the exact Round-Trip-Time measured by the paradigm
   * @param error the business exception thrown by the call (or null if successful)
   */
  void releaseAndReport(String callId, Duration rtt, Throwable error);

  int getAvailablePermits();

  int getConcurrentCalls();
}
