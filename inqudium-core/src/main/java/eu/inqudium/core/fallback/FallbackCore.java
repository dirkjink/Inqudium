package eu.inqudium.core.fallback;

import java.time.Instant;

/**
 * Pure functional core of the fallback provider.
 *
 * <p>All methods are static and side-effect-free. They manage the
 * per-execution fallback lifecycle as an immutable state machine:
 *
 * <pre>
 *   IDLE ──[start]──► EXECUTING ──[success]──────────────► SUCCEEDED
 *                         │
 *                         ├──[failure, handler found]──► FALLING_BACK ──[ok]──► RECOVERED
 *                         │                                   │
 *                         │                                   └──[fail]──► FALLBACK_FAILED
 *                         │
 *                         └──[failure, no handler]──► UNHANDLED
 * </pre>
 *
 * <p>The core does <strong>not</strong> invoke any handlers or callables.
 * It only manages state transitions and resolves which handler matches.
 * The wrappers are responsible for actually calling the primary operation
 * and the fallback handler.
 *
 * <p>Like TimeLimiter and Retry, there is no shared mutable state between
 * executions. Each execution gets its own {@link FallbackSnapshot}.
 */
public final class FallbackCore {

  private FallbackCore() {
    // Utility class — not instantiable
  }

  public static FallbackSnapshot start(Instant now) {
    return FallbackSnapshot.idle().withExecuting(now);
  }

  public static FallbackSnapshot recordPrimarySuccess(FallbackSnapshot snapshot, Instant now) {
    requireState(snapshot, FallbackState.EXECUTING, "recordPrimarySuccess");
    return snapshot.withSucceeded(now);
  }

  public static FallbackSnapshot recordFallbackSuccess(FallbackSnapshot snapshot, Instant now) {
    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackSuccess");
    return snapshot.withRecovered(now);
  }

  public static FallbackSnapshot recordFallbackFailure(
      FallbackSnapshot snapshot,
      Throwable fallbackFailure,
      Instant now) {

    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackFailure");
    return snapshot.withFallbackFailed(fallbackFailure, now);
  }

  @SuppressWarnings("unchecked")
  public static <T> T invokeExceptionHandler(FallbackExceptionHandler<T> handler, Throwable failure) {
    return switch (handler) {
      case FallbackExceptionHandler.ForExceptionType<T, ?> typed -> typed.apply(failure);
      case FallbackExceptionHandler.ForExceptionPredicate<T> predicated -> predicated.apply(failure);
      case FallbackExceptionHandler.CatchAll<T> catchAll -> catchAll.apply(failure);
      case FallbackExceptionHandler.ConstantValue<T> constant -> constant.apply();
    };
  }

  public static <T> T invokeResultHandler(FallbackResultHandler<T> handler, T result) {
    return handler.apply(result);
  }

  private static void requireState(FallbackSnapshot snapshot, FallbackState required, String operation) {
    if (snapshot.state() != required) {
      throw new IllegalStateException(
          "Cannot %s in state %s (expected %s)".formatted(operation, snapshot.state(), required));
    }
  }
}
