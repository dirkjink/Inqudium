package eu.inqudium.config.dsl;

import eu.inqudium.config.spi.ParadigmProvider;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Default implementation of {@link AsyncSection}. See
 * {@link DefaultSyncSection} for the shared-accumulator rationale —
 * the async section differs only in the builder type it requests
 * from the provider and the {@link AsyncBulkheadBuilder} surface it
 * presents to user code.
 *
 * @since 0.9.0
 */
public final class DefaultAsyncSection implements AsyncSection {

    private final BulkheadPatchAccumulator accumulator;
    private final ParadigmProvider provider;

    DefaultAsyncSection(BulkheadPatchAccumulator accumulator, ParadigmProvider provider) {
        this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public AsyncSection bulkhead(String name, Consumer<AsyncBulkheadBuilder> configurer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(configurer, "configurer");
        BulkheadBuilderBase<?> base = provider.createAsyncBulkheadBuilder(name);
        AsyncBulkheadBuilder builder = (AsyncBulkheadBuilder) base;
        configurer.accept(builder);
        accumulator.applyBulkheadPatch(name, base.toPatch());
        return this;
    }

    @Override
    public AsyncSection removeBulkhead(String name) {
        accumulator.markBulkheadRemoval(name);
        return this;
    }
}
