package eu.inqudium.retry.internal;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.retry.RetryBehavior;
import eu.inqudium.core.retry.RetryConfig;
import eu.inqudium.core.retry.InqRetryExhaustedException;
import eu.inqudium.retry.Retry;
import eu.inqudium.retry.event.RetryOnRetryEvent;
import eu.inqudium.retry.event.RetryOnSuccessEvent;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Imperative retry implementation.
 *
 * <p>Waits between attempts using {@link LockSupport#parkNanos(long)} — virtual-thread
 * safe, no carrier-thread pinning (ADR-008).
 *
 * @since 0.1.0
 */
public final class RetryImpl implements Retry {

    private final String name;
    private final RetryConfig config;
    private final RetryBehavior behavior;
    private final InqEventPublisher eventPublisher;

    public RetryImpl(String name, RetryConfig config) {
        this.name = name;
        this.config = config;
        this.behavior = RetryBehavior.defaultBehavior();
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.RETRY);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public RetryConfig getConfig() {
        return config;
    }

    @Override
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    @Override
    public <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return () -> {
            var callId = UUID.randomUUID().toString();
            Throwable lastException = null;

            for (int attempt = 1; attempt <= config.getMaxAttempts(); attempt++) {
                try {
                    T result = supplier.get();
                    eventPublisher.publish(new RetryOnSuccessEvent(
                            callId, name, attempt, java.time.Instant.now()));
                    return result;
                } catch (Exception e) {
                    lastException = e;

                    var maybeDelay = behavior.shouldRetry(attempt, e, config);
                    if (maybeDelay.isEmpty()) {
                        break; // no retry — fall through to exhausted
                    }

                    var delay = maybeDelay.get();
                    eventPublisher.publish(new RetryOnRetryEvent(
                            callId, name, attempt, delay, e, java.time.Instant.now()));

                    // Wait using LockSupport — virtual-thread safe (ADR-008)
                    LockSupport.parkNanos(delay.toNanos());
                }
            }

            throw new InqRetryExhaustedException(name, config.getMaxAttempts(), lastException);
        };
    }

    @Override
    public <T> Supplier<T> decorateCallable(Callable<T> callable) {
        return decorateSupplier(() -> {
            try {
                return callable.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Runnable decorateRunnable(Runnable runnable) {
        Supplier<Void> supplier = decorateSupplier(() -> {
            runnable.run();
            return null;
        });
        return supplier::get;
    }
}
