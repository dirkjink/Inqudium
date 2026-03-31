package eu.inqudium.imperative.circuitbreaker;

import eu.inqudium.core.circuitbreaker.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative circuit breaker implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom) or traditional
 * platform threads. Uses lock-free CAS operations on an
 * {@link AtomicReference} to manage state, avoiding pinning of virtual threads
 * on monitors.
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link CircuitBreakerCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = CircuitBreakerConfig.builder("my-service")
 *     .failureThreshold(3)
 *     .waitDurationInOpenState(Duration.ofSeconds(10))
 *     .build();
 *
 * var cb = new ImperativeCircuitBreaker(config);
 *
 * // With exception propagation
 * String result = cb.execute(() -> httpClient.call());
 *
 * // With fallback
 * String result = cb.executeWithFallback(
 *     () -> httpClient.call(),
 *     () -> "fallback-value"
 * );
 * }</pre>
 */
public class ImperativeCircuitBreaker {

  private final CircuitBreakerConfig config;
  private final AtomicReference<CircuitBreakerSnapshot> snapshotRef;
  private final Clock clock;
  private final List<Consumer<StateTransition>> transitionListeners;

  public ImperativeCircuitBreaker(CircuitBreakerConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.snapshotRef = new AtomicReference<>(CircuitBreakerSnapshot.initial(clock.instant()));
    this.transitionListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  /**
   * Executes the given callable through the circuit breaker.
   *
   * @param callable the operation to protect
   * @param <T>      the return type
   * @return the result of the callable
   * @throws CircuitBreakerException if the circuit is open and the call is not permitted
   * @throws Exception               if the callable itself throws
   */
  public <T> T execute(Callable<T> callable) throws Exception {
    acquirePermissionOrThrow();
    try {
      T result = callable.call();
      recordSuccess();
      return result;
    } catch (Exception e) {
      handleException(e);
      throw e;
    }
  }

  /**
   * Executes the given callable with a fallback when the circuit is open.
   *
   * @param callable the primary operation
   * @param fallback the fallback supplier invoked when the circuit rejects the call
   * @param <T>      the return type
   * @return the result of the callable or the fallback
   * @throws Exception if the callable throws (non-recorded exceptions still propagate)
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (CircuitBreakerException e) {
      return fallback.get();
    }
  }

  /**
   * Executes a {@link Runnable} through the circuit breaker.
   *
   * @param runnable the operation to protect
   * @throws CircuitBreakerException if the circuit is open
   */
  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (CircuitBreakerException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Permission ========================

  private void acquirePermissionOrThrow() {
    Instant now = clock.instant();
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      PermissionResult result = CircuitBreakerCore.tryAcquirePermission(current, config, now);

      if (!result.permitted()) {
        throw new CircuitBreakerException(config.name(), current.state());
      }

      // CAS: apply the (possibly transitioned) snapshot
      if (snapshotRef.compareAndSet(current, result.snapshot())) {
        emitTransitionIfChanged(current, result.snapshot(), now);
        return;
      }
      // CAS failed — another thread modified state; retry
    }
  }

  // ======================== Recording ========================

  private void recordSuccess() {
    Instant now = clock.instant();
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordSuccess(current, config, now);
      if (snapshotRef.compareAndSet(current, updated)) {
        emitTransitionIfChanged(current, updated, now);
        return;
      }
    }
  }

  private void handleException(Exception exception) {
    if (!config.shouldRecordAsFailure(exception)) {
      // Exception is ignored — treat as success for the circuit
      recordSuccess();
      return;
    }

    Instant now = clock.instant();
    while (true) {
      CircuitBreakerSnapshot current = snapshotRef.get();
      CircuitBreakerSnapshot updated = CircuitBreakerCore.recordFailure(current, config, now);
      if (snapshotRef.compareAndSet(current, updated)) {
        emitTransitionIfChanged(current, updated, now);
        return;
      }
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers a listener that is called on every state transition.
   *
   * @param listener the transition listener
   */
  public void onStateTransition(Consumer<StateTransition> listener) {
    transitionListeners.add(Objects.requireNonNull(listener));
  }

  private void emitTransitionIfChanged(CircuitBreakerSnapshot before, CircuitBreakerSnapshot after, Instant now) {
    StateTransition transition = CircuitBreakerCore.detectTransition(config.name(), before, after, now);
    if (transition != null) {
      for (Consumer<StateTransition> listener : transitionListeners) {
        listener.accept(transition);
      }
    }
  }

  // ======================== Introspection ========================

  /**
   * Returns the current state of the circuit breaker.
   */
  public CircuitState getState() {
    return snapshotRef.get().state();
  }

  /**
   * Returns a snapshot of the current internal state. Useful for monitoring.
   */
  public CircuitBreakerSnapshot getSnapshot() {
    return snapshotRef.get();
  }

  /**
   * Returns the configuration of this circuit breaker.
   */
  public CircuitBreakerConfig getConfig() {
    return config;
  }

  /**
   * Resets the circuit breaker to its initial CLOSED state.
   */
  public void reset() {
    Instant now = clock.instant();
    CircuitBreakerSnapshot before = snapshotRef.getAndSet(CircuitBreakerSnapshot.initial(now));
    emitTransitionIfChanged(before, snapshotRef.get(), now);
  }
}
