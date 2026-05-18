package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.ImperativeTag;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.List;
import java.util.Objects;

/**
 * Wraps a {@link BulkheadHandle} typed as {@link ImperativeTag} as a
 * typed {@link BulkheadHandle} of {@link AsyncTag}. Every accessor
 * delegates to the wrapped handle.
 *
 * <p>Identity-equality semantics: two wrappers around the same underlying
 * handle are equal iff their {@link #wrapped()} fields are the same
 * instance.</p>
 *
 * <p>Package-private — instantiated only by
 * {@link DefaultAsync#bulkhead(String)} / {@link DefaultAsync#findBulkhead(String)}.</p>
 *
 * @since 0.9.0
 */
final class BulkheadHandleAsAsyncView implements BulkheadHandle<AsyncTag> {

    private final BulkheadHandle<ImperativeTag> wrapped;

    BulkheadHandleAsAsyncView(BulkheadHandle<ImperativeTag> wrapped) {
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
    }

    BulkheadHandle<ImperativeTag> wrapped() {
        return wrapped;
    }

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

    @Override
    public LifecycleState lifecycleState() {
        return wrapped.lifecycleState();
    }

    @Override
    public AutoCloseable onChangeRequest(ChangeRequestListener<BulkheadSnapshot> listener) {
        return wrapped.onChangeRequest(listener);
    }

    @Override
    public List<ChangeRequestListener<BulkheadSnapshot>> listeners() {
        return wrapped.listeners();
    }

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
        if (!(obj instanceof BulkheadHandleAsAsyncView other)) return false;
        return wrapped == other.wrapped;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(wrapped);
    }
}
