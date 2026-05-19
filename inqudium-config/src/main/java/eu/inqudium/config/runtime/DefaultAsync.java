package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.SyncTag;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Asynchronous-paradigm view over a {@link Sync} container.
 *
 * <p>The {@link Sync} container is the canonical home of imperative
 * bulkhead components (the imperative module's {@code DefaultImperative}
 * implements {@code Sync} directly). {@code DefaultAsync} wraps that
 * container as a façade and projects each handle as a
 * {@code BulkheadHandle<AsyncTag>} via {@link AsyncBulkheadHandle};
 * the underlying bulkhead instance is shared (Q.5a / ADR-046).</p>
 *
 * <p>{@link #snapshots()} returns an empty stream because the wrapped
 * {@link Sync} container is the single source of truth iterated by
 * cross-paradigm read paths; the view contributes no additional
 * snapshots of its own.</p>
 *
 * <p>Package-private — constructed only by {@link DefaultInqRuntime}.</p>
 *
 * @since 0.9.0
 */
final class DefaultAsync implements Async {

    private final Sync sync;

    DefaultAsync(Sync sync) {
        this.sync = Objects.requireNonNull(sync, "sync");
    }

    @Override
    public ParadigmTag paradigm() {
        return AsyncTag.INSTANCE;
    }

    @Override
    public BulkheadHandle<AsyncTag> bulkhead(String name) {
        return toAsyncHandle(sync.bulkhead(name));
    }

    @Override
    public Optional<BulkheadHandle<AsyncTag>> findBulkhead(String name) {
        return sync.findBulkhead(name).map(this::toAsyncHandle);
    }

    @Override
    public Set<String> bulkheadNames() {
        return sync.bulkheadNames();
    }

    @Override
    public ParadigmApplyResult applyUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        return sync.applyUpdate(general, patches);
    }

    @Override
    public ParadigmApplyResult dryRunUpdate(
            GeneralSnapshot general, ParadigmSectionPatches patches) {
        return sync.dryRunUpdate(general, patches);
    }

    @Override
    public Stream<? extends ComponentSnapshot> snapshots() {
        return Stream.empty();
    }

    private BulkheadHandle<AsyncTag> toAsyncHandle(BulkheadHandle<SyncTag> handle) {
        return BulkheadHandle.async(handle.target());
    }
}
