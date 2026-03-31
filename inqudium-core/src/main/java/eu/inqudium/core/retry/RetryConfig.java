package eu.inqudium.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Immutable configuration for a retry instance.
 *
 * <p>Use {@link #builder(String)} to construct.
 *
 * @param name            a human-readable identifier (used in exceptions and events)
 * @param maxAttempts     maximum number of attempts (initial call + retries);
 *                        e.g. 3 means 1 initial call + 2 retries
 * @param backoffStrategy the strategy for computing wait durations between retries
 * @param retryPredicate  predicate that decides whether a given throwable should
 *                        trigger a retry ({@code true} = retry)
 * @param resultPredicate predicate that decides whether a given result should
 *                        trigger a retry ({@code true} = retry); may be {@code null}
 */
public record RetryConfig(
    String name,
    int maxAttempts,
    BackoffStrategy backoffStrategy,
    Predicate<Throwable> retryPredicate,
    Predicate<Object> resultPredicate
) {

  public RetryConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(backoffStrategy, "backoffStrategy must not be null");
    Objects.requireNonNull(retryPredicate, "retryPredicate must not be null");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * Returns the maximum number of retries (maxAttempts - 1).
   */
  public int maxRetries() {
    return maxAttempts - 1;
  }

  /**
   * Checks whether the given throwable should trigger a retry.
   */
  public boolean shouldRetryOnException(Throwable throwable) {
    return retryPredicate.test(throwable);
  }

  /**
   * Checks whether the given result should trigger a retry.
   */
  public boolean shouldRetryOnResult(Object result) {
    return resultPredicate != null && resultPredicate.test(result);
  }

  public static final class Builder {
    private final String name;
    private int maxAttempts = 3;
    private BackoffStrategy backoffStrategy = BackoffStrategy.fixedDelay(Duration.ofMillis(500));
    private Predicate<Throwable> retryPredicate = e -> true;
    private Predicate<Object> resultPredicate = null;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    public Builder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder backoffStrategy(BackoffStrategy backoffStrategy) {
      this.backoffStrategy = backoffStrategy;
      return this;
    }

    public Builder fixedDelay(Duration delay) {
      this.backoffStrategy = BackoffStrategy.fixedDelay(delay);
      return this;
    }

    public Builder exponentialBackoff(Duration initialDelay) {
      this.backoffStrategy = BackoffStrategy.exponential(initialDelay);
      return this;
    }

    public Builder exponentialBackoff(Duration initialDelay, double multiplier, Duration maxDelay) {
      this.backoffStrategy = BackoffStrategy.exponential(initialDelay, multiplier, maxDelay);
      return this;
    }

    public Builder exponentialBackoffWithJitter(Duration initialDelay) {
      this.backoffStrategy = BackoffStrategy.exponentialWithJitter(initialDelay);
      return this;
    }

    public Builder noWait() {
      this.backoffStrategy = BackoffStrategy.noWait();
      return this;
    }

    public Builder retryPredicate(Predicate<Throwable> retryPredicate) {
      this.retryPredicate = retryPredicate;
      return this;
    }

    /**
     * Only retry on the specified exception types.
     */
    @SafeVarargs
    public final Builder retryOnExceptions(Class<? extends Throwable>... exceptionTypes) {
      this.retryPredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) {
            return true;
          }
        }
        return false;
      };
      return this;
    }

    /**
     * Do not retry on the specified exception types.
     */
    @SafeVarargs
    public final Builder ignoreExceptions(Class<? extends Throwable>... exceptionTypes) {
      this.retryPredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) {
            return false;
          }
        }
        return true;
      };
      return this;
    }

    /**
     * Retry when the result satisfies the given predicate.
     * Useful for retrying on specific return values (e.g. null, empty).
     */
    @SuppressWarnings("unchecked")
    public <T> Builder retryOnResult(Predicate<T> resultPredicate) {
      this.resultPredicate = (Predicate<Object>) (Predicate<?>) resultPredicate;
      return this;
    }

    public RetryConfig build() {
      return new RetryConfig(name, maxAttempts, backoffStrategy, retryPredicate, resultPredicate);
    }
  }
}
