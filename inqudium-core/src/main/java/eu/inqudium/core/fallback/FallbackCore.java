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

  // ======================== Lifecycle Transitions ========================

  /**
   * Starts the execution. Transitions IDLE → EXECUTING.
   *
   * @param now the current time
   * @return a snapshot in EXECUTING state
   */
  public static FallbackSnapshot start(Instant now) {
    return FallbackSnapshot.idle().withExecuting(now);
  }

  /**
   * Records a successful primary execution. Transitions EXECUTING → SUCCEEDED.
   *
   * @param snapshot the current snapshot (must be in EXECUTING)
   * @param now      the current time
   * @return a snapshot in SUCCEEDED state
   * @throws IllegalStateException if not in EXECUTING state
   */
  public static FallbackSnapshot recordPrimarySuccess(FallbackSnapshot snapshot, Instant now) {
    requireState(snapshot, FallbackState.EXECUTING, "recordPrimarySuccess");
    return snapshot.withSucceeded(now);
  }

  // ======================== Handler Resolution ========================

  /**
   * Resolves which fallback handler (if any) matches the primary failure
   * and transitions the snapshot accordingly.
   *
   * <p>If a matching handler is found, transitions to FALLING_BACK.
   * If no handler matches, transitions to UNHANDLED.
   *
   * @param snapshot the current snapshot (must be in EXECUTING)
   * @param config   the fallback configuration
   * @param failure  the primary operation's exception
   * @param now      the current time
   * @return a resolved result containing the handler and updated snapshot
   * @throws IllegalStateException if not in EXECUTING state
   */
  public static <T> HandlerResolution<T> resolveHandler(
      FallbackSnapshot snapshot,
      FallbackConfig<T> config,
      Throwable failure,
      Instant now) {

    requireState(snapshot, FallbackState.EXECUTING, "resolveHandler");

    FallbackHandler<T> handler = config.findHandlerForException(failure);
    if (handler == null) {
      FallbackSnapshot unhandled = snapshot.withUnhandled(failure, now);
      return new HandlerResolution<>(null, unhandled, false);
    }

    FallbackSnapshot fallingBack = snapshot.withFallingBack(failure, handler.name(), now);
    return new HandlerResolution<>(handler, fallingBack, true);
  }

  /**
   * Resolves which fallback handler (if any) matches the primary result
   * and transitions the snapshot accordingly.
   *
   * @param snapshot the current snapshot (must be in EXECUTING)
   * @param config   the fallback configuration
   * @param result   the primary operation's result to evaluate
   * @param now      the current time
   * @return a resolved result, or {@code null} if no handler matches (result is acceptable)
   * @throws IllegalStateException if not in EXECUTING state
   */
  public static <T> HandlerResolution<T> resolveResultHandler(
      FallbackSnapshot snapshot,
      FallbackConfig<T> config,
      Object result,
      Instant now) {

    requireState(snapshot, FallbackState.EXECUTING, "resolveResultHandler");

    FallbackHandler<T> handler = config.findHandlerForResult(result);
    if (handler == null) {
      return null; // Result is acceptable
    }

    FallbackSnapshot fallingBack = snapshot.withFallingBack(null, handler.name(), now);
    return new HandlerResolution<>(handler, fallingBack, true);
  }

  // ======================== Fallback Outcome ========================

  /**
   * Records that the fallback handler recovered successfully.
   * Transitions FALLING_BACK → RECOVERED.
   *
   * @param snapshot the current snapshot (must be in FALLING_BACK)
   * @param now      the current time
   * @return a snapshot in RECOVERED state
   * @throws IllegalStateException if not in FALLING_BACK state
   */
  public static FallbackSnapshot recordFallbackSuccess(FallbackSnapshot snapshot, Instant now) {
    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackSuccess");
    return snapshot.withRecovered(now);
  }

  /**
   * Records that the fallback handler itself failed.
   * Transitions FALLING_BACK → FALLBACK_FAILED.
   *
   * @param snapshot        the current snapshot (must be in FALLING_BACK)
   * @param fallbackFailure the exception thrown by the fallback handler
   * @param now             the current time
   * @return a snapshot in FALLBACK_FAILED state
   * @throws IllegalStateException if not in FALLING_BACK state
   */
  public static FallbackSnapshot recordFallbackFailure(
      FallbackSnapshot snapshot,
      Throwable fallbackFailure,
      Instant now) {

    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackFailure");
    return snapshot.withFallbackFailed(fallbackFailure, now);
  }

  // ======================== Handler Invocation (pure) ========================

  /**
   * Invokes the resolved exception-based handler and returns the fallback value.
   *
   * <p>This is a pure delegation method — it pattern-matches on the handler
   * type and calls the appropriate {@code apply} method.
   *
   * @param handler the resolved handler
   * @param failure the primary exception
   * @param <T>     the result type
   * @return the fallback value
   */
  @SuppressWarnings("unchecked")
  public static <T> T invokeExceptionHandler(FallbackHandler<T> handler, Throwable failure) {
    return switch (handler) {
      case FallbackHandler.ForExceptionType<T, ?> typed -> typed.apply(failure);
      case FallbackHandler.ForExceptionPredicate<T> predicated -> predicated.apply(failure);
      case FallbackHandler.CatchAll<T> catchAll -> catchAll.apply(failure);
      case FallbackHandler.ConstantValue<T> constant -> constant.apply();
      case FallbackHandler.ForResult<T> e ->
          throw new IllegalStateException("Cannot invoke result handler for an exception");
    };
  }

  /**
   * Invokes the resolved result-based handler and returns the fallback value.
   *
   * @param handler the resolved handler
   * @param <T>     the result type
   * @return the fallback value
   */
  public static <T> T invokeResultHandler(FallbackHandler<T> handler) {
    if (handler instanceof FallbackHandler.ForResult<T> resultHandler) {
      return resultHandler.apply();
    }
    throw new IllegalStateException("Expected a result handler but got: " + handler.getClass().getSimpleName());
  }

  // ======================== Internal ========================

  private static void requireState(FallbackSnapshot snapshot, FallbackState required, String operation) {
    if (snapshot.state() != required) {
      throw new IllegalStateException(
          "Cannot %s in state %s (expected %s)".formatted(operation, snapshot.state(), required));
    }
  }

  // ======================== Handler Resolution Record ========================

  /**
   * The result of resolving a fallback handler for a failure.
   *
   * @param handler  the matched handler, or {@code null} if no handler matched
   * @param snapshot the updated snapshot
   * @param matched  whether a handler was found
   * @param <T>      the result type
   */
  public record HandlerResolution<T>(
      FallbackHandler<T> handler,
      FallbackSnapshot snapshot,
      boolean matched
  ) {
  }
}
