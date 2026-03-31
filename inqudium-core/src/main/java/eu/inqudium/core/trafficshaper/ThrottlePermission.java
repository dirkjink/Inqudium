package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.time.Instant;

/**
 * Result of scheduling a request through the traffic shaper.
 *
 * <p>Unlike a rate limiter which is binary (permit/reject), the traffic
 * shaper has three outcomes:
 * <ul>
 *   <li><strong>Immediate</strong>: the request may proceed now (zero wait).</li>
 *   <li><strong>Delayed</strong>: the request is admitted but must wait for its slot.</li>
 *   <li><strong>Rejected</strong>: the queue is full or wait would exceed the limit.</li>
 * </ul>
 *
 * @param snapshot      the updated snapshot (with this request's scheduling applied)
 * @param admitted      whether the request was admitted (immediate or delayed)
 * @param waitDuration  how long the caller must wait (zero if immediate or rejected)
 * @param scheduledSlot the absolute instant at which the request may proceed
 *                      ({@code null} if rejected)
 */
public record ThrottlePermission(
    ThrottleSnapshot snapshot,
    boolean admitted,
    Duration waitDuration,
    Instant scheduledSlot
) {

  /**
   * The request may proceed immediately — no wait required.
   */
  public static ThrottlePermission immediate(ThrottleSnapshot snapshot, Instant slot) {
    return new ThrottlePermission(snapshot, true, Duration.ZERO, slot);
  }

  /**
   * The request is admitted but must wait for its scheduled slot.
   */
  public static ThrottlePermission delayed(ThrottleSnapshot snapshot, Duration waitDuration, Instant slot) {
    return new ThrottlePermission(snapshot, true, waitDuration, slot);
  }

  /**
   * The request is rejected — queue is full or wait would exceed the limit.
   */
  public static ThrottlePermission rejected(ThrottleSnapshot snapshot, Duration wouldHaveWaited) {
    return new ThrottlePermission(snapshot, false, wouldHaveWaited, null);
  }

  /**
   * Returns {@code true} if the request requires a non-zero wait.
   */
  public boolean requiresWait() {
    return admitted && !waitDuration.isZero();
  }
}
