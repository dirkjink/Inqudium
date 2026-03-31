package eu.inqudium.imperative.retry;

import eu.inqudium.core.retry.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe, imperative retry implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom). The backoff
 * delay between retries is honoured via {@link LockSupport#parkNanos},
 * which is virtual-thread-friendly (does not pin to a carrier thread).
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link RetryCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = RetryConfig.builder("downstream-call")
 *     .maxAttempts(4)
 *     .exponentialBackoff(Duration.ofMillis(200))
 *     .retryOnExceptions(IOException.class, TimeoutException.class)
 *     .build();
 *
 * var retry = new ImperativeRetry(config);
 *
 * // Basic usage
 * String result = retry.execute(() -> httpClient.call());
 *
 * // With fallback on exhaustion
 * String result = retry.executeWithFallback(
 *     () -> httpClient.call(),
 *     () -> "fallback-value"
 * );
 * }</pre>
 */
public class ImperativeRetry {

  private final RetryConfig config;
  private final Clock clock;
  private final List<Consumer<RetryEvent>> eventListeners;

  public ImperativeRetry(RetryConfig config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeRetry(RetryConfig config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Callable Execution ========================

  /**
   * Executes the given callable with retry logic.
   *
   * <p>On each retryable failure, the calling thread is parked for the
   * computed backoff delay before the next attempt.
   *
   * @param callable the operation to protect with retries
   * @param <T>      the return type
   * @return the result of the callable
   * @throws RetryException if all attempts are exhausted
   * @throws Exception      if the callable throws a non-retryable exception
   */
  public <T> T execute(Callable<T> callable) throws Exception {
    Instant now = clock.instant();
    RetrySnapshot snapshot = RetryCore.startFirstAttempt(now);
    emitEvent(RetryEvent.attemptStarted(
        config.name(), 1, snapshot.totalElapsed(now), now));

    while (true) {
      try {
        T result = callable.call();

        // Check if the result should trigger a retry
        RetryDecision resultDecision = RetryCore.evaluateResult(snapshot, config, result);
        if (resultDecision == null) {
          // Result is acceptable
          snapshot = RetryCore.recordSuccess(snapshot);
          Instant completedAt = clock.instant();
          emitEvent(RetryEvent.attemptSucceeded(
              config.name(), snapshot.attemptNumber(),
              snapshot.totalElapsed(completedAt), completedAt));
          return result;
        }

        // Result-based retry
        snapshot = handleDecision(resultDecision, snapshot, null);
        if (snapshot.state().isTerminal()) {
          throw new RetryException(config.name(), snapshot.totalAttempts(),
              new RuntimeException("Unacceptable result: " + result),
              snapshot.failures());
        }

      } catch (RetryException e) {
        throw e; // Don't retry our own exhaustion exception
      } catch (Exception e) {
        RetryDecision decision = RetryCore.evaluateFailure(snapshot, config, e);
        snapshot = handleDecision(decision, snapshot, e);

        if (snapshot.state() == RetryState.FAILED) {
          throw e;
        }
        if (snapshot.state() == RetryState.EXHAUSTED) {
          throw new RetryException(
              config.name(), snapshot.totalAttempts(), e, snapshot.failures());
        }
      }

      // Wait and start next attempt
      if (snapshot.state() == RetryState.WAITING_FOR_RETRY) {
        Duration delay = snapshot.nextRetryDelay();
        if (delay != null && delay.isPositive()) {
          LockSupport.parkNanos(delay.toNanos());
        }

        Instant retryNow = clock.instant();
        snapshot = RetryCore.startNextAttempt(snapshot, retryNow);
        emitEvent(RetryEvent.attemptStarted(
            config.name(), snapshot.attemptNumber(),
            snapshot.totalElapsed(retryNow), retryNow));
      }
    }
  }

  /**
   * Executes a {@link Runnable} with retry logic.
   */
  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (RetryException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Executes with a fallback on retry exhaustion.
   *
   * @param callable the primary operation
   * @param fallback supplier invoked when all retries are exhausted
   * @param <T>      the return type
   * @return the result of the callable or the fallback
   * @throws Exception if the callable throws a non-retryable exception
   */
  public <T> T executeWithFallback(Callable<T> callable, Supplier<T> fallback) throws Exception {
    try {
      return execute(callable);
    } catch (RetryException e) {
      return fallback.get();
    }
  }

  // ======================== Internal ========================

  private RetrySnapshot handleDecision(RetryDecision decision, RetrySnapshot snapshot, Throwable failure) {
    Instant now = clock.instant();
    Duration elapsed = snapshot.totalElapsed(now);

    return switch (decision) {
      case RetryDecision.DoRetry doRetry -> {
        emitEvent(RetryEvent.retryScheduled(
            config.name(), snapshot.attemptNumber(), doRetry.delay(),
            elapsed, failure, now));
        yield doRetry.snapshot();
      }
      case RetryDecision.DoNotRetry doNotRetry -> {
        emitEvent(RetryEvent.failedNonRetryable(
            config.name(), snapshot.attemptNumber(), elapsed,
            doNotRetry.failure(), now));
        yield doNotRetry.snapshot();
      }
      case RetryDecision.RetriesExhausted exhausted -> {
        emitEvent(RetryEvent.retriesExhausted(
            config.name(), snapshot.attemptNumber(), elapsed,
            exhausted.failure(), now));
        yield exhausted.snapshot();
      }
    };
  }

  // ======================== Listeners ========================

  /**
   * Registers a listener that is called on every retry event.
   */
  public void onEvent(Consumer<RetryEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(RetryEvent event) {
    for (Consumer<RetryEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  // ======================== Introspection ========================

  /**
   * Returns the configuration.
   */
  public RetryConfig getConfig() {
    return config;
  }
}
