package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.InternalMutabilityCheck;
import eu.inqudium.config.lifecycle.LifecycleAware;
import eu.inqudium.config.lifecycle.ListenerRegistry;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElement;

/**
 * Paradigm-agnostic surface of a bulkhead resilience component.
 *
 * <p>A {@code BulkheadComponent} is the concrete, paradigm-spanning
 * implementation of a bulkhead — it owns the lifecycle phase, the
 * strategy, the live snapshot, and the decorator surfaces for every
 * paradigm family the component supports (sync via
 * {@code InqDecorator}, async via {@code InqAsyncDecorator}, and so
 * on as new paradigms come online).</p>
 *
 * <p>A {@link BulkheadHandle} is the paradigm-tagged wrapper that
 * exposes the component to user code. Multiple handles may point at
 * the same component — one per paradigm tag the component supports.
 * The component itself has exactly one instance per
 * {@code (paradigm-family, name)} registry key.</p>
 *
 * <p>The lifecycle, listener, and mutability super-interfaces are
 * paradigm-agnostic by design and are reused unchanged here. Together
 * they give the update dispatcher everything it needs to route a
 * patch through the runtime's veto chain without ever importing a
 * paradigm module.</p>
 *
 * @since 0.10.0
 */
public interface BulkheadComponent
        extends InqElement.Kind.Bulkhead,
                LifecycleAware,
                ListenerRegistry<BulkheadSnapshot>,
                InternalMutabilityCheck<BulkheadSnapshot> {

    /**
     * @return the bulkhead's current snapshot, read directly from the
     *         underlying live container.
     */
    BulkheadSnapshot snapshot();

    /**
     * @return the number of permits currently available. When the
     *         bulkhead is hot, the value comes from the live strategy;
     *         when cold, it falls back to the snapshot's
     *         {@code maxConcurrentCalls}.
     */
    int availablePermits();

    /**
     * @return the number of permits currently held by in-flight calls.
     *         Zero when cold.
     */
    int concurrentCalls();
}
