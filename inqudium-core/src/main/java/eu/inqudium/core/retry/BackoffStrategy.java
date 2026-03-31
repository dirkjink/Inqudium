package eu.inqudium.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy for computing the wait duration between retry attempts.
 *
 * <p>All implementations are immutable value objects. The
 * {@link #computeDelay(int)} method receives the zero-based attempt
 * index (0 = first retry, 1 = second retry, …) and returns the
 * duration to wait before the next attempt.
 */
public sealed interface BackoffStrategy {

  /**
   * Fixed delay between retries.
   */
  static BackoffStrategy fixedDelay(Duration delay) {
    return new Fixed(delay);
  }

  // ======================== Fixed ========================

  /**
   * Exponential backoff with defaults: multiplier 2.0, max 30s.
   */
  static BackoffStrategy exponential(Duration initialDelay) {
    return new Exponential(initialDelay, 2.0, Duration.ofSeconds(30));
  }

  // ======================== Exponential ========================

  /**
   * Exponential backoff with custom parameters.
   */
  static BackoffStrategy exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
    return new Exponential(initialDelay, multiplier, maxDelay);
  }

  // ======================== Exponential with Jitter ========================

  /**
   * Exponential backoff with full jitter (recommended for distributed systems).
   */
  static BackoffStrategy exponentialWithJitter(Duration initialDelay) {
    return new ExponentialWithJitter(initialDelay, 2.0, Duration.ofSeconds(30));
  }

  // ======================== No Wait ========================

  /**
   * Exponential backoff with jitter and custom parameters.
   */
  static BackoffStrategy exponentialWithJitter(Duration initialDelay, double multiplier, Duration maxDelay) {
    return new ExponentialWithJitter(initialDelay, multiplier, maxDelay);
  }

  // ======================== Factory methods ========================

  /**
   * No delay between retries.
   */
  static BackoffStrategy noWait() {
    return new NoWait();
  }

  /**
   * Computes the delay before the given retry attempt.
   *
   * @param attemptIndex zero-based retry index (0 = first retry after initial failure)
   * @return the duration to wait
   */
  Duration computeDelay(int attemptIndex);

  /**
   * Constant wait duration between every retry attempt.
   *
   * @param delay the fixed delay between attempts
   */
  record Fixed(Duration delay) implements BackoffStrategy {

    public Fixed {
      Objects.requireNonNull(delay, "delay must not be null");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must not be negative");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      return delay;
    }
  }

  /**
   * Exponentially increasing wait duration: {@code initialDelay * multiplier^attemptIndex},
   * capped at {@code maxDelay}.
   *
   * @param initialDelay the delay before the first retry
   * @param multiplier   the factor by which delay grows each attempt
   * @param maxDelay     ceiling for the computed delay
   */
  record Exponential(Duration initialDelay, double multiplier, Duration maxDelay) implements BackoffStrategy {

    public Exponential {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative() || initialDelay.isZero()) {
        throw new IllegalArgumentException("initialDelay must be positive");
      }
      if (multiplier < 1.0) {
        throw new IllegalArgumentException("multiplier must be >= 1.0, got " + multiplier);
      }
      if (maxDelay.isNegative() || maxDelay.isZero()) {
        throw new IllegalArgumentException("maxDelay must be positive");
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      double delayMillis = initialDelay.toMillis() * Math.pow(multiplier, attemptIndex);
      long cappedMillis = Math.min((long) delayMillis, maxDelay.toMillis());
      return Duration.ofMillis(cappedMillis);
    }
  }

  /**
   * Exponential backoff with randomised jitter to avoid thundering-herd
   * problems. The actual delay is uniformly distributed in
   * {@code [0, computedExponentialDelay]}.
   *
   * @param initialDelay the base delay before the first retry
   * @param multiplier   the factor by which delay grows each attempt
   * @param maxDelay     ceiling for the computed delay (before jitter)
   */
  record ExponentialWithJitter(Duration initialDelay, double multiplier, Duration maxDelay) implements BackoffStrategy {

    public ExponentialWithJitter {
      Objects.requireNonNull(initialDelay, "initialDelay must not be null");
      Objects.requireNonNull(maxDelay, "maxDelay must not be null");
      if (initialDelay.isNegative() || initialDelay.isZero()) {
        throw new IllegalArgumentException("initialDelay must be positive");
      }
      if (multiplier < 1.0) {
        throw new IllegalArgumentException("multiplier must be >= 1.0, got " + multiplier);
      }
    }

    @Override
    public Duration computeDelay(int attemptIndex) {
      double delayMillis = initialDelay.toMillis() * Math.pow(multiplier, attemptIndex);
      long cappedMillis = Math.min((long) delayMillis, maxDelay.toMillis());
      // Full jitter: uniform random in [0, cappedMillis]
      long jitteredMillis = cappedMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(cappedMillis + 1);
      return Duration.ofMillis(jitteredMillis);
    }
  }

  /**
   * Zero delay — retry immediately without any wait.
   */
  record NoWait() implements BackoffStrategy {

    @Override
    public Duration computeDelay(int attemptIndex) {
      return Duration.ZERO;
    }
  }
}
