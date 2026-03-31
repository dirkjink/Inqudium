package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable snapshot of the traffic shaper's shared scheduling state.
 *
 * <p>The traffic shaper maintains a virtual timeline of scheduled slots.
 * {@code nextFreeSlot} is the earliest instant at which the next request
 * may proceed. Each admitted request advances this timestamp by the
 * configured interval, creating an evenly-spaced output stream.
 *
 * <pre>
 *   Timeline:  ──|──|──|──|──|──|──►
 *                S1 S2 S3 S4 S5  ← scheduled slots
 *                         ▲
 *                    nextFreeSlot
 * </pre>
 *
 * @param nextFreeSlot  the earliest instant the next request can proceed
 * @param queueDepth    number of requests currently waiting for their slot
 * @param totalAdmitted total number of requests admitted since creation
 * @param totalRejected total number of requests rejected since creation
 */
public record ThrottleSnapshot(
    Instant nextFreeSlot,
    int queueDepth,
    long totalAdmitted,
    long totalRejected
) {

  /**
   * Creates the initial snapshot — the first slot is immediately available.
   */
  public static ThrottleSnapshot initial(Instant now) {
    return new ThrottleSnapshot(now, 0, 0, 0);
  }

  // --- Wither methods for immutable updates ---

  /**
   * Schedules a request: advances the next free slot by the given interval
   * and increments the queue depth.
   */
  public ThrottleSnapshot withRequestScheduled(Duration interval) {
    return new ThrottleSnapshot(
        nextFreeSlot.plus(interval),
        queueDepth + 1,
        totalAdmitted + 1,
        totalRejected
    );
  }

  /**
   * Records that a queued request has started executing (left the queue).
   */
  public ThrottleSnapshot withRequestDequeued() {
    return new ThrottleSnapshot(
        nextFreeSlot,
        Math.max(0, queueDepth - 1),
        totalAdmitted,
        totalRejected
    );
  }

  /**
   * Records a rejected request (does not affect the scheduling timeline).
   */
  public ThrottleSnapshot withRequestRejected() {
    return new ThrottleSnapshot(nextFreeSlot, queueDepth, totalAdmitted, totalRejected + 1);
  }

  /**
   * Resets the next free slot to the given time (used when the slot is in
   * the past and no one is queued, to avoid accumulating "credit").
   */
  public ThrottleSnapshot withNextFreeSlot(Instant slot) {
    return new ThrottleSnapshot(slot, queueDepth, totalAdmitted, totalRejected);
  }

  // --- Query helpers ---

  /**
   * Returns the wait duration for a request arriving at the given instant.
   * Returns {@link Duration#ZERO} if the next slot is in the past or at {@code now}.
   */
  public Duration waitDurationFor(Instant now) {
    Duration wait = Duration.between(now, nextFreeSlot);
    return wait.isNegative() ? Duration.ZERO : wait;
  }
}
