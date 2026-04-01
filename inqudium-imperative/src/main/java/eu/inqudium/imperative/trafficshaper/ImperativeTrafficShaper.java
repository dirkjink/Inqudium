package eu.inqudium.imperative.trafficshaper;

import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.ThrottleSnapshot;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.trafficshaper.TrafficShaperCore;
import eu.inqudium.core.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.trafficshaper.TrafficShaperException;

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
 * Thread-safe, imperative traffic shaper implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom). Uses lock-free
 * CAS operations for scheduling and {@link LockSupport#parkNanos} for
 * the throttle delay, which is virtual-thread-friendly.
 *
 * <p>Unlike a rate limiter that rejects excess requests, the traffic shaper
 * <em>delays</em> them to produce smooth, evenly-spaced output. Only when
 * the queue overflows or the wait exceeds the configured maximum are
 * requests rejected.
 */
public class ImperativeTrafficShaper {

  private static final Logger LOG = Logger.getLogger(ImperativeTrafficShaper.class.getName());

  private final TrafficShaperConfig config;
  private final AtomicReference<ThrottleSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<TrafficShaperEvent>> eventListeners;

  // Fix 5: Unique instance identifier for identity-based comparison
  private final String instanceId;

  public ImperativeTrafficShaper(TrafficShaperConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTrafficShaper(TrafficShaperConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(ThrottleSnapshot.initial(clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ======================== Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    // Fix 10: Fail fast on null — prevents consuming a slot for nothing
    Objects.requireNonNull(callable, "callable must not be null");
    waitForSlot();
    return callable.call();
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

  /**
   * Fix 5: Uses {@code instanceId} instead of name for identity-based comparison.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TrafficShaperException e) {
      if (Objects.equals(e.getInstanceId(), this.instanceId)) {
        return fallback.get();
      }
      throw e;
    }
  }

  // ======================== Internal ========================

  private void waitForSlot() {
    ThrottlePermission permission = acquireSlot();

    // Fix 1: Immediate requests don't enter the queue, so they don't need
    // recordExecution(). Only delayed requests need the try-finally dequeue.
    if (!permission.requiresWait()) {
      return;
    }

    try {
      parkUntil(permission.scheduledSlot());
    } finally {
      // Fix 1: Only dequeue delayed requests. This is in finally to ensure
      // queueDepth is decremented even if the thread is interrupted.
      recordExecution();
    }
  }

  private ThrottlePermission acquireSlot() {
    while (true) {
      Instant now = clock.instant();
      ThrottleSnapshot current = snapshotRef.get();

      ThrottlePermission permission = TrafficShaperCore.schedule(current, config, now);

      if (!permission.admitted()) {
        // Fix 4: Emit inside try-catch so a listener failure doesn't prevent the exception
        emitEvent(TrafficShaperEvent.rejected(
            config.name(), permission.waitDuration(), current.queueDepth(), now));
        // Fix 5: Include instanceId in exception
        throw new TrafficShaperException(
            config.name(), instanceId, permission.waitDuration(), current.queueDepth());
      }

      if (snapshotRef.compareAndSet(current, permission.snapshot())) {
        if (permission.requiresWait()) {
          emitEvent(TrafficShaperEvent.admittedDelayed(
              config.name(), permission.waitDuration(), permission.snapshot().queueDepth(), now));

          // Fix 11: Check if the unbounded queue has grown dangerously
          if (TrafficShaperCore.isUnboundedQueueWarning(permission.snapshot(), config, now)) {
            emitEvent(TrafficShaperEvent.unboundedQueueWarning(
                config.name(),
                permission.snapshot().projectedTailWait(now),
                permission.snapshot().queueDepth(),
                now));
          }
        } else {
          emitEvent(TrafficShaperEvent.admittedImmediate(
              config.name(), permission.snapshot().queueDepth(), now));
        }
        return permission;
      }
    }
  }

  /**
   * Fix 8: Properly handles interrupts with a dedicated exception type
   * instead of wrapping in RuntimeException.
   */
  private void parkUntil(Instant targetWakeup) {
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        // The interrupt flag stays set. The finally block in waitForSlot
        // will call recordExecution() to clean up the queue depth.
        throw new TrafficShaperInterruptedException(
            "Thread interrupted while waiting for traffic shaper '%s' slot"
                .formatted(config.name()));
      }

      Duration remaining = Duration.between(clock.instant(), targetWakeup);
      if (remaining.isNegative() || remaining.isZero()) {
        break;
      }
      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  /**
   * Fix 8: Dedicated unchecked exception for interrupts during slot waiting.
   * Distinguishable from other RuntimeExceptions and TrafficShaperExceptions.
   */
  static class TrafficShaperInterruptedException extends RuntimeException {
    TrafficShaperInterruptedException(String message) {
      super(message);
    }
  }

  private void recordExecution() {
    while (true) {
      Instant now = clock.instant();
      ThrottleSnapshot current = snapshotRef.get();
      ThrottleSnapshot dequeued = TrafficShaperCore.recordExecution(current);

      if (snapshotRef.compareAndSet(current, dequeued)) {
        emitEvent(TrafficShaperEvent.executing(config.name(), dequeued.queueDepth(), now));
        return;
      }
    }
  }

  // ======================== Listeners ========================

  public void onEvent(Consumer<TrafficShaperEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Fix 4: Each listener is invoked in its own try-catch. A failing listener
   * must not break the scheduling flow or cause queue depth leaks.
   */
  private void emitEvent(TrafficShaperEvent event) {
    for (Consumer<TrafficShaperEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for traffic shaper '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), e.getMessage()),
            e);
      }
    }
  }

  // ======================== Introspection ========================

  public int getQueueDepth() {
    return snapshotRef.get().queueDepth();
  }

  public Duration getEstimatedWait() {
    return TrafficShaperCore.estimateWait(snapshotRef.get(), clock.instant());
  }

  public ThrottleSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  public TrafficShaperConfig getConfig() {
    return config;
  }

  /**
   * Fix 5: Returns the unique instance identifier.
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Fix 9: Reset uses CAS to avoid overwriting concurrent scheduling operations.
   * Threads currently parked on old slots will dequeue on wakeup, which is harmless
   * because queueDepth is clamped to 0 by Math.max in withRequestDequeued.
   */
  public void reset() {
    Instant now = clock.instant();
    while (true) {
      ThrottleSnapshot current = snapshotRef.get();
      ThrottleSnapshot fresh = TrafficShaperCore.reset(now);
      if (snapshotRef.compareAndSet(current, fresh)) {
        emitEvent(TrafficShaperEvent.reset(config.name(), now));
        return;
      }
    }
  }
}
