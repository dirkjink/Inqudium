package eu.inqudium.core.trafficshaper;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a traffic shaper instance.
 *
 * <p>The traffic shaper uses a <strong>leaky bucket scheduler</strong>:
 * incoming requests are assigned successive time slots spaced
 * {@link #interval()} apart. Each request is delayed until its slot
 * arrives, producing smooth, evenly-spaced output traffic regardless
 * of how bursty the input is.
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name            a human-readable identifier
 * @param ratePerSecond   the target throughput in requests per second
 * @param interval        the computed interval between successive slots
 *                        ({@code Duration.ofNanos(1_000_000_000 / ratePerSecond)})
 * @param maxQueueDepth   maximum number of requests that may be waiting
 *                        simultaneously; exceeded → reject (unless unbounded)
 * @param maxWaitDuration maximum time a single request may wait before
 *                        being rejected (acts as a hard cap on queue depth)
 * @param throttleMode    how to handle overflow (reject vs. unbounded)
 */
public record TrafficShaperConfig(
    String name,
    double ratePerSecond,
    Duration interval,
    int maxQueueDepth,
    Duration maxWaitDuration,
    ThrottleMode throttleMode
) {

  public TrafficShaperConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(interval, "interval must not be null");
    Objects.requireNonNull(maxWaitDuration, "maxWaitDuration must not be null");
    Objects.requireNonNull(throttleMode, "throttleMode must not be null");
    if (ratePerSecond <= 0) {
      throw new IllegalArgumentException("ratePerSecond must be positive, got " + ratePerSecond);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive");
    }
    if (maxQueueDepth < 0) {
      throw new IllegalArgumentException("maxQueueDepth must be >= 0, got " + maxQueueDepth);
    }
    if (maxWaitDuration.isNegative()) {
      throw new IllegalArgumentException("maxWaitDuration must not be negative");
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private double ratePerSecond = 10.0;
    private int maxQueueDepth = 50;
    private Duration maxWaitDuration = Duration.ofSeconds(10);
    private ThrottleMode throttleMode = ThrottleMode.SHAPE_AND_REJECT_OVERFLOW;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    /**
     * Sets the target throughput in requests per second.
     * The interval between slots is computed automatically.
     */
    public Builder ratePerSecond(double ratePerSecond) {
      this.ratePerSecond = ratePerSecond;
      return this;
    }

    /**
     * Convenience: sets the rate from a count and a period.
     * E.g. {@code rateForPeriod(100, Duration.ofMinutes(1))} = 100 req/min.
     */
    public Builder rateForPeriod(int count, Duration period) {
      this.ratePerSecond = (double) count / ((double) period.toNanos() / 1_000_000_000.0);
      return this;
    }

    /**
     * Maximum number of requests that may be queued waiting for their slot.
     * Requests beyond this depth are rejected (in SHAPE_AND_REJECT_OVERFLOW mode).
     */
    public Builder maxQueueDepth(int maxQueueDepth) {
      this.maxQueueDepth = maxQueueDepth;
      return this;
    }

    /**
     * Maximum time any single request may wait. Requests whose computed
     * wait exceeds this are rejected regardless of queue depth.
     */
    public Builder maxWaitDuration(Duration maxWaitDuration) {
      this.maxWaitDuration = maxWaitDuration;
      return this;
    }

    public Builder throttleMode(ThrottleMode throttleMode) {
      this.throttleMode = throttleMode;
      return this;
    }

    public TrafficShaperConfig build() {
      long intervalNanos = (long) (1_000_000_000.0 / ratePerSecond);
      Duration interval = Duration.ofNanos(Math.max(intervalNanos, 1));
      return new TrafficShaperConfig(
          name, ratePerSecond, interval, maxQueueDepth, maxWaitDuration, throttleMode);
    }
  }
}
