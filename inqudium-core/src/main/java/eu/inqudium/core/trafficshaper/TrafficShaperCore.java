package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure functional core of the traffic shaper (leaky bucket scheduler).
 *
 * <p>All methods are static and side-effect-free. They accept the current
 * immutable {@link ThrottleSnapshot} and return a new snapshot reflecting
 * the scheduling decision. No synchronization, no I/O, no mutation.
 *
 * <h2>Leaky Bucket Scheduling Algorithm</h2>
 * <p>The core maintains a virtual timeline of evenly-spaced slots. Each
 * incoming request is assigned the next available slot:
 *
 * <pre>
 *   Input:   ──||||||───────||──||──────►  (bursty arrivals)
 *   Output:  ──|──|──|──|──|──|──|──|──►  (smooth, evenly-spaced)
 *                ↑ interval between slots
 * </pre>
 *
 * <p>When a request arrives at time {@code now}:
 * <ol>
 *   <li>If {@code nextFreeSlot <= now}: the request proceeds immediately,
 *       and {@code nextFreeSlot} is set to {@code now + interval}.</li>
 *   <li>If {@code nextFreeSlot > now}: the request must wait until
 *       {@code nextFreeSlot}, and the slot advances by {@code interval}.</li>
 *   <li>If the computed wait exceeds {@code maxWaitDuration} or the queue
 *       depth exceeds {@code maxQueueDepth}, the request is rejected
 *       (in {@link ThrottleMode#SHAPE_AND_REJECT_OVERFLOW} mode).</li>
 * </ol>
 *
 * <p>This differs from a token-bucket rate limiter in a key way:
 * a rate limiter <em>permits bursts</em> up to the bucket capacity and then
 * rejects; a traffic shaper <em>smooths bursts</em> by delaying requests
 * so they proceed at a constant rate.
 *
 * <p>This design allows the same core logic to be shared between an
 * imperative wrapper (virtual threads + {@code LockSupport.parkNanos})
 * and a reactive wrapper (Project Reactor + {@code Mono.delay}).
 */
public final class TrafficShaperCore {

  private TrafficShaperCore() {
    // Utility class — not instantiable
  }

  // ======================== Scheduling ========================

  /**
   * Schedules a request and returns the throttling decision.
   *
   * <p>This is the central function. It evaluates whether the request
   * can be admitted (immediately or after a delay) or must be rejected.
   *
   * @param snapshot the current shared scheduling state
   * @param config   the traffic shaper configuration
   * @param now      the current timestamp (arrival time of the request)
   * @return a throttle permission with the updated snapshot
   */
  public static ThrottlePermission schedule(
      ThrottleSnapshot snapshot,
      TrafficShaperConfig config,
      Instant now) {

    // Reclaim past credits: if the next free slot is in the past and
    // nobody is queued, reset it to now (prevents accumulating "burst credit")
    ThrottleSnapshot effective = reclaimSlot(snapshot, now);

    // Compute the wait: time from now until the assigned slot
    Duration waitDuration = effective.waitDurationFor(now);

    // Check overflow conditions (only in SHAPE_AND_REJECT_OVERFLOW mode)
    if (config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      if (shouldReject(effective, config, waitDuration)) {
        return ThrottlePermission.rejected(
            effective.withRequestRejected(), waitDuration);
      }
    }

    // Admit the request: assign the current nextFreeSlot as its execution slot
    Instant assignedSlot = effective.nextFreeSlot();

    // Advance the scheduling timeline
    ThrottleSnapshot updated = effective.withRequestScheduled(config.interval());

    if (waitDuration.isZero()) {
      return ThrottlePermission.immediate(updated, assignedSlot);
    }
    return ThrottlePermission.delayed(updated, waitDuration, assignedSlot);
  }

  /**
   * Records that a previously queued request has left the queue and
   * started executing. Decrements the queue depth.
   *
   * @param snapshot the current snapshot
   * @return the updated snapshot with decremented queue depth
   */
  public static ThrottleSnapshot recordExecution(ThrottleSnapshot snapshot) {
    return snapshot.withRequestDequeued();
  }

  // ======================== Overflow Detection ========================

  /**
   * Determines whether a request should be rejected based on queue depth
   * and wait duration limits.
   */
  static boolean shouldReject(
      ThrottleSnapshot snapshot,
      TrafficShaperConfig config,
      Duration waitDuration) {

    // Reject if queue depth exceeds the limit
    if (config.maxQueueDepth() > 0 && snapshot.queueDepth() >= config.maxQueueDepth()) {
      return true;
    }

    // Reject if the wait would exceed the max wait duration
    if (!config.maxWaitDuration().isZero() && waitDuration.compareTo(config.maxWaitDuration()) > 0) {
      return true;
    }

    return false;
  }

  // ======================== Slot Reclamation ========================

  /**
   * Reclaims unused time slots when the scheduling timeline has fallen
   * behind the current time and no requests are queued.
   *
   * <p>Without reclamation, a traffic shaper that was idle for 10 seconds
   * would allow the next 10 seconds' worth of requests to pass without
   * delay, defeating the purpose of traffic shaping. By resetting
   * {@code nextFreeSlot} to {@code now}, we ensure that even after idle
   * periods, requests are still paced at the configured rate.
   *
   * @param snapshot the current snapshot
   * @param now      the current time
   * @return the snapshot with the slot reclaimed (or unchanged)
   */
  public static ThrottleSnapshot reclaimSlot(ThrottleSnapshot snapshot, Instant now) {
    if (snapshot.queueDepth() == 0 && snapshot.nextFreeSlot().isBefore(now)) {
      return snapshot.withNextFreeSlot(now);
    }
    return snapshot;
  }

  // ======================== Query Helpers ========================

  /**
   * Returns the current queue depth.
   */
  public static int queueDepth(ThrottleSnapshot snapshot) {
    return snapshot.queueDepth();
  }

  /**
   * Returns the estimated wait time for a request arriving now.
   * Does <strong>not</strong> modify the snapshot.
   */
  public static Duration estimateWait(ThrottleSnapshot snapshot, Instant now) {
    ThrottleSnapshot effective = reclaimSlot(snapshot, now);
    return effective.waitDurationFor(now);
  }

  /**
   * Returns the current effective throughput based on the configured rate.
   */
  public static double currentRatePerSecond(TrafficShaperConfig config) {
    return config.ratePerSecond();
  }

  /**
   * Resets the traffic shaper to its initial state.
   */
  public static ThrottleSnapshot reset(Instant now) {
    return ThrottleSnapshot.initial(now);
  }
}
