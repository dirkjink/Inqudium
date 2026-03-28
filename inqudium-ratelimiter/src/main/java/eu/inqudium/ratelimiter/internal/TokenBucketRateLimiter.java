package eu.inqudium.ratelimiter.internal;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.ratelimiter.*;
import eu.inqudium.ratelimiter.RateLimiter;
import eu.inqudium.ratelimiter.event.RateLimiterOnPermitEvent;
import eu.inqudium.ratelimiter.event.RateLimiterOnRejectEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Token-bucket rate limiter using {@link AtomicReference} for lock-free state
 * management and {@link LockSupport#parkNanos} for waiting (ADR-008, ADR-019).
 *
 * @since 0.1.0
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final String name;
    private final RateLimiterConfig config;
    private final RateLimiterBehavior behavior;
    private final InqEventPublisher eventPublisher;
    private final AtomicReference<TokenBucketState> stateRef;

    public TokenBucketRateLimiter(String name, RateLimiterConfig config) {
        this.name = name;
        this.config = config;
        this.behavior = RateLimiterBehavior.defaultBehavior();
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.RATE_LIMITER);
        this.stateRef = new AtomicReference<>(TokenBucketState.initial(config));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public RateLimiterConfig getConfig() {
        return config;
    }

    @Override
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    @Override
    public void acquirePermit() {
        var callId = config.getCallIdGenerator().generate();
        var deadline = config.getTimeoutDuration().isZero()
                ? Instant.MIN
                : config.getClock().instant().plus(config.getTimeoutDuration());

        while (true) {
            var currentState = stateRef.get();
            var result = behavior.tryAcquire(currentState, config);

            if (result.permitted()) {
                if (stateRef.compareAndSet(currentState, result.updatedState())) {
                    eventPublisher.publish(new RateLimiterOnPermitEvent(
                            callId, name, result.updatedState().availableTokens(),
                            config.getClock().instant()));
                    return;
                }
                // CAS failed — retry the loop with fresh state
                continue;
            }

            // Denied — check if we should wait
            if (config.getTimeoutDuration().isZero()) {
                eventPublisher.publish(new RateLimiterOnRejectEvent(
                        callId, name, result.waitDuration(), config.getClock().instant()));
                throw new InqRequestNotPermittedException(name, result.waitDuration());
            }

            // Check timeout deadline
            var now = config.getClock().instant();
            if (now.isAfter(deadline)) {
                eventPublisher.publish(new RateLimiterOnRejectEvent(
                        callId, name, result.waitDuration(), now));
                throw new InqRequestNotPermittedException(name, result.waitDuration());
            }

            // Wait and retry — park for the estimated wait or remaining timeout, whichever is shorter
            var remaining = Duration.between(now, deadline);
            var parkDuration = result.waitDuration().compareTo(remaining) < 0
                    ? result.waitDuration() : remaining;
            LockSupport.parkNanos(parkDuration.toNanos());
        }
    }

    @Override
    public <T> Supplier<T> decorateSupplier(Supplier<T> supplier) {
        return () -> {
            acquirePermit();
            return supplier.get();
        };
    }

    @Override
    public Runnable decorateRunnable(Runnable runnable) {
        return () -> {
            acquirePermit();
            runnable.run();
        };
    }
}
