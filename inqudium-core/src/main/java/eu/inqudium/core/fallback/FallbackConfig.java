package eu.inqudium.core.fallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Immutable configuration for a fallback provider instance.
 *
 * <p>Contains an ordered list of {@link FallbackHandler} entries.
 * On failure, handlers are evaluated in registration order; the first
 * matching handler is invoked.
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name     a human-readable identifier (used in exceptions and events)
 * @param handlers the ordered list of fallback handlers
 * @param <T>      the result type of the protected operation
 */
public record FallbackConfig<T>(
    String name,
    List<FallbackHandler<T>> handlers
) {

  public FallbackConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(handlers, "handlers must not be null");
    handlers = List.copyOf(handlers);
    if (handlers.isEmpty()) {
      throw new IllegalArgumentException("At least one fallback handler must be registered");
    }
  }

  public static <T> Builder<T> builder(String name) {
    return new Builder<>(name);
  }

  /**
   * Finds the first handler that matches the given throwable, or {@code null}.
   */
  public FallbackHandler<T> findHandlerForException(Throwable throwable) {
    for (FallbackHandler<T> handler : handlers) {
      if (handler.matchesException(throwable)) {
        return handler;
      }
    }
    return null;
  }

  /**
   * Finds the first handler that matches the given result, or {@code null}.
   */
  public FallbackHandler<T> findHandlerForResult(Object result) {
    for (FallbackHandler<T> handler : handlers) {
      if (handler.matchesResult(result)) {
        return handler;
      }
    }
    return null;
  }

  public static final class Builder<T> {
    private final String name;
    private final List<FallbackHandler<T>> handlers = new ArrayList<>();

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    /**
     * Registers a fallback for a specific exception type.
     *
     * <pre>{@code
     * .onException(IOException.class, ex -> "cached-response")
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <E extends Throwable> Builder<T> onException(
        Class<E> exceptionType,
        Function<E, T> fallback) {

      handlers.add((FallbackHandler<T>) new FallbackHandler.ForExceptionType<>(
          exceptionType.getSimpleName(), exceptionType, fallback));
      return this;
    }

    /**
     * Registers a named fallback for a specific exception type.
     */
    @SuppressWarnings("unchecked")
    public <E extends Throwable> Builder<T> onException(
        String handlerName,
        Class<E> exceptionType,
        Function<E, T> fallback) {

      handlers.add((FallbackHandler<T>) new FallbackHandler.ForExceptionType<>(
          handlerName, exceptionType, fallback));
      return this;
    }

    /**
     * Registers a fallback for exceptions matching a predicate.
     *
     * <pre>{@code
     * .onExceptionMatching(
     *     ex -> ex.getMessage().contains("timeout"),
     *     ex -> "timeout-fallback")
     * }</pre>
     */
    public Builder<T> onExceptionMatching(
        Predicate<Throwable> predicate,
        Function<Throwable, T> fallback) {

      handlers.add(new FallbackHandler.ForExceptionPredicate<>(
          "predicate-" + handlers.size(), predicate, fallback));
      return this;
    }

    /**
     * Registers a named fallback for exceptions matching a predicate.
     */
    public Builder<T> onExceptionMatching(
        String handlerName,
        Predicate<Throwable> predicate,
        Function<Throwable, T> fallback) {

      handlers.add(new FallbackHandler.ForExceptionPredicate<>(
          handlerName, predicate, fallback));
      return this;
    }

    /**
     * Registers a catch-all fallback that handles any exception.
     * Typically registered last as a default handler.
     *
     * <pre>{@code
     * .onAnyException(ex -> "default-fallback")
     * }</pre>
     */
    public Builder<T> onAnyException(Function<Throwable, T> fallback) {
      handlers.add(new FallbackHandler.CatchAll<>("catch-all", fallback));
      return this;
    }

    /**
     * Registers a named catch-all fallback.
     */
    public Builder<T> onAnyException(String handlerName, Function<Throwable, T> fallback) {
      handlers.add(new FallbackHandler.CatchAll<>(handlerName, fallback));
      return this;
    }

    /**
     * Registers a fallback for unacceptable results.
     *
     * <pre>{@code
     * .onResult(result -> result == null, () -> "default-value")
     * }</pre>
     */
    public Builder<T> onResult(Predicate<T> predicate, Supplier<T> fallback) {
      handlers.add(new FallbackHandler.ForResult<>(
          "result-" + handlers.size(), predicate, fallback));
      return this;
    }

    /**
     * Registers a named fallback for unacceptable results.
     */
    public Builder<T> onResult(String handlerName, Predicate<T> predicate, Supplier<T> fallback) {
      handlers.add(new FallbackHandler.ForResult<>(handlerName, predicate, fallback));
      return this;
    }

    /**
     * Registers a constant fallback value for any exception.
     *
     * <pre>{@code
     * .withDefault("fallback-value")
     * }</pre>
     */
    public Builder<T> withDefault(T value) {
      handlers.add(new FallbackHandler.ConstantValue<>("default", value));
      return this;
    }

    /**
     * Registers a named constant fallback value.
     */
    public Builder<T> withDefault(String handlerName, T value) {
      handlers.add(new FallbackHandler.ConstantValue<>(handlerName, value));
      return this;
    }

    public FallbackConfig<T> build() {
      return new FallbackConfig<>(name, handlers);
    }
  }
}
