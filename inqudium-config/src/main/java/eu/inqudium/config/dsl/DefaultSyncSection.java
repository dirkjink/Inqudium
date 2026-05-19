package eu.inqudium.config.dsl;

import eu.inqudium.config.spi.ParadigmProvider;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Default implementation of {@link SyncSection}. Routes
 * {@code .bulkhead(...)} / {@code .removeBulkhead(...)} calls through
 * a shared {@link BulkheadPatchAccumulator} that backs both the sync
 * and async DSL sections — the underlying registry is a single
 * shared instance (Q.5a façade design).
 *
 * @since 0.9.0
 */
public final class DefaultSyncSection implements SyncSection {

    private final BulkheadPatchAccumulator accumulator;
    private final ParadigmProvider provider;

    DefaultSyncSection(BulkheadPatchAccumulator accumulator, ParadigmProvider provider) {
        this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public SyncSection bulkhead(String name, Consumer<SyncBulkheadBuilder> configurer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(configurer, "configurer");
        BulkheadBuilderBase<?> base = provider.createSyncBulkheadBuilder(name);
        SyncBulkheadBuilder builder = (SyncBulkheadBuilder) base;
        configurer.accept(builder);
        accumulator.applyBulkheadPatch(name, base.toPatch());
        return this;
    }

    @Override
    public SyncSection removeBulkhead(String name) {
        accumulator.markBulkheadRemoval(name);
        return this;
    }
}
