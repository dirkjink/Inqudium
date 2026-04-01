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
    Objects.requireNonNull(callable, "callable must not be null");

    Instant now = clock.instant();
    FallbackSnapshot snapshot = FallbackCore.start(now);
    emitEvent(FallbackEvent.primaryStarted(config.name(), now));

    T result;
    try {
      result = callable.call();
    } catch (Throwable primary) {
      // Fix 2: Bypassing the fallback chain for InterruptedException
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw (InterruptedException) primary; // Throw directly, avoid FallbackException
      }
      return handlePrimaryFailure(snapshot, primary);
    }

    return handleResult(snapshot, result);
  }

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
      // Fix 2: Bypassing the fallback chain for InterruptedException
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw (InterruptedException) primary;
      }
      return handlePrimaryFailure(snapshot, primary);
    }
  }

  private T handleResult(FallbackSnapshot snapshot, T result) throws Exception {
    Instant resultTime = clock.instant();

    FallbackCore.ResultResolution<T> resultResolution =
        FallbackCore.resolveResultHandler(snapshot, config, result, resultTime);

    if (!resultResolution.matched()) {
      snapshot = resultResolution.snapshot();
      emitEvent(FallbackEvent.primarySucceeded(
          config.name(), snapshot.elapsed(resultTime), resultTime));
      return result;
    }

    snapshot = resultResolution.snapshot();
    emitEvent(FallbackEvent.resultFallbackInvoked(
        config.name(), resultResolution.handler().name(), Duration.ZERO, resultTime));

    try {
      // Fix 1: Provide the rejected original result to the handler
      T fallbackResult = FallbackCore.invokeResultHandler(resultResolution.handler(), result);
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

      // Fix 4: Propagate transparently instead of wrapping in FallbackException
      // Allows active rejection like throwing IllegalStateException to remain clean
      if (fallbackEx instanceof Exception e) throw e;
      if (fallbackEx instanceof Error err) throw err;
      throw new RuntimeException(fallbackEx);
    }
  }

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

  private void emitEvent(FallbackEvent event) {
    for (Consumer<FallbackEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) { // Fix 3: Catch Throwable to prevent NoClassDefFoundError crashes
        LOG.log(Level.WARNING,
            "Event listener threw exception for fallback '%s' (event: %s): %s"
                .formatted(config.name(), event.type(), t.getMessage()),
            t);
      }
    }
  }

  public FallbackConfig<T> getConfig() {
    return config;
  }
}