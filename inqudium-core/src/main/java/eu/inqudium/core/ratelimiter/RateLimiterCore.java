package eu.inqudium.core.ratelimiter;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure functional core of the token-bucket rate limiter.
 */
public final class RateLimiterCore {

  private RateLimiterCore() {
    // Utility class — not instantiable
  }

  // ======================== Refill ========================

  public static RateLimiterSnapshot refill(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    long elapsedNanos = Duration.between(snapshot.lastRefillTime(), now).toNanos();
    if (elapsedNanos <= 0) {
      return snapshot;
    }

    long periodNanos = config.refillPeriod().toNanos();
    long completePeriods = elapsedNanos / periodNanos;

    if (completePeriods <= 0) {
      return snapshot;
    }

    Instant newRefillTime = snapshot.lastRefillTime()
        .plusNanos(completePeriods * periodNanos);

    // Fix 4: Early exit when the refill would fill or overflow the bucket.
    // Avoids unnecessary arithmetic for long inactivity periods (e.g. weeks)
    // where completePeriods * refillPermits easily exceeds capacity.
    long tokensToAdd = completePeriods * config.refillPermits();
    if (tokensToAdd >= (long) config.capacity() - Math.min(snapshot.availablePermits(), 0)) {
      // Bucket is guaranteed to be full — no need to compute the exact sum
      return snapshot.withRefill(config.capacity(), newRefillTime);
    }

    int newPermits = (int) Math.min(
        (long) snapshot.availablePermits() + tokensToAdd,
        config.capacity()
    );

    return snapshot.withRefill(newPermits, newRefillTime);
  }

  // ======================== Permission (fail-fast) ========================

  public static RateLimitPermission tryAcquirePermission(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() > 0) {
      return RateLimitPermission.permitted(refilled.withPermitConsumed());
    }

    Duration waitDuration = estimateWaitDuration(refilled, config, now);
    return RateLimitPermission.rejected(refilled, waitDuration);
  }

  public static RateLimitPermission tryAcquirePermissions(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now,
      int permits) {

    if (permits < 1) {
      throw new IllegalArgumentException("permits must be >= 1, got " + permits);
    }
    if (permits > config.capacity()) {
      throw new IllegalArgumentException(
          "permits (%d) exceeds capacity (%d)".formatted(permits, config.capacity()));
    }

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() >= permits) {
      return RateLimitPermission.permitted(
          refilled.withAvailablePermits(refilled.availablePermits() - permits));
    }

    int deficit = permits - refilled.availablePermits();
    Duration waitDuration = estimateWaitForPermits(config, deficit);
    return RateLimitPermission.rejected(refilled, waitDuration);
  }

  // ======================== Reservation (wait-capable) ========================

  public static ReservationResult reservePermission(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now,
      Duration timeout) {

    RateLimiterSnapshot refilled = refill(snapshot, config, now);

    if (refilled.availablePermits() > 0) {
      return ReservationResult.immediate(refilled.withPermitConsumed());
    }

    Duration waitDuration = estimateWaitDuration(refilled, config, now);

    if (timeout.isZero() || waitDuration.compareTo(timeout) > 0) {
      return ReservationResult.timedOut(refilled, waitDuration);
    }

    // Fix 1: Enforce a debt floor to prevent unbounded negative permits.
    // Without this guard, concurrent delayed reservations can drive availablePermits
    // to arbitrarily negative values, causing ever-growing wait times and
    // rendering the rate limiter effectively unusable after a burst.
    // The floor is -capacity, allowing at most 'capacity' queued reservations.
    int debtFloor = -config.capacity();
    if (refilled.availablePermits() <= debtFloor) {
      return ReservationResult.timedOut(refilled, waitDuration);
    }

    RateLimiterSnapshot consumed = refilled.withAvailablePermits(
        refilled.availablePermits() - 1);
    return ReservationResult.delayed(consumed, waitDuration);
  }

  // ======================== Drain & Reset ========================

  public static RateLimiterSnapshot drain(RateLimiterSnapshot snapshot) {
    return snapshot.withAvailablePermits(0);
  }

  /**
   * Resets the rate limiter to a fresh state.
   *
   * <p>Fix 2/7: Accepts the current snapshot so the epoch can be incremented.
   * Pending reservations from the old epoch are invalidated — the wrapper
   * checks the epoch after parking and re-acquires if it changed.
   *
   * @param current the current snapshot (used to derive the next epoch)
   * @param config  the rate limiter configuration
   * @param now     the current timestamp
   * @return a fresh snapshot with a full bucket and incremented epoch
   */
  public static RateLimiterSnapshot reset(
      RateLimiterSnapshot current,
      RateLimiterConfig config,
      Instant now) {
    return current.withNextEpoch(config.capacity(), now);
  }

  // ======================== Query helpers ========================

  public static int availablePermits(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    return refill(snapshot, config, now).availablePermits();
  }

  public static Duration estimateWaitDuration(
      RateLimiterSnapshot snapshot,
      RateLimiterConfig config,
      Instant now) {

    if (snapshot.availablePermits() > 0) {
      return Duration.ZERO;
    }

    // Account for bucket debt: if permits are at -5, we need 6 refilled permits to reach +1
    int deficit = 1 - snapshot.availablePermits();
    return estimateWaitForPermits(config, deficit);
  }

  static Duration estimateWaitForPermits(RateLimiterConfig config, int permits) {
    if (permits <= 0) {
      return Duration.ZERO;
    }
    long cyclesNeeded = ((long) permits + config.refillPermits() - 1) / config.refillPermits();
    return config.refillPeriod().multipliedBy(cyclesNeeded);
  }
}
