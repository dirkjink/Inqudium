package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.time.Instant;

/**
 * Event emitted by the traffic shaper for observability.
 *
 * @param name         the traffic shaper name
 * @param type         the event type
 * @param waitDuration the delay assigned to this request (zero for immediate)
 * @param queueDepth   the queue depth after this event
 * @param timestamp    when the event occurred
 */
public record TrafficShaperEvent(
    String name,
    Type type,
    Duration waitDuration,
    int queueDepth,
    Instant timestamp
) {

  public static TrafficShaperEvent admittedImmediate(String name, int queueDepth, Instant now) {
    return new TrafficShaperEvent(name, Type.ADMITTED_IMMEDIATE, Duration.ZERO, queueDepth, now);
  }

  public static TrafficShaperEvent admittedDelayed(String name, Duration wait, int queueDepth, Instant now) {
    return new TrafficShaperEvent(name, Type.ADMITTED_DELAYED, wait, queueDepth, now);
  }

  public static TrafficShaperEvent rejected(String name, Duration wouldHaveWaited, int queueDepth, Instant now) {
    return new TrafficShaperEvent(name, Type.REJECTED, wouldHaveWaited, queueDepth, now);
  }

  public static TrafficShaperEvent executing(String name, int queueDepth, Instant now) {
    return new TrafficShaperEvent(name, Type.EXECUTING, Duration.ZERO, queueDepth, now);
  }

  public static TrafficShaperEvent reset(String name, Instant now) {
    return new TrafficShaperEvent(name, Type.RESET, Duration.ZERO, 0, now);
  }

  @Override
  public String toString() {
    return "TrafficShaper '%s': %s — wait %s ms, queue %d at %s"
        .formatted(name, type, waitDuration.toMillis(), queueDepth, timestamp);
  }

  public enum Type {
    /**
     * A request was admitted and will proceed immediately.
     */
    ADMITTED_IMMEDIATE,
    /**
     * A request was admitted but must wait for its slot.
     */
    ADMITTED_DELAYED,
    /**
     * A request was rejected because the queue is full or wait too long.
     */
    REJECTED,
    /**
     * A previously delayed request has finished waiting and started executing.
     */
    EXECUTING,
    /**
     * The traffic shaper was reset.
     */
    RESET
  }
}
