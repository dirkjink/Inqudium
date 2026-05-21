package eu.inqudium.config.runtime;

import eu.inqudium.config.lifecycle.ChangeDecision;
import eu.inqudium.config.lifecycle.ChangeRequest;
import eu.inqudium.config.lifecycle.ChangeRequestListener;
import eu.inqudium.config.lifecycle.LifecycleState;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Synchronous-imperative handle on a {@link BulkheadComponent}.
 *
 * <p>Wraps the component and delegates every accessor to it. The
 * handle adds nothing functional — its purpose is the
 * paradigm-tagged compile-time identity ({@link SyncTag}) and the
 * {@link #target()} accessor to the component.</p>
 *
 * <p>Identity-equality semantics: two handles wrapping the same
 * component are equal iff their {@link #target()} reference is the
 * same instance.</p>
 *
 * <p>Package-private — instantiated only by the runtime classes
 * ({@code DefaultImperative}).</p>
 *
 * @since 0.10.0
 */
final class SyncBulkheadHandle implements BulkheadHandle<SyncTag> {

    private final BulkheadComponent component;

    SyncBulkheadHandle(BulkheadComponent component) {
        this.component = Objects.requireNonNull(component, "component");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends InqElement.Kind.Bulkhead> T target() {
        return (T) component;
    }

    @Override
    public String name() {
        return component.name();
    }

    @Override
    public InqElementType elementType() {
        return component.elementType();
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return component.eventPublisher();
    }

    @Override
    public Set<ParadigmTag> paradigmTags() {
        return Set.of(SyncTag.INSTANCE);
    }

    @Override
    public BulkheadSnapshot snapshot() {
        return component.snapshot();
    }

    @Override
    public int availablePermits() {
        return component.availablePermits();
    }

    @Override
    public int concurrentCalls() {
        return component.concurrentCalls();
    }

    @Override
    public LifecycleState lifecycleState() {
        return component.lifecycleState();
    }

    @Override
    public AutoCloseable onChangeRequest(ChangeRequestListener<BulkheadSnapshot> listener) {
        return component.onChangeRequest(listener);
    }

    @Override
    public List<ChangeRequestListener<BulkheadSnapshot>> listeners() {
        return component.listeners();
    }

    @Override
    public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
        return component.evaluate(request);
    }

    @Override
    public ChangeDecision evaluateRemoval(BulkheadSnapshot currentSnapshot) {
        return component.evaluateRemoval(currentSnapshot);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SyncBulkheadHandle other)) return false;
        return component == other.component;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(component);
    }
}
