package eu.inqudium.ratelimiter;

import eu.inqudium.core.ratelimiter.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative rate limiter implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom) or traditional
 * platform threads. Uses lock-free CAS operations on an
 * {@link AtomicReference} to manage state, avoiding pinning of virtual threads
 * on monitors.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><strong>Fail-fast</strong> ({@code defaultTimeout = Duration.ZERO}):
 *       throws {@link RateLimiterException} immediately when no permits
 *       are available.</li>
 *   <li><strong>Blocking wait</strong> ({@code defaultTimeout > 0}):
 *       parks the calling (virtual) thread using {@link LockSupport#parkNanos}
 *       until a permit becomes available or the timeout expires.</li>
 * </ul>
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link RateLimiterCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = RateLimiterConfig.builder("api-limiter")
 *     .limitForPeriod(100, Duration.ofSeconds(1))
 *     .defaultTimeout(Duration.ofMillis(500))
 *     .build();
 *
 * var limiter = new ImperativeRateLimiter(config);
 *
 * // Blocking (waits up to 500ms if rate-limited)
 * String result = limiter.execute(() -> httpClient.call());
 *
 * // Fail-fast check
 * if (limiter.tryAcquirePermission()) {
 *     doWork();
 * }
 *
 * // With fallback
 * String result = limiter.executeWithFallback(
 *     () -> httpClient.call(),
 *     () -> "rate-limited-fallback"
 * );
 * }</pre>
 */
public class ImperativeRateLimiter {

  private final RateLimiterConfig config;
  private final AtomicReference<RateLimiterSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<RateLimiterEvent>> eventListeners;

  public ImperativeRateLimiter(RateLimiterConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRateLimiter(RateLimiterConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(RateLimiterSnapshot.initial(config, clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  /**
   * Executes the given callable, acquiring a permit first.
   *
   * <p>If no permit is available and a timeout is configured, the calling
   * thread is parked (virtual-thread-friendly) until a permit becomes
   * available or the timeout expires.
   *
   * @param callable the operation to protect
   * @param <T>      the return type
   * @return the result of the callable
   * @throws RateLimiterException if no permit is available within the timeout
   * @throws Exception            if the callable itself throws
   */
  public <T> T execute(Callable<T> callable) throws Exception {
    acquirePermissionOrThrow(config.defaultTimeout());
    return callable.call();
  }

  /**
   * Executes with a custom timeout override.
   */
  public <T> T execute(Callable<T> callable, Duration timeout) throws Exception {
    acquirePermissionOrThrow(timeout);
    return callable.call();
  }

  /**
   * Executes the given callable with a fallback when rate-limited.
   *
   * @param callable the primary operation
   * @param fallback the fallback supplier invoked when no permit is available
   * @param <T>      the return type
   * @return the result of the callable or the fallback
   * @throws Exception if the callable throws
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (RateLimiterException e) {
      return fallback.get();
    }
  }

  /**
   * Executes a {@link Runnable}, acquiring a permit first.
   *
   * @param runnable the operation to protect
   * @throws RateLimiterException if no permit is available within the timeout
   */
  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RateLimiterException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Direct permission API ========================

  /**
   * Attempts to acquire a permit without waiting.
   *
   * @return {@code true} if a permit was acquired
   */
  public boolean tryAcquirePermission() {
    Instant now = clock.instant();
    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      RateLimitPermission result = RateLimiterCore.tryAcquirePermission(current, config, now);

      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        if (result.permitted()) {
          emitEvent(RateLimiterEvent.permitted(
              config.name(), result.snapshot().availablePermits(), now));
        }
        return result.permitted();
      }
      // CAS failed — retry
    }
  }

  /**
   * Acquires a permit, blocking if necessary up to the configured timeout.
   *
   * @throws RateLimiterException if no permit becomes available within the timeout
   */
  public void acquirePermission() {
    acquirePermissionOrThrow(config.defaultTimeout());
  }

  // ======================== Internal ========================

  private void acquirePermissionOrThrow(Duration timeout) {
    Instant now = clock.instant();
    Instant deadline = now.plus(timeout);

    while (true) {
      RateLimiterSnapshot current = snapshotRef.get();
      now = clock.instant();

      // Try with reservation (supports waiting)
      ReservationResult reservation = RateLimiterCore.reservePermission(
          current, config, now, timeout);

      if (reservation.timedOut()) {
        // No permit available within timeout
        emitEvent(RateLimiterEvent.rejected(
            config.name(), current.availablePermits(), reservation.waitDuration(), now));
        throw new RateLimiterException(
            config.name(), reservation.waitDuration(), current.availablePermits());
      }

      if (snapshotRef.compareAndSet(current, reservation.snapshot())) {
        if (reservation.waitDuration().isZero()) {
          // Immediate permit
          emitEvent(RateLimiterEvent.permitted(
              config.name(), reservation.snapshot().availablePermits(), now));
          return;
        }

        // Wait for the reservation
        emitEvent(RateLimiterEvent.waiting(
            config.name(), reservation.snapshot().availablePermits(),
            reservation.waitDuration(), now));

        parkForDuration(reservation.waitDuration(), deadline);
        return;
      }
      // CAS failed — retry
    }
  }

  /**
   * Parks the current thread for the given duration.
   * Uses {@link LockSupport#parkNanos} which is virtual-thread-friendly
   * (does not pin to a carrier thread).
   */
  private void parkForDuration(Duration waitDuration, Instant deadline) {
    Instant now = clock.instant();
    Duration remaining = Duration.between(now, deadline);
    Duration actualWait = waitDuration.compareTo(remaining) < 0 ? waitDuration : remaining;

    if (actualWait.isPositive()) {
      LockSupport.parkNanos(actualWait.toNanos());
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers a listener that is called on every rate limiter event.
   */
  public void onEvent(Consumer<RateLimiterEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(RateLimiterEvent event) {
    for (Consumer<RateLimiterEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  // ======================== Introspection ========================

  /**
   * Returns the current number of available permits (with refill applied).
   */
  public int getAvailablePermits() {
    return RateLimiterCore.availablePermits(snapshotRef.get(), config, clock.instant());
  }

  /**
   * Returns a snapshot of the current internal state.
   */
  public RateLimiterSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  /**
   * Returns the configuration.
   */
  public RateLimiterConfig getConfig() {
    return config;
  }

  /**
   * Drains all permits from the bucket.
   */
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
   * Resets the rate limiter to its initial (full-bucket) state.
   */
  public void reset() {
    Instant now = clock.instant();
    RateLimiterSnapshot fresh = RateLimiterCore.reset(config, now);
    snapshotRef.set(fresh);
    emitEvent(RateLimiterEvent.reset(config.name(), fresh.availablePermits(), now));
  }
}
