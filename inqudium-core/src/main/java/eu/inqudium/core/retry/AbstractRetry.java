package eu.inqudium.core.retry;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.retry.event.RetryOnRetryEvent;
import eu.inqudium.core.retry.event.RetryOnSuccessEvent;

import java.time.Duration;

/**
 * Base implementation for all retry paradigms (imperative, Reactor, Kotlin, RxJava).
 *
 * <p>Contains the complete retry logic — attempt loop, backoff calculation,
 * event publishing, exception handling, and decoration. Paradigm modules only
 * provide the wait mechanism: {@link #waitBeforeRetry(Duration)}.
 *
 * <p>This separation ensures that retry logic, event publishing, and error
 * codes are implemented <strong>once</strong> in the core, not duplicated across
 * every paradigm module.
 *
 * <h2>Subclass contract</h2>
 * <ul>
 *   <li>{@link #waitBeforeRetry(Duration)} — block/suspend the caller for the
 *       given backoff duration before the next retry attempt.
 *       Imperative: {@code LockSupport.parkNanos}.
 *       Kotlin: {@code delay}. Reactor: {@code Mono.delay}.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public abstract class AbstractRetry implements InqDecorator {

  private final String name;
  private final RetryConfig config;
  private final RetryBehavior behavior;
  private final InqEventPublisher eventPublisher;

  protected AbstractRetry(String name, RetryConfig config) {
    this.name = name;
    this.config = config;
    this.behavior = RetryBehavior.defaultBehavior();
    this.eventPublisher = InqEventPublisher.create(name, InqElementType.RETRY);
  }

  // ── InqDecorator / InqElement ──

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InqElementType getElementType() {
    return InqElementType.RETRY;
  }

  @Override
  public InqEventPublisher getEventPublisher() {
    return eventPublisher;
  }

  public RetryConfig getConfig() {
    return config;
  }

  // ── Decoration — template method ──

  @Override
  public <T> InqCall<T> decorate(InqCall<T> call) {
    return call.withCallable(() -> executeCall(call));
  }

  /**
   * Core retry loop — iterates up to {@code maxAttempts}, applying backoff
   * between failures via {@link #waitBeforeRetry(Duration)}.
   *
   * <p>On success: publishes {@link RetryOnSuccessEvent} with the attempt number.
   * On each retry: publishes {@link RetryOnRetryEvent} with attempt, delay, and cause.
   * On exhaustion: throws {@link InqRetryExhaustedException} with total attempts
   * and the last exception.
   */
  private <T> T executeCall(InqCall<T> call) throws Exception {
    var callId = call.callId();
    Throwable lastException = null;

    for (int attempt = 1; attempt <= config.getMaxAttempts(); attempt++) {
      try {
        T result = call.callable().call();
        eventPublisher.publish(new RetryOnSuccessEvent(
            callId, name, attempt, config.getClock().instant()));
        return result;
      } catch (Exception e) {
        lastException = e;
        var maybeDelay = behavior.shouldRetry(attempt, e, config);
        if (maybeDelay.isEmpty()) {
          break;
        }

        var delay = maybeDelay.get();
        eventPublisher.publish(new RetryOnRetryEvent(
            callId, name, attempt, delay, e, config.getClock().instant()));
        waitBeforeRetry(delay);
      }
    }
    throw new InqRetryExhaustedException(call.callId(), name, config.getMaxAttempts(), lastException);
  }

  // ── Abstract — paradigm-specific wait mechanism ──

  /**
   * Waits for the given backoff duration before the next retry attempt.
   *
   * <p>Imperative: {@code LockSupport.parkNanos(duration.toNanos())}.
   * Kotlin: {@code delay(duration.toMillis())}.
   * Reactor: non-blocking delay via {@code Mono.delay}.
   *
   * @param duration the backoff duration to wait
   */
  protected abstract void waitBeforeRetry(Duration duration);
}
