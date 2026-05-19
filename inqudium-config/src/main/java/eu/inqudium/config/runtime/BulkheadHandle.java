package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.paradigm.ParadigmTag;

/**
 * Paradigm-agnostic read surface for a live bulkhead.
 *
 * <p>Every paradigm-specific bulkhead handle (the imperative {@code InqBulkhead}, and — in later
 * phases — {@code ReactiveBulkhead} and friends) implements this interface, parameterised by the
 * paradigm tag. The handle exposes only read accessors and listener-registration; paradigm-specific
 * {@code execute} signatures live on the concrete component because they differ in shape
 * (synchronous return vs. {@code Mono}/{@code Flux}/{@code suspend fun}).
 *
 * <p>The {@link LifecycleAware}, {@link ListenerRegistry}, and {@link InternalMutabilityCheck}
 * super-interfaces are paradigm-agnostic by design and are reused unchanged here. Together they
 * give the update dispatcher everything it needs to route a patch through ADR-028's veto chain
 * without ever importing a paradigm module.
 *
 * <p>{@link InqElement} contributes the {@code name()}, {@code elementType()}, and
 * {@code eventPublisher()} accessors (ADR-033 Stage 3). Every bulkhead handle's
 * {@link InqElement#elementType() elementType()} returns
 * {@link eu.inqudium.core.element.InqElementType#BULKHEAD} — the constraint is enforced by the
 * implementations, not the type system.
 *
 * @param <P> the paradigm tag.
 */
public interface BulkheadHandle<P extends ParadigmTag>
        extends InqElement,
        LifecycleAware,
        ListenerRegistry<BulkheadSnapshot>,
        InternalMutabilityCheck<BulkheadSnapshot> {

    /**
     * @return the bulkhead's current snapshot, read directly from the underlying live container.
     */
    BulkheadSnapshot snapshot();

    /**
     * @return the number of permits currently available. When the bulkhead is hot, the value
     *         comes from the live strategy; when cold, it falls back to the snapshot's
     *         {@code maxConcurrentCalls}.
     */
    int availablePermits();

    /**
     * @return the number of permits currently held by in-flight calls. Zero when cold.
     */
    int concurrentCalls();

    /**
     * Returns this handle as the requested type. Convenience method
     * that eliminates the cast pattern
     * <pre>{@code
     * InqBulkhead<Void, String> bh =
     *     (InqBulkhead<Void, String>) runtime.sync().bulkhead("foo");
     * }</pre>
     * which now reads as
     * <pre>{@code
     * InqBulkhead<Void, String> bh =
     *     runtime.sync().bulkhead("foo").unwrap(InqBulkhead.class);
     * }</pre>
     *
     * <p>The default implementation does a direct cast and throws
     * {@code ClassCastException} on mismatch. Wrapper implementations
     * (the {@link eu.inqudium.config.runtime.BulkheadHandleAsAsyncView}
     * that exposes a sync handle as a typed async view) override this
     * method to unwrap their delegate before casting, so async
     * callers receive the underlying concrete instance.</p>
     *
     * @param target the target class
     * @param <T> the target type
     * @return this handle as the target type
     * @throws ClassCastException if this handle's underlying
     *         implementation is not assignable to {@code target}
     * @throws NullPointerException if {@code target} is null
     *
     * @since 0.10.0
     */
    default <T> T unwrap(Class<T> target) {
        return target.cast(this);
    }
}
