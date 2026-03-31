package eu.inqudium.imperative.fallback;

import eu.inqudium.core.fallback.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe, imperative fallback provider implementation.
 *
 * <p>Designed for use with virtual threads (Project Loom). Intercepts
 * exceptions from the primary operation and routes them to registered
 * fallback handlers based on exception type, predicate, or catch-all.
 *
 * <p>Also supports result-based fallback: if the primary operation returns
 * an unacceptable value (e.g. {@code null}), a registered result handler
 * provides a substitute.
 *
 * <p>Delegates all state-machine logic to the functional
 * {@link FallbackCore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var config = FallbackConfig.<String>builder("user-service")
 *     .onException(IOException.class, ex -> "cached-user")
 *     .onException(TimeoutException.class, ex -> "timeout-default")
 *     .onResult(result -> result == null, () -> "unknown-user")
 *     .onAnyException(ex -> "generic-fallback")
 *     .build();
 *
 * var fallback = new ImperativeFallbackProvider<>(config);
 *
 * // Exceptions are caught and routed to the matching handler
 * String user = fallback.execute(() -> userService.findById(42));
 *
 * // null results are replaced by the result handler
 * String user = fallback.execute(() -> userService.findByName("unknown"));
 * }</pre>
 */
public class ImperativeFallbackProvider<T> {

  private final FallbackConfig<T> config;
  private final Clock clock;
  private final List<Consumer<FallbackEvent>> eventListeners;

  public ImperativeFallbackProvider(FallbackConfig<T> config) {
    this(config, Clock.systemUTC());
  }

  public ImperativeFallbackProvider(FallbackConfig<T> config, Clock clock) {
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.eventListeners = new CopyOnWriteArrayList<>();
  }

  // ======================== Execution ========================

  /**
   * Executes the given callable with fallback protection.
   *
   * <p>On exception: the first matching handler is invoked.
   * On unacceptable result: the first matching result handler is invoked.
   * If no handler matches, the original exception propagates.
   * If the fallback handler itself fails, a {@link FallbackException} is thrown.
   *
   * @param callable the primary operation
   * @return the result (from primary or fallback)
   * @throws FallbackException if no handler matches or the fallback fails
   * @throws Exception         if the primary throws a non-matched exception
   */
  public T execute(Callable<T> callable) throws Exception {
    Instant now = clock.instant();
    FallbackSnapshot snapshot = FallbackCore.start(now);
    emitEvent(FallbackEvent.primaryStarted(config.name(), now));

    try {
      T result = callable.call();

      // Check for result-based fallback
      Instant resultTime = clock.instant();
      FallbackCore.HandlerResolution<T> resultResolution =
          FallbackCore.resolveResultHandler(snapshot, config, result, resultTime);

      if (resultResolution == null) {
        // Result is acceptable
        snapshot = FallbackCore.recordPrimarySuccess(snapshot, resultTime);
        emitEvent(FallbackEvent.primarySucceeded(
            config.name(), snapshot.elapsed(resultTime), resultTime));
        return result;
      }

      // Result-based fallback
      snapshot = resultResolution.snapshot();
      emitEvent(FallbackEvent.resultFallbackInvoked(
          config.name(), resultResolution.handler().name(),
          snapshot.elapsed(resultTime), resultTime));

      T fallbackResult = FallbackCore.invokeResultHandler(resultResolution.handler());
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);
      emitEvent(FallbackEvent.resultFallbackRecovered(
          config.name(), resultResolution.handler().name(),
          snapshot.elapsed(recoveredTime), recoveredTime));
      return fallbackResult;

    } catch (Exception primary) {
      return handleException(snapshot, primary);
    }
  }

  /**
   * Executes a {@link Runnable} with fallback protection.
   * Note: fallback handlers must return a value, so this only provides
   * exception-swallowing semantics for void operations.
   */
  public void execute(Runnable runnable) {
    try {
      execute(() -> {
        runnable.run();
        return null;
      });
    } catch (FallbackException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ======================== Internal ========================

  private T handleException(FallbackSnapshot snapshot, Exception primary) throws Exception {
    Instant failedTime = clock.instant();
    emitEvent(FallbackEvent.primaryFailed(
        config.name(), snapshot.elapsed(failedTime), primary, failedTime));

    // Resolve handler
    FallbackCore.HandlerResolution<T> resolution =
        FallbackCore.resolveHandler(snapshot, config, primary, failedTime);

    if (!resolution.matched()) {
      emitEvent(FallbackEvent.noHandlerMatched(
          config.name(), snapshot.elapsed(failedTime), primary, failedTime));
      throw primary; // No handler found — propagate the original exception
    }

    snapshot = resolution.snapshot();
    emitEvent(FallbackEvent.fallbackInvoked(
        config.name(), resolution.handler().name(),
        snapshot.elapsed(failedTime), failedTime));

    // Invoke the handler
    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(resolution.handler(), primary);
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);
      emitEvent(FallbackEvent.fallbackRecovered(
          config.name(), resolution.handler().name(),
          snapshot.elapsed(recoveredTime), recoveredTime));
      return fallbackValue;

    } catch (Exception fallbackEx) {
      Instant fbFailedTime = clock.instant();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedTime);
      emitEvent(FallbackEvent.fallbackFailed(
          config.name(), resolution.handler().name(),
          snapshot.elapsed(fbFailedTime), fallbackEx, fbFailedTime));
      throw new FallbackException(
          config.name(), FallbackException.Reason.FALLBACK_FAILED,
          primary, fallbackEx);
    }
  }

  // ======================== Listeners ========================

  /**
   * Registers a listener that is called on every fallback event.
   */
  public void onEvent(Consumer<FallbackEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  private void emitEvent(FallbackEvent event) {
    for (Consumer<FallbackEvent> listener : eventListeners) {
      listener.accept(event);
    }
  }

  // ======================== Introspection ========================

  /**
   * Returns the configuration.
   */
  public FallbackConfig<T> getConfig() {
    return config;
  }
}
