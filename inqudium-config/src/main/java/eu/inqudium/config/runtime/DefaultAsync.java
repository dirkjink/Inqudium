package eu.inqudium.config.runtime;

import eu.inqudium.config.snapshot.ComponentSnapshot;
import eu.inqudium.config.snapshot.GeneralSnapshot;
import eu.inqudium.config.spi.ParadigmSectionPatches;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.ParadigmTag;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Asynchronous-paradigm view over an {@link Imperative} container.
 * Read accessors and lifecycle operations pass through; the underlying
 * registry is shared with {@link DefaultSync}.
 *
 * <p>See {@link DefaultSync} for the façade-pattern rationale and the
 * empty-{@code snapshots()} convention. The async view differs only in
 * the paradigm tag and the wrapper type used to project the underlying
 * handles.</p>
 *
 * <p>Package-private — constructed only by {@link DefaultInqRuntime}.</p>
 *
 * @since 0.9.0
 */
final class DefaultAsync implements Async {

    private final Imperative imperative;

    DefaultAsync(Imperative imperative) {
        this.imperative = Objects.requireNonNull(imperative, "imperative");
    }

    @Override
    public ParadigmTag paradigm() {
        return AsyncTag.INSTANCE;
    }

    @Override
    public BulkheadHandle<AsyncTag> bulkhead(String name) {
        return toAsyncHandle(imperative.bulkhead(name));
    }

    @Override
    public Optional<BulkheadHandle<AsyncTag>> findBulkhead(String name) {
        return imperative.findBulkhead(name).map(this::toAsyncHandle);
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
        return Stream.empty();
    }

    private BulkheadHandle<AsyncTag> toAsyncHandle(
            BulkheadHandle<eu.inqudium.core.element.paradigm.ImperativeTag> handle) {
        return new BulkheadHandleAsAsyncView(handle);
    }
}
