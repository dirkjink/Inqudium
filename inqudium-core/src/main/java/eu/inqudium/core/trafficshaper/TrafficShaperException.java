package eu.inqudium.core.trafficshaper;

import java.time.Duration;

/**
 * Exception thrown when the traffic shaper rejects a request because
 * the scheduling queue is full or the required wait would exceed the
 * configured maximum.
 */
public class TrafficShaperException extends RuntimeException {

  private final String trafficShaperName;
  private final Duration wouldHaveWaited;
  private final int queueDepth;

  public TrafficShaperException(String trafficShaperName, Duration wouldHaveWaited, int queueDepth) {
    super("TrafficShaper '%s' — request rejected, queue depth %d, would have waited %s ms"
        .formatted(trafficShaperName, queueDepth, wouldHaveWaited.toMillis()));
    this.trafficShaperName = trafficShaperName;
    this.wouldHaveWaited = wouldHaveWaited;
    this.queueDepth = queueDepth;
  }

  public String getTrafficShaperName() {
    return trafficShaperName;
  }

  /**
   * Returns how long the request would have needed to wait
   * if it had been admitted.
   */
  public Duration getWouldHaveWaited() {
    return wouldHaveWaited;
  }

  /**
   * Returns the queue depth at the time of rejection.
   */
  public int getQueueDepth() {
    return queueDepth;
  }
}
