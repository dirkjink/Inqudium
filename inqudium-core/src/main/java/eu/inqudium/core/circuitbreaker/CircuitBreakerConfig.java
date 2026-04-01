package eu.inqudium.core.circuitbreaker;

import eu.inqudium.core.circuitbreaker.metrics.FailureMetrics;
import eu.inqudium.core.circuitbreaker.metrics.TimeBasedErrorRateMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable configuration for a circuit breaker instance.
 * Use {@link #builder(String)} to construct.
 */
public record CircuitBreakerConfig(
    String name,
    int failureThreshold,
    int successThresholdInHalfOpen,
    int permittedCallsInHalfOpen,
    Duration waitDurationInOpenState,
    Predicate<Throwable> recordFailurePredicate,
    Function<Instant, FailureMetrics> metricsFactory // <--- Die neue Factory
) {

  public CircuitBreakerConfig {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
    Objects.requireNonNull(recordFailurePredicate, "recordFailurePredicate must not be null");
    Objects.requireNonNull(metricsFactory, "metricsFactory must not be null");

    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be >= 1, got " + failureThreshold);
    }
    if (successThresholdInHalfOpen < 1) {
      throw new IllegalArgumentException("successThresholdInHalfOpen must be >= 1, got " + successThresholdInHalfOpen);
    }
    if (permittedCallsInHalfOpen < 1) {
      throw new IllegalArgumentException("permittedCallsInHalfOpen must be >= 1, got " + permittedCallsInHalfOpen);
    }
    if (permittedCallsInHalfOpen < successThresholdInHalfOpen) {
      throw new IllegalArgumentException(
          "permittedCallsInHalfOpen (%d) must be >= successThresholdInHalfOpen (%d), otherwise the circuit can never transition from HALF_OPEN back to CLOSED"
              .formatted(permittedCallsInHalfOpen, successThresholdInHalfOpen));
    }
    if (waitDurationInOpenState.isNegative() || waitDurationInOpenState.isZero()) {
      throw new IllegalArgumentException("waitDurationInOpenState must be positive");
    }
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public boolean shouldRecordAsFailure(Throwable throwable) {
    return recordFailurePredicate.test(throwable);
  }

  public static final class Builder {
    private final String name;

    // Standard-Werte sind nun auf die Time-Based Error Rate Metrik optimiert
    private int failureThreshold = 50; // 50% Ausfallrate
    private int successThresholdInHalfOpen = 3;
    private int permittedCallsInHalfOpen = 3;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    private Predicate<Throwable> recordFailurePredicate = e -> true;
    private boolean predicateSetViaConvenienceMethod = false;

    // Spezifische Einstellungen für die Standard-Metrik
    private int slidingWindowSeconds = 10;
    private int minimumNumberOfCalls = 10;

    // Erlaubt das Überschreiben der Metrik-Strategie
    private Function<Instant, FailureMetrics> customMetricsFactory = null;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name);
    }

    public Builder failureThreshold(int failureThreshold) {
      this.failureThreshold = failureThreshold;
      return this;
    }

    public Builder successThresholdInHalfOpen(int successThresholdInHalfOpen) {
      this.successThresholdInHalfOpen = successThresholdInHalfOpen;
      return this;
    }

    public Builder permittedCallsInHalfOpen(int permittedCallsInHalfOpen) {
      this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
      return this;
    }

    public Builder waitDurationInOpenState(Duration waitDurationInOpenState) {
      this.waitDurationInOpenState = waitDurationInOpenState;
      return this;
    }

    /**
     * Set the size of the time-based sliding window.
     * Only applies if no custom metrics factory is provided.
     */
    public Builder slidingWindow(Duration windowSize) {
      this.slidingWindowSeconds = (int) Math.max(1, windowSize.getSeconds());
      return this;
    }

    /**
     * Set the minimum number of calls required before the failure rate is evaluated.
     * Only applies if no custom metrics factory is provided.
     */
    public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
      this.minimumNumberOfCalls = minimumNumberOfCalls;
      return this;
    }

    /**
     * Provide a custom strategy for tracking failures.
     * Overrides the default Time-Based Error Rate algorithm.
     */
    public Builder metricsStrategy(Function<Instant, FailureMetrics> factory) {
      this.customMetricsFactory = Objects.requireNonNull(factory);
      return this;
    }

    public Builder recordFailurePredicate(Predicate<Throwable> recordFailurePredicate) {
      this.recordFailurePredicate = recordFailurePredicate;
      this.predicateSetViaConvenienceMethod = false;
      return this;
    }

    @SafeVarargs
    public final Builder recordExceptions(Class<? extends Throwable>... exceptionTypes) {
      // (Identisch zur vorherigen Implementierung)
      if (predicateSetViaConvenienceMethod) {
        throw new IllegalStateException("recordExceptions() and ignoreExceptions() cannot both be used on the same builder.");
      }
      this.recordFailurePredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) return true;
        }
        return false;
      };
      this.predicateSetViaConvenienceMethod = true;
      return this;
    }

    @SafeVarargs
    public final Builder ignoreExceptions(Class<? extends Throwable>... exceptionTypes) {
      // (Identisch zur vorherigen Implementierung)
      if (predicateSetViaConvenienceMethod) {
        throw new IllegalStateException("recordExceptions() and ignoreExceptions() cannot both be used on the same builder.");
      }
      this.recordFailurePredicate = throwable -> {
        for (Class<? extends Throwable> type : exceptionTypes) {
          if (type.isInstance(throwable)) return false;
        }
        return true;
      };
      this.predicateSetViaConvenienceMethod = true;
      return this;
    }

    public CircuitBreakerConfig build() {
      // Wenn der Nutzer keine eigene Factory gesetzt hat, nutzen wir den Goldstandard
      Function<Instant, FailureMetrics> factoryToUse = customMetricsFactory != null
          ? customMetricsFactory
          : now -> TimeBasedErrorRateMetrics.initial(slidingWindowSeconds, minimumNumberOfCalls, now);

      return new CircuitBreakerConfig(
          name,
          failureThreshold,
          successThresholdInHalfOpen,
          permittedCallsInHalfOpen,
          waitDurationInOpenState,
          recordFailurePredicate,
          factoryToUse
      );
    }
  }
}