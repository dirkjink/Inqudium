package eu.inqudium.imperative.fallback;

import eu.inqudium.core.fallback.FallbackConfig;
import eu.inqudium.core.fallback.FallbackCore;
import eu.inqudium.core.fallback.FallbackEvent;
import eu.inqudium.core.fallback.FallbackException;
import eu.inqudium.core.fallback.FallbackSnapshot;
import eu.inqudium.core.fallback.FallbackExceptionHandler;
import eu.inqudium.core.fallback.FallbackResultHandler;

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
 * Thread-safe, imperative fallback provider implementation optimized for low GC overhead.
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
    emitEventIfListening(() -> FallbackEvent.primaryStarted(config.name(), now));

    T result;
    try {
      result = callable.call();
    } catch (Throwable primary) {
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw (InterruptedException) primary;
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
    emitEventIfListening(() -> FallbackEvent.primaryStarted(config.name(), now));

    try {
      T result = callable.call();

      // ==========================================
      // HOT-PATH: Erfolg bei Runnables
      // ==========================================
      if (!eventListeners.isEmpty()) {
        Instant resultTime = clock.instant();
        Duration elapsed = Duration.between(snapshot.startTime(), resultTime);
        emitEvent(FallbackEvent.primarySucceeded(config.name(), elapsed, resultTime));
      }
      return result;

    } catch (Throwable primary) {
      if (primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw (InterruptedException) primary;
      }
      return handlePrimaryFailure(snapshot, primary);
    }
  }

  private T handleResult(FallbackSnapshot snapshot, T result) throws Exception {
    // DIREKTER LOOKUP
    FallbackResultHandler<T> handler = config.findHandlerForResult(result);

    if (handler == null) {
      // ==========================================
      // HOT-PATH: Erfolg & kein Fallback nötig
      // ==========================================
      if (!eventListeners.isEmpty()) {
        Instant resultTime = clock.instant();
        Duration elapsed = Duration.between(snapshot.startTime(), resultTime);
        emitEvent(FallbackEvent.primarySucceeded(config.name(), elapsed, resultTime));
      }
      return result;
    }

    // ==========================================
    // SLOW-PATH: Result-Fallback notwendig
    // ==========================================
    Instant resultTime = clock.instant();
    snapshot = snapshot.withFallingBack(null, handler.name(), resultTime);
    emitEventIfListening(() -> FallbackEvent.resultFallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, resultTime));

    try {
      T fallbackResult = FallbackCore.invokeResultHandler(handler, result);
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.resultFallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredTime), recoveredTime));
      }
      return fallbackResult;

    } catch (Throwable fallbackEx) {
      Instant fbFailedTime = clock.instant();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedTime);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedTime), fallbackEx, fbFailedTime));
      }

      if (fallbackEx instanceof Exception e) throw e;
      if (fallbackEx instanceof Error err) throw err;
      throw new RuntimeException(fallbackEx);
    }
  }

  private T handlePrimaryFailure(FallbackSnapshot snapshot, Throwable primary) throws Exception {
    Instant failedTime = clock.instant();

    if (!eventListeners.isEmpty()) {
      Duration elapsed = Duration.between(snapshot.startTime(), failedTime);
      emitEvent(FallbackEvent.primaryFailed(config.name(), elapsed, primary, failedTime));
    }

    // DIREKTER LOOKUP
    FallbackExceptionHandler<T> handler = config.findHandlerForException(primary);

    if (handler == null) {
      if (!eventListeners.isEmpty()) {
        Duration elapsed = Duration.between(snapshot.startTime(), failedTime);
        emitEvent(FallbackEvent.noHandlerMatched(config.name(), elapsed, primary, failedTime));
      }
      if (primary instanceof Exception e) throw e;
      if (primary instanceof Error err) throw err;
      throw new RuntimeException(primary);
    }

    // ==========================================
    // SLOW-PATH: Exception-Fallback notwendig
    // ==========================================
    snapshot = snapshot.withFallingBack(primary, handler.name(), failedTime);
    emitEventIfListening(() -> FallbackEvent.fallbackInvoked(
        config.name(), handler.name(), Duration.ZERO, failedTime));

    try {
      T fallbackValue = FallbackCore.invokeExceptionHandler(handler, primary);
      Instant recoveredTime = clock.instant();
      snapshot = FallbackCore.recordFallbackSuccess(snapshot, recoveredTime);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackRecovered(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(recoveredTime), recoveredTime));
      }
      return fallbackValue;

    } catch (Throwable fallbackEx) {
      Instant fbFailedTime = clock.instant();
      snapshot = FallbackCore.recordFallbackFailure(snapshot, fallbackEx, fbFailedTime);

      if (!eventListeners.isEmpty()) {
        emitEvent(FallbackEvent.fallbackFailed(
            config.name(), handler.name(),
            snapshot.fallbackElapsed(fbFailedTime), fallbackEx, fbFailedTime));
      }

      throw new FallbackException(config.name(), primary, fallbackEx);
    }
  }

  public void onEvent(Consumer<FallbackEvent> listener) {
    eventListeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Helper method to avoid instantiating FallbackEvent objects if no listeners are present.
   * Useful for events that don't need pre-calculated variables.
   */
  private void emitEventIfListening(java.util.function.Supplier<FallbackEvent> eventSupplier) {
    if (!eventListeners.isEmpty()) {
      emitEvent(eventSupplier.get());
    }
  }

  private void emitEvent(FallbackEvent event) {
    for (Consumer<FallbackEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (Throwable t) {
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