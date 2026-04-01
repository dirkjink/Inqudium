package eu.inqudium.imperative.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimitPermission;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterCore;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import eu.inqudium.core.ratelimiter.RateLimiterSnapshot;
import eu.inqudium.core.ratelimiter.ReservationResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative rate limiter implementation.
 */
public class ImperativeRateLimiter {

  private static final Logger LOG = Logger.getLogger(ImperativeRateLimiter.class.getName());

  private final RateLimiterConfig config;
  private final AtomicReference<RateLimiterSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<RateLimiterEvent>> eventListeners;

  // Fix 8: Unique instance identifier for identity-based comparison in executeWithFallback
  private final String instanceId;

  public ImperativeRateLimiter(RateLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRateLimiter(RateLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(RateLimiterSnapshot.initial(config, clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ======================== Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    // Fix 10: Fail fast on null
    Objects.requireNonNull(callable, "callable must not be null");
    acquirePermissionOrThrow(config.defaultTimeout());
    return callable.call();
  }

  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");
    // Fix 10: Validate timeout to prevent cryptic NPEs deep in Duration arithmetic
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative, got " + timeout);
    }
    acquirePermissionOrThrow(timeout);
    return callable.call();
  }

  /**
   * Executes with a fallback that activates when <em>this</em> rate limiter rejects the call.
   *
   * <p>Fix 8: Uses instance-based comparison ({@code instanceId}) instead of the
   * human-readable name to determine whether the exception originated from this
   * rate limiter. This prevents false positives when multiple rate limiters share
   * the same name, or when a downstream rate limiter throws.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (RateLimiterException e) {
      if (Objects.equals(e.getInstanceId(), this.instanceId)) {
        return fallback.get();
      }
      throw e;
    }
  }

  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Direct permission API ========================

  public boolean tryAcquirePermission() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimitPermission result = RateLimiterCore.tryAcquirePermission(current, config, now);

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        } else {
          emitEvent(RateLimiterEvent.rejected(
              config.name(), result.snapshot().availablePermits(), result.waitDuration(), now));
        }
        return result.permitted();
      }
    }
  }

  /**
   * Fix 3: Exposes the multi-permit acquisition from the core.
   *
   * @param permits the number of permits to acquire (must be >= 1 and <= capacity)
   * @return {@code true} if all permits were acquired
   */
  public boolean tryAcquirePermissions(int permits) {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimitPermission result = RateLimiterCore.tryAcquirePermissions(
          current, config, now, permits);

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        } else {
          emitEvent(RateLimiterEvent.rejected(
              config.name(), result.snapshot().availablePermits(), result.waitDuration(), now));
        }
        return result.permitted();
      }
    }
  }

  public void acquirePermission() {
    acquirePermissionOrThrow(config.defaultTimeout());
  }

  // ======================== Internal ========================

  private void acquirePermissionOrThrow(Duration timeout) {
    Instant start = clock.instant();
    Instant deadline = timeout.isZero() ? start : start.plus(timeout);

    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      Instant now = clock.instant();

      // Fix 2A: Recompute remaining timeout on every CAS retry to prevent
      // timeout erosion from thread contention or slow CAS loops.
      Duration remainingTimeout = timeout.isZero()
          ? Duration.ZERO
          : Duration.between(now, deadline);
      if (remainingTimeout.isNegative()) {
        remainingTimeout = Duration.ZERO;
      }

      ReservationResult reservation = RateLimiterCore.reservePermission(
          current, config, now, remainingTimeout);

      if (reservation.timedOut()) {
        emitEvent(RateLimiterEvent.rejected(
            config.name(), current.availablePermits(), reservation.waitDuration(), now));
        throw new RateLimiterException(
            config.name(), instanceId, reservation.waitDuration(), current.availablePermits());
      }

      if (snapshotRef.compareAndSet(current, reservation.snapshot())) {
        if (reservation.waitDuration().isZero()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), reservation.snapshot().availablePermits(), now));
          return;
        }

        emitEvent(RateLimiterEvent.waiting(
            config.name(), reservation.snapshot().availablePermits(),
            reservation.waitDuration(), now));

        // Remember the epoch before parking so we can detect invalidation
        long epochBeforePark = reservation.snapshot().epoch();
        Instant targetWakeup = now.plus(reservation.waitDuration());

        // Fix 9: parkUntil now handles interrupts properly
        parkUntil(targetWakeup);

        // Fix 2: After waking, verify the epoch hasn't changed (e.g. by reset/drain).
        // If it changed, the reservation from the old epoch is invalid and we must re-acquire.
        RateLimiterSnapshot currentAfterWake = snapshotRef.get();
        if (currentAfterWake.epoch() != epochBeforePark) {
          // Epoch changed — our reservation is from a previous lifecycle.
          // Loop back to re-acquire a permit in the new epoch.
          continue;
        }

        return;
      }
      // CAS failed — retry
    }
  }

  /**
   * Parks the current thread until the target time is reached.
   *
   * <p>Fix 9: Checks for thread interruption on each iteration. If the thread
   * is interrupted (e.g. by a virtual-thread cancellation or executor shutdown),
   * the method restores the interrupt flag and throws a {@link RateLimiterException}
   * instead of silently busy-waiting through the remaining duration.
   */
  private void parkUntil(Instant targetWakeupTime) {
    while (true) {
      // Fix 9: Detect and honour interrupts instead of spinning through them
      if (Thread.currentThread().isInterrupted()) {
        // Do not clear the flag — let the caller see it
        throw new RateLimiterException(
            config.name(),
            instanceId,
            Duration.between(clock.instant(), targetWakeupTime),
            snapshotRef.get().availablePermits());
      }

      Instant now = clock.instant();
      Duration remaining = Duration.between(now, targetWakeupTime);

      if (remaining.isNegative() || remaining.isZero()) {
        break; // Wait time has physically elapsed
      }

      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  // ======================== Listeners ========================

  public void onEvent(Consumer<RateLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Fix 6: Each listener is invoked in its own try-catch to ensure a failing listener
   * does not break the event chain or corrupt the rate limiter execution flow.
   */
  private void emitEvent(RateLimiterEvent event) {
    for (Consumer<RateLimiterEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for rate limiter '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), e.getMessage()),
            e);
      }
    }
  }

  // ======================== Introspection ========================

  public int getAvailablePermits() {
    return RateLimiterCore.availablePermits(snapshotRef.get(), config, clock.instant());
  }

  public RateLimiterSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  public RateLimiterConfig getConfig() {
    return config;
  }

  /**
   * Returns the unique instance identifier.
   * Useful for diagnostic logging and identity-based exception matching.
   */
  public String getInstanceId() {
    return instanceId;
  }

  public void drain() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot drained = RateLimiterCore.drain(current);
      if (snapshotRef.compareAndSet(current, drained)) {
        emitEvent(RateLimiterEvent.drained(config.name(), now));
        return;
      }
    }
  }

  /**
   * Resets the rate limiter to a full bucket.
   *
   * <p>Fix 7: Uses the epoch-incrementing reset from the core, which signals
   * to threads parked on old reservations that their permits are invalidated.
   * Those threads will detect the epoch change after waking and re-acquire.
   */
  public void reset() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimiterSnapshot fresh = RateLimiterCore.reset(current, config, now);
      if (snapshotRef.compareAndSet(current, fresh)) {
        emitEvent(RateLimiterEvent.reset(config.name(), fresh.availablePermits(), now));
        return;
      }
    }
  }
}
