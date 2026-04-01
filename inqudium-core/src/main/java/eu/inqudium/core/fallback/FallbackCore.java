package eu.inqudium.core.fallback;

/**
 * Pure functional core of the fallback provider.
 */
public final class FallbackCore {

  private FallbackCore() {
    // Utility class — not instantiable
  }

  public static FallbackSnapshot start(long currentNanos) {
    return FallbackSnapshot.idle().withExecuting(currentNanos);
  }

  public static FallbackSnapshot recordPrimarySuccess(FallbackSnapshot snapshot, long currentNanos) {
    requireState(snapshot, FallbackState.EXECUTING, "recordPrimarySuccess");
    return snapshot.withSucceeded(currentNanos);
  }

  public static FallbackSnapshot recordFallbackSuccess(FallbackSnapshot snapshot, long currentNanos) {
    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackSuccess");
    return snapshot.withRecovered(currentNanos);
  }

  public static FallbackSnapshot recordFallbackFailure(
      FallbackSnapshot snapshot,
      Throwable fallbackFailure,
      long currentNanos) {

    requireState(snapshot, FallbackState.FALLING_BACK, "recordFallbackFailure");
    return snapshot.withFallbackFailed(fallbackFailure, currentNanos);
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