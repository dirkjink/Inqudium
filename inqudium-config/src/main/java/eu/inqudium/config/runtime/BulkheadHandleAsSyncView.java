package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.paradigm.ImperativeTag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.List;
import java.util.Objects;

/**
 * Wraps a {@link BulkheadHandle} typed as {@link ImperativeTag} (the
 * imperative module's native handle type) as a typed
 * {@link BulkheadHandle} of {@link SyncTag}. Every accessor delegates
 * to the wrapped handle.
 *
 * <p>Identity-equality semantics: two wrappers around the same underlying
 * handle are equal iff their {@link #wrapped()} fields are the same
 * instance. {@link #hashCode()} returns the underlying handle's identity
 * hash so wrappers can be used as map keys with reference semantics.</p>
 *
 * <p>Package-private — instantiated only by
 * {@link DefaultSync#bulkhead(String)} / {@link DefaultSync#findBulkhead(String)}.</p>
 *
 * @since 0.9.0
 */
final class BulkheadHandleAsSyncView implements BulkheadHandle<SyncTag> {

    private final BulkheadHandle<ImperativeTag> wrapped;

    BulkheadHandleAsSyncView(BulkheadHandle<ImperativeTag> wrapped) {
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
    }

    BulkheadHandle<ImperativeTag> wrapped() {
        return wrapped;
    }

    // InqElement

    @Override
    public String name() {
        return wrapped.name();
    }

    @Override
    public InqElementType elementType() {
        return wrapped.elementType();
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return wrapped.eventPublisher();
    }

    // BulkheadHandle

    @Override
    public BulkheadSnapshot snapshot() {
        return wrapped.snapshot();
    }

    @Override
    public int availablePermits() {
        return wrapped.availablePermits();
    }

    @Override
    public int concurrentCalls() {
        return wrapped.concurrentCalls();
    }

    // LifecycleAware

    @Override
    public LifecycleState lifecycleState() {
        return wrapped.lifecycleState();
    }

    // ListenerRegistry

    @Override
    public AutoCloseable onChangeRequest(ChangeRequestListener<BulkheadSnapshot> listener) {
        return wrapped.onChangeRequest(listener);
    }

    @Override
    public List<ChangeRequestListener<BulkheadSnapshot>> listeners() {
        return wrapped.listeners();
    }

    // InternalMutabilityCheck

    @Override
    public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
        return wrapped.evaluate(request);
    }

    @Override
    public ChangeDecision evaluateRemoval(BulkheadSnapshot currentSnapshot) {
        return wrapped.evaluateRemoval(currentSnapshot);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BulkheadHandleAsSyncView other)) return false;
        return wrapped == other.wrapped;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(wrapped);
    }
}
