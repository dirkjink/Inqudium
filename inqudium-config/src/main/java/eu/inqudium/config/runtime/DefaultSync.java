package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.SyncTag;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Synchronous-paradigm view over an {@link Imperative} container.
 * Read accessors and lifecycle operations pass through; the underlying
 * registry is shared with {@link DefaultAsync}.
 *
 * <p>Per Q.5a of {@code REFACTORING_PARADIGM_TAGGING.md}, this is a
 * thin façade — no state of its own. All bulkhead instances live in
 * the wrapped {@code Imperative}; this class projects them as
 * {@link BulkheadHandle} typed with {@link SyncTag} via
 * {@link BulkheadHandleAsSyncView}.</p>
 *
 * <p>{@link #snapshots()} returns an empty stream because the underlying
 * {@code Imperative} container is the single source of truth iterated
 * by cross-paradigm read paths; the view contributes no additional
 * snapshots of its own.</p>
 *
 * <p>Package-private — constructed only by {@link DefaultInqRuntime}.</p>
 *
 * @since 0.9.0
 */
final class DefaultSync implements Sync {

    private final Imperative imperative;

    DefaultSync(Imperative imperative) {
        this.imperative = Objects.requireNonNull(imperative, "imperative");
    }

    @Override
    public ParadigmTag paradigm() {
        return SyncTag.INSTANCE;
    }

    @Override
    public BulkheadHandle<SyncTag> bulkhead(String name) {
        return toSyncHandle(imperative.bulkhead(name));
    }

    @Override
    public Optional<BulkheadHandle<SyncTag>> findBulkhead(String name) {
        return imperative.findBulkhead(name).map(this::toSyncHandle);
    }

    @Override
    public Set<String> bulkheadNames() {
        return imperative.bulkheadNames();
    }

    @Override
    public ParadigmApplyResult applyUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        return imperative.applyUpdate(general, patches);
    }

    @Override
    public ParadigmApplyResult dryRunUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        return imperative.dryRunUpdate(general, patches);
    }

    @Override
    public Stream<? extends ComponentSnapshot> snapshots() {
        // The Imperative container is the single source of truth for
        // snapshot iteration; returning empty here prevents
        // cross-paradigm read paths from double-counting components
        // when Sync, Async, and Imperative views co-exist over the
        // same registry.
        return Stream.empty();
    }

    private BulkheadHandle<SyncTag> toSyncHandle(
            BulkheadHandle<eu.inqudium.core.element.paradigm.ImperativeTag> handle) {
        return new BulkheadHandleAsSyncView(handle);
    }
}
