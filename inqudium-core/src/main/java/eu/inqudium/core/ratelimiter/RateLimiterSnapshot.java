package eu.inqudium.core.ratelimiter;

import java.time.Instant;

/**
 * Immutable snapshot of the rate limiter's internal state.
 *
 * <p>This is the central data structure of the functional core.
 * All state transitions produce a new snapshot rather than mutating in place.
 *
 * <p>The token bucket model works as follows: {@code availablePermits}
 * tracks the current fill level. On each permission check the core first
 * refills tokens based on elapsed time since {@code lastRefillTime}, then
 * checks whether a token can be consumed.
 *
 * @param availablePermits current number of permits in the bucket
 * @param lastRefillTime   timestamp of the last (possibly virtual) refill
 * @param epoch            monotonically increasing generation counter; incremented
 *                         on {@link RateLimiterCore#reset} to invalidate pending
 *                         reservations from a previous lifecycle (Fix 2/7)
 */
public record RateLimiterSnapshot(
    int availablePermits,
    Instant lastRefillTime,
    long epoch
) {

  /**
   * Creates the initial snapshot with a full bucket at epoch 0.
   */
  public static RateLimiterSnapshot initial(RateLimiterConfig config, Instant now) {
    return new RateLimiterSnapshot(config.capacity(), now, 0L);
  }

  // --- Wither methods for immutable updates ---

  public RateLimiterSnapshot withAvailablePermits(int permits) {
    return new RateLimiterSnapshot(permits, lastRefillTime, epoch);
  }

  public RateLimiterSnapshot withLastRefillTime(Instant time) {
    return new RateLimiterSnapshot(availablePermits, time, epoch);
  }

  public RateLimiterSnapshot withPermitConsumed() {
    return new RateLimiterSnapshot(availablePermits - 1, lastRefillTime, epoch);
  }

  public RateLimiterSnapshot withRefill(int newPermits, Instant newRefillTime) {
    return new RateLimiterSnapshot(newPermits, newRefillTime, epoch);
  }

  /**
   * Fix 2/7: Creates a fresh snapshot with the epoch incremented.
   * Used by reset to signal that all prior reservations are invalidated.
   */
  public RateLimiterSnapshot withNextEpoch(int permits, Instant now) {
    return new RateLimiterSnapshot(permits, now, epoch + 1);
  }
}
