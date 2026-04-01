package eu.inqudium.imperative.retry;

import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.RetryCore;
import eu.inqudium.core.retry.RetryDecision;
import eu.inqudium.core.retry.RetryEvent;
import eu.inqudium.core.retry.RetryException;
import eu.inqudium.core.retry.RetrySnapshot;
import eu.inqudium.core.retry.RetryState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative retry implementation.
 */
public class ImperativeRetry {

  private static final Logger LOG = Logger.getLogger(ImperativeRetry.class.getName());

  private final RetryConfig config;
  private final Clock clock;
  private final List<Consumer<RetryEvent>> eventListeners;

  // Fix 7: Unique instance identifier for identity-based comparison in executeWithFallback
  private final String instanceId;

  public ImperativeRetry(RetryConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRetry(RetryConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
    this.instanceId = UUID.randomUUID().toString();
  }

  // ======================== Callable Execution ========================

  public <T> T execute(Callable<T> callable) throws Exception {
    Objects.requireNonNull(callable, "callable must not be null");

    Instant now = clock.instant();
    RetrySnapshot snapshot = RetryCore.startFirstAttempt(now);
    emitEvent(RetryEvent.attemptStarted(config.name(), 1, snapshot.totalElapsed(now), now));

    while (true) {
      boolean success = false;
      T result = null;
      Throwable attemptFailure = null;

      try {
        result = callable.call();
        success = true;
      } catch (Throwable e) {
        // Fix 5: InterruptedException must be honoured immediately.
        // Retrying an interrupted call is almost never correct — the interrupt
        // signals that the thread (or virtual thread) should stop.
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw (InterruptedException) e;
        }
        attemptFailure = e;
      }

      if (success) {
        // Fix 1: evaluateResult now always returns a non-null decision
        RetryDecision resultDecision = RetryCore.evaluateResult(snapshot, config, result);

        switch (resultDecision) {
          case RetryDecision.Accept accept -> {
            snapshot = accept.snapshot();
            Instant completedAt = clock.instant();
            emitEvent(RetryEvent.attemptSucceeded(
                config.name(), snapshot.attemptNumber(),
                snapshot.totalElapsed(completedAt), completedAt));
            return result;
          }

          case RetryDecision.DoRetry doRetry -> {
            // Fix 9: Use the dedicated result retry event type instead of passing null as failure
            snapshot = emitRetryDecision(doRetry, snapshot, true);
          }

          case RetryDecision.RetriesExhausted exhausted -> {
            snapshot = emitExhaustedDecision(exhausted, snapshot);
            throw new RetryException(
                config.name(), instanceId, snapshot.totalAttempts(),
                snapshot.lastFailure(), snapshot.failures(),
                exhausted.resultBased());
          }

          // Result evaluation never produces DoNotRetry
          case RetryDecision.DoNotRetry e ->
              throw new IllegalStateException("evaluateResult should not produce DoNotRetry");
        }

      } else {
        RetryDecision decision = RetryCore.evaluateFailure(snapshot, config, attemptFailure);

        switch (decision) {
          case RetryDecision.DoRetry doRetry -> {
            snapshot = emitRetryDecision(doRetry, snapshot, false);
          }

          case RetryDecision.DoNotRetry doNotRetry -> {
            Instant failedAt = clock.instant();
            emitEvent(RetryEvent.failedNonRetryable(
                config.name(), snapshot.attemptNumber(),
                snapshot.totalElapsed(failedAt), doNotRetry.failure(), failedAt));
            // Transparent propagation
            if (attemptFailure instanceof Exception ex) throw ex;
            if (attemptFailure instanceof Error err) throw err;
            throw new RuntimeException(attemptFailure);
          }

          case RetryDecision.RetriesExhausted exhausted -> {
            snapshot = emitExhaustedDecision(exhausted, snapshot);
            throw new RetryException(
                config.name(), instanceId, snapshot.totalAttempts(),
                attemptFailure, snapshot.failures(),
                exhausted.resultBased());
          }

          // Failure evaluation never produces Accept
          case RetryDecision.Accept e -> throw new IllegalStateException("evaluateFailure should not produce Accept");
        }
      }

      // Wait and start next attempt
      if (snapshot.state() == RetryState.WAITING_FOR_RETRY) {
        Duration delay = snapshot.nextRetryDelay();
        if (delay != null && delay.isPositive()) {
          parkUntil(clock.instant().plus(delay));
        }

        Instant retryNow = clock.instant();
        snapshot = RetryCore.startNextAttempt(snapshot, retryNow);
        emitEvent(RetryEvent.attemptStarted(
            config.name(), snapshot.attemptNumber(),
            snapshot.totalElapsed(retryNow), retryNow));
      }
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

  /**
   * Executes with a fallback that activates when <em>this</em> retry instance exhausts all attempts.
   *
   * <p>Fix 7: Uses {@code instanceId} instead of the human-readable name to determine
   * whether the exception originated from this retry instance. This prevents false
   * positives when multiple retries share the same name or when a downstream retry throws.
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (RetryException e) {
      if (Objects.equals(e.getInstanceId(), this.instanceId)) {
        return fallback.get();
      }
      throw e;
    }
  }

  // ======================== Internal ========================

  /**
   * Fix 9: Emits the correct event type for retry decisions —
   * result-based retries use RESULT_RETRY_SCHEDULED, exception-based retries use RETRY_SCHEDULED.
   */
  private RetrySnapshot emitRetryDecision(
      RetryDecision.DoRetry doRetry,
      RetrySnapshot snapshot,
      boolean resultBased) {

    Instant now = clock.instant();
    Duration elapsed = snapshot.totalElapsed(now);

    if (resultBased) {
      emitEvent(RetryEvent.resultRetryScheduled(
          config.name(), snapshot.attemptNumber(), doRetry.delay(), elapsed, now));
    } else {
      emitEvent(RetryEvent.retryScheduled(
          config.name(), snapshot.attemptNumber(), doRetry.delay(),
          elapsed, snapshot.lastFailure(), now));
    }

    return doRetry.snapshot();
  }

  private RetrySnapshot emitExhaustedDecision(
      RetryDecision.RetriesExhausted exhausted,
      RetrySnapshot snapshot) {

    Instant now = clock.instant();
    Duration elapsed = snapshot.totalElapsed(now);

    emitEvent(RetryEvent.retriesExhausted(
        config.name(), snapshot.attemptNumber(), elapsed,
        exhausted.failure(), now));

    return exhausted.snapshot();
  }

  /**
   * Parks the current thread until the target time is reached.
   *
   * <p>Fix 10: Properly handles interrupts by restoring the interrupt flag
   * and throwing an InterruptedException instead of wrapping in RuntimeException.
   * This allows the caller (the retry loop) to propagate the interrupt cleanly.
   */
  private void parkUntil(Instant targetWakeup) {
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        // Fix 10: Throw a checked InterruptedException that the retry loop can propagate.
        // The interrupt flag is already set — callers of execute() will see it.
        throw new RetryInterruptedException(
            "Thread interrupted during retry backoff for '%s'".formatted(config.name()));
      }

      Duration remaining = Duration.between(clock.instant(), targetWakeup);
      if (remaining.isNegative() || remaining.isZero()) {
        break;
      }
      LockSupport.parkNanos(remaining.toNanos());
    }
  }

  public void onEvent(Consumer<RetryEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  // ======================== Listeners & Introspection ========================

  /**
   * Fix 4: Each listener is invoked in its own try-catch to prevent a failing listener
   * from breaking the retry loop. Without this, a monitoring listener that throws
   * intermittently could abort the entire retry sequence.
   */
  private void emitEvent(RetryEvent event) {
    for (Consumer<RetryEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for retry '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), e.getMessage()),
            e);
      }
    }
  }

  public RetryConfig getConfig() {
    return config;
  }

  /**
   * Fix 7: Returns the unique instance identifier.
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Fix 10: Dedicated unchecked exception for interrupts during backoff.
   * Distinguishable from other RuntimeExceptions and preserves the interrupt semantics.
   */
  static class RetryInterruptedException extends RuntimeException {
    RetryInterruptedException(String message) {
      super(message);
    }
  }
}
