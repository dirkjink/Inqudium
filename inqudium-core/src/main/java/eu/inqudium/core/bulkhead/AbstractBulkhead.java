package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqCall;
import eu.inqudium.core.InqElementType;
import eu.inqudium.core.bulkhead.event.BulkheadOnAcquireEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnRejectEvent;
import eu.inqudium.core.bulkhead.event.BulkheadOnReleaseEvent;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;

import java.time.Duration;

/**
 * Base implementation for all bulkhead paradigms (imperative, Reactor, Kotlin, RxJava).
 *
 * <p>Contains the complete bulkhead logic — decoration, event publishing, exception
 * handling, and state queries. Paradigm modules only implement the permit mechanism:
 * {@link #tryAcquirePermit(Duration)} and {@link #releasePermit()}.
 *
 * <p>This separation ensures that event publishing, error codes, and the acquire/release
 * contract are implemented <strong>once</strong> in the core, not duplicated across
 * every paradigm module.
 *
 * <h2>Subclass contract</h2>
 * <ul>
 *   <li>{@link #tryAcquirePermit(Duration)} — attempt to acquire a permit within the
 *       given timeout. Return {@code true} if acquired, {@code false} if denied.
 *       Must handle {@link InterruptedException} internally.</li>
 *   <li>{@link #releasePermit()} — release a previously acquired permit. Called exactly
 *       once per successful acquire, in a {@code finally} block.</li>
 *   <li>{@link #getConcurrentCalls()} — current number of in-flight calls.</li>
 *   <li>{@link #getAvailablePermits()} — number of permits currently available.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public abstract class AbstractBulkhead implements InqDecorator {

    private final String name;
    private final BulkheadConfig config;
    private final InqEventPublisher eventPublisher;

    protected AbstractBulkhead(String name, BulkheadConfig config) {
        this.name = name;
        this.config = config;
        this.eventPublisher = InqEventPublisher.create(name, InqElementType.BULKHEAD);
    }

    // ── InqDecorator / InqElement ──

    @Override
    public String getName() {
        return name;
    }

    @Override
    public InqElementType getElementType() {
        return InqElementType.BULKHEAD;
    }

    @Override
    public InqEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    public BulkheadConfig getConfig() {
        return config;
    }

    // ── Decoration — template method ──

    @Override
    public <T> InqCall<T> decorate(InqCall<T> call) {
        return call.withCallable(() -> {
            doAcquire(call.callId());
            try {
                return call.callable().call();
            } finally {
                doRelease(call.callId());
            }
        });
    }

    private void doAcquire(String callId) {
        boolean acquired = tryAcquirePermit(config.getMaxWaitDuration());

        if (!acquired) {
            int concurrent = getConcurrentCalls();
            eventPublisher.publish(new BulkheadOnRejectEvent(
                    callId, name, concurrent, config.getClock().instant()));
            throw new InqBulkheadFullException(
                    callId, name, concurrent, config.getMaxConcurrentCalls());
        }

        eventPublisher.publish(new BulkheadOnAcquireEvent(
                callId, name, getConcurrentCalls(), config.getClock().instant()));
    }

    private void doRelease(String callId) {
        releasePermit();
        eventPublisher.publish(new BulkheadOnReleaseEvent(
                callId, name, getConcurrentCalls(), config.getClock().instant()));
    }

    // ── Abstract — paradigm-specific permit mechanism ──

    /**
     * Attempts to acquire a permit within the given timeout.
     *
     * <p>Implementations must handle {@link InterruptedException} internally
     * (restore interrupt flag and return {@code false}).
     *
     * @param timeout the maximum time to wait for a permit ({@link Duration#ZERO} for no waiting)
     * @return {@code true} if the permit was acquired, {@code false} if denied
     */
    protected abstract boolean tryAcquirePermit(Duration timeout);

    /**
     * Releases a previously acquired permit.
     *
     * <p>Called exactly once per successful {@link #tryAcquirePermit} in a
     * {@code finally} block. Implementations must be idempotent-safe.
     */
    protected abstract void releasePermit();

    /**
     * Returns the current number of in-flight calls.
     *
     * @return the number of concurrently active calls
     */
    public abstract int getConcurrentCalls();

    /**
     * Returns the number of permits currently available.
     *
     * @return the available permit count
     */
    public abstract int getAvailablePermits();
}
