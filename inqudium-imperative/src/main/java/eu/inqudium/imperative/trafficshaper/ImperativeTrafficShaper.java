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
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

  private final TrafficShaperConfig config;
  private final AtomicReference<ThrottleSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<TrafficShaperEvent>> eventListeners;

  public ImperativeTrafficShaper(TrafficShaperConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeTrafficShaper(TrafficShaperConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(ThrottleSnapshot.initial(clock.instant()));
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    waitForSlot();
    return callable.call();
  }

  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RuntimeException | Error e) {
      // Fix 4B: Disjunkte Typen korrigiert. Fängt TrafficShaperException, RuntimeException und Errors.
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (TrafficShaperException e) {
      // Fix 4A: Maskierung verschachtelter Traffic Shaper verhindern.
      if (Objects.equals(e.getTrafficShaperName(), config.name())) {
        return fallback.get();
      }
      throw e;
    }
  }

  // ======================== Internal ========================

  private void waitForSlot() {
    ThrottlePermission permission = acquireSlot();

    try {
      if (permission.requiresWait()) {
        parkUntil(permission.scheduledSlot());
      }
    } finally {
      // Fix 1 & 2A: recordExecution() zwingend im finally-Block aufrufen!
      // Wenn der Thread durch einen Timeout oder Interrupt abgebrochen wird,
      // MUSS die Queue-Depth zwingend wieder dekrementiert werden,
      // sonst leakt die Queue für immer voll.
      recordExecution();
    }
  }

  private ThrottlePermission acquireSlot() {
    while (true) {
      Instant now = clock.instant();
      ThrottleSnapshot current = snapshotRef.get();

      // Nutze die Core-Logik, um den nächsten Slot zu berechnen
      ThrottlePermission permission = TrafficShaperCore.schedule(current, config, now);

      if (!permission.admitted()) {
        emitEvent(TrafficShaperEvent.rejected(
            config.name(), permission.waitDuration(), current.queueDepth(), now));
        throw new TrafficShaperException(
            config.name(), permission.waitDuration(), current.queueDepth());
      }

      if (snapshotRef.compareAndSet(current, permission.snapshot())) {
        if (permission.requiresWait()) {
          emitEvent(TrafficShaperEvent.admittedDelayed(
              config.name(), permission.waitDuration(), permission.snapshot().queueDepth(), now));
        } else {
          emitEvent(TrafficShaperEvent.admittedImmediate(
              config.name(), permission.snapshot().queueDepth(), now));
        }
        return permission;
      }
    }
  }

  private void parkUntil(Instant targetWakeup) {
    // Fix 2B: while-Schleife schützt vor Spurious Wakeups
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        // Fix 2A: Werfe bei Thread-Interrupt sofort eine Exception,
        // um das Waiting abzubrechen (wird dann durch das `finally` im waitForSlot aufgeräumt).
        throw new RuntimeException(new InterruptedException("Thread interrupted while waiting for traffic shaper slot"));
      }

      Duration remaining = Duration.between(clock.instant(), targetWakeup);
      if (remaining.isNegative() || remaining.isZero()) {
        break; // Ziel-Zeitpunkt ist physisch erreicht
      }
      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  private void recordExecution() {
    while (true) {
      Instant now = clock.instant();
      ThrottleSnapshot current = snapshotRef.get();
      // Verkleinert die queueDepth
      ThrottleSnapshot dequeued = current.withRequestDequeued();

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

  private void emitEvent(TrafficShaperEvent event) {
    for (Consumer<TrafficShaperEvent> listener : eventListeners) {
      listener.accept(event);
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

  public void reset() {
    Instant now = clock.instant();
    ThrottleSnapshot fresh = TrafficShaperCore.reset(now);
    snapshotRef.set(fresh);
    emitEvent(TrafficShaperEvent.reset(config.name(), now));
  }
}