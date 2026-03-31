package eu.inqudium.imperative.fallback;

import eu.inqudium.core.fallback.FallbackConfig;
import eu.inqudium.core.fallback.FallbackCore;
import eu.inqudium.core.fallback.FallbackEvent;
import eu.inqudium.core.fallback.FallbackException;
import eu.inqudium.core.fallback.FallbackSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe, imperative fallback provider implementation.
 */
public class ImperativeFallbackProvider<T> {

  private static final Logger LOG = Logger.getLogger(ImperativeFallbackProvider.class.getName());

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

  public T execute(Callable<T> callable) throws Exception {
    // Fix 9: Fail fast on null callable instead of letting the NPE
    // propagate through the fallback mechanism
    Objects.requireNonNull(callable, "callable must not be null");

    Instant now = clock.instant();
    FallbackSnapshot snapshot = FallbackCore.start(now);
    emitEvent(FallbackEvent.primaryStarted(config.name(), now));

    // Fix 1: Separate the primary callable invocation from result-fallback handling.
    // Previously, a result-fallback handler failure would fall into the catch block
    // and be treated as a primary exception — causing an IllegalStateException because
    // the snapshot was already in FALLING_BACK state.
    T result;
    try {
      result = callable.call();
    } catch (Throwable primary) {
      // Fix 3: Preserve interrupt status before any processing
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return handlePrimaryFailure(snapshot, primary);
    }

    // Result resolution happens safely outside the primary try-catch
    return handleResult(snapshot, result);
  }

  /**
   * Fix 8: Dedicated Runnable execution that bypasses result handler resolution.
   *
   * <p>The Runnable wrapper returns {@code null} internally, which is a technical
   * artifact, not a meaningful result. Without this separation, a result handler
   * registered with {@code onResult(Objects::isNull, ...)} would always trigger
   * for Runnables, even though the null return has no semantic meaning.
   */
  public void execute(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    try {
      executeWithoutResultCheck(() -> {
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
   * Fix 8: Internal execution path that skips result handler resolution.
   * Used by the Runnable overload where the null return value is meaningless.
   */
  private T executeWithoutResultCheck(Callable<T> callable) throws Exception {
    Instant now = clock.instant();
    FallbackSnapshot snapshot = FallbackCore.start(now);
    emitEvent(FallbackEvent.primaryStarted(config.name(), now));

    try {
      T result = callable.call();

      Instant resultTime = clock.instant();
      snapshot = FallbackCore.recordPrimarySuccess(snapshot, resultTime);
      emitEvent(FallbackEvent.primarySucceeded(
          config.name(), snapshot.elapsed(resultTime), resultTime));
      return result;

    } catch (Throwable primary) {
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return handlePrimaryFailure(snapshot, primary);
    }
  }

  /**
   * Fix 1: Handles the primary result and any result-based fallback invocation
   * in a separate method, completely outside the primary try-catch scope.
   */
  private T handleResult(FallbackSnapshot snapshot, T result) throws Exception {
    Instant resultTime = clock.instant();

    // Fix 4: resolveResultHandler now always returns non-null
    FallbackCore.ResultResolution<T> resultResolution =
        FallbackCore.resolveResultHandler(snapshot, config, result, resultTime);

    if (!resultResolution.matched()) {
      // Fix 4: Snapshot is already transitioned to SUCCEEDED inside the resolution
      snapshot = resultResolution.snapshot();
      emitEvent(FallbackEvent.primarySucceeded(
          config.name(), snapshot.elapsed(resultTime), resultTime));
      return result;
    }

    // A result handler matched — invoke the fallback
    snapshot = resultResolution.snapshot();
    emitEvent(FallbackEvent.resultFallbackInvoked(
        config.name(), resultResolution.handler().name(), Duration.ZERO, resultTime));

    // Fix 1: Now this try-catch correctly handles result-fallback failures
    // instead of accidentally treating them as primary failures
    try {
      T fallbackResult = FallbackCore.invokeResultHandler(resultResolution.handler());
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);

      emitEvent(FallbackEvent.resultFallbackRecovered(
          config.name(), resultResolution.handler().name(),
          snapshot.fallbackElapsed(recoveredTime), recoveredTime));
      return fallbackResult;

    } catch (Throwable fallbackEx) {
      Instant fbFailedTime = clock.instant();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedTime);

      emitEvent(FallbackEvent.fallbackFailed(
          config.name(), resultResolution.handler().name(),
          snapshot.fallbackElapsed(fbFailedTime), fallbackEx, fbFailedTime));

      // Fix 7: primaryFailure is null for result-based fallbacks — FallbackException handles this
      throw new FallbackException(config.name(), null, fallbackEx);
    }
  }

  /**
   * Handles a primary failure by resolving and invoking the appropriate exception handler.
   */
  private T handlePrimaryFailure(FallbackSnapshot snapshot, Throwable primary) throws Exception {
    Instant failedTime = clock.instant();
    emitEvent(FallbackEvent.primaryFailed(
        config.name(), snapshot.elapsed(failedTime), primary, failedTime));

    FallbackCore.ExceptionResolution<T> resolution =
        FallbackCore.resolveExceptionHandler(snapshot, config, primary, failedTime);

    if (!resolution.matched()) {
      snapshot = resolution.snapshot();
      emitEvent(FallbackEvent.noHandlerMatched(
          config.name(), snapshot.elapsed(failedTime), primary, failedTime));

      // Transparent propagation
      if (primary instanceof Exception e) throw e;
      if (primary instanceof Error err) throw err;
      throw new RuntimeException(primary);
    }

    snapshot = resolution.snapshot();
    emitEvent(FallbackEvent.fallbackInvoked(
        config.name(), resolution.handler().name(), Duration.ZERO, failedTime));

    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(resolution.handler(), primary);
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);

      emitEvent(FallbackEvent.fallbackRecovered(
          config.name(), resolution.handler().name(),
          snapshot.fallbackElapsed(recoveredTime), recoveredTime));
      return fallbackValue;

    } catch (Throwable fallbackEx) {
      Instant fbFailedTime = clock.instant();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedTime);

      emitEvent(FallbackEvent.fallbackFailed(
          config.name(), resolution.handler().name(),
          snapshot.fallbackElapsed(fbFailedTime), fallbackEx, fbFailedTime));

      throw new FallbackException(config.name(), primary, fallbackEx);
    }
  }

  public void onEvent(Consumer<FallbackEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Fix 2: Each listener is invoked in its own try-catch to ensure that a failing listener
   * does not prevent subsequent listeners from being notified, and does not corrupt
   * the execution flow of the fallback provider itself.
   */
  private void emitEvent(FallbackEvent event) {
    for (Consumer<FallbackEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        LOG.log(Level.WARNING,
            "Event listener threw exception for fallback '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), e.getMessage()),
            e);
      }
    }
  }

  public FallbackConfig<T> getConfig() {
    return config;
  }
}
