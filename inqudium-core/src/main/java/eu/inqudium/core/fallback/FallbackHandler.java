package eu.inqudium.core.fallback;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A single fallback handler entry that maps a condition to a recovery function.
 *
 * <p>Handlers are evaluated in registration order. The first handler whose
 * condition matches the failure (or result) is invoked.
 *
 * <p>This is a sealed hierarchy so the core can pattern-match exhaustively.
 */
public sealed interface FallbackHandler<T> {

  /**
   * Returns a descriptive name for this handler (used in events/logging).
   */
  String name();

  /**
   * Tests whether this handler matches the given throwable.
   * Returns {@code false} for result-based handlers.
   */
  boolean matchesException(Throwable throwable);

  /**
   * Tests whether this handler matches the given result.
   * Returns {@code false} for exception-based handlers.
   */
  boolean matchesResult(Object result);

  // ======================== Exception-based handlers ========================

  /**
   * Handles exceptions of a specific type.
   *
   * @param <T>           the result type
   * @param <E>           the exception type to handle
   * @param name          handler name
   * @param exceptionType the exception class to match (including subtypes)
   * @param fallback      function that receives the matched exception and produces a fallback value
   */
  record ForExceptionType<T, E extends Throwable>(
      String name,
      Class<E> exceptionType,
      Function<E, T> fallback
  ) implements FallbackHandler<T> {

    public ForExceptionType {
      Objects.requireNonNull(name);
      Objects.requireNonNull(exceptionType);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matchesException(Throwable throwable) {
      return exceptionType.isInstance(throwable);
    }

    @Override
    public boolean matchesResult(Object result) {
      return false;
    }

    @SuppressWarnings("unchecked")
    public T apply(Throwable throwable) {
      return fallback.apply((E) throwable);
    }
  }

  /**
   * Handles exceptions matching a custom predicate.
   *
   * @param <T>       the result type
   * @param name      handler name
   * @param predicate the predicate to test exceptions against
   * @param fallback  function that receives the matched exception and produces a fallback value
   */
  record ForExceptionPredicate<T>(
      String name,
      Predicate<Throwable> predicate,
      Function<Throwable, T> fallback
  ) implements FallbackHandler<T> {

    public ForExceptionPredicate {
      Objects.requireNonNull(name);
      Objects.requireNonNull(predicate);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matchesException(Throwable throwable) {
      return predicate.test(throwable);
    }

    @Override
    public boolean matchesResult(Object result) {
      return false;
    }

    public T apply(Throwable throwable) {
      return fallback.apply(throwable);
    }
  }

  /**
   * Catches all exceptions unconditionally.
   *
   * @param <T>      the result type
   * @param name     handler name
   * @param fallback function that receives the exception and produces a fallback value
   */
  record CatchAll<T>(
      String name,
      Function<Throwable, T> fallback
  ) implements FallbackHandler<T> {

    public CatchAll {
      Objects.requireNonNull(name);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matchesException(Throwable throwable) {
      return true;
    }

    @Override
    public boolean matchesResult(Object result) {
      return false;
    }

    public T apply(Throwable throwable) {
      return fallback.apply(throwable);
    }
  }

  // ======================== Result-based handler ========================

  /**
   * Handles unacceptable results (e.g. null, empty collections).
   *
   * @param <T>       the result type
   * @param name      handler name
   * @param predicate predicate that returns {@code true} for results requiring fallback
   * @param fallback  supplier that produces the fallback value
   */
  record ForResult<T>(
      String name,
      Predicate<T> predicate,
      Supplier<T> fallback
  ) implements FallbackHandler<T> {

    public ForResult {
      Objects.requireNonNull(name);
      Objects.requireNonNull(predicate);
      Objects.requireNonNull(fallback);
    }

    @Override
    public boolean matchesException(Throwable throwable) {
      return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean matchesResult(Object result) {
      return predicate.test((T) result);
    }

    public T apply() {
      return fallback.get();
    }
  }

  // ======================== Constant value handler ========================

  /**
   * Returns a constant fallback value for any exception.
   *
   * @param <T>   the result type
   * @param name  handler name
   * @param value the constant fallback value
   */
  record ConstantValue<T>(
      String name,
      T value
  ) implements FallbackHandler<T> {

    public ConstantValue {
      Objects.requireNonNull(name);
    }

    @Override
    public boolean matchesException(Throwable throwable) {
      return true;
    }

    @Override
    public boolean matchesResult(Object result) {
      return false;
    }

    public T apply() {
      return value;
    }
  }
}
