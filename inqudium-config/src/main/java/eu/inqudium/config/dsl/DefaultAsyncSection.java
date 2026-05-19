package eu.inqudium.config.dsl;

import eu.inqudium.config.spi.ParadigmProvider;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Async-paradigm façade over a shared {@link DefaultImperativeSection}.
 *
 * <p>See {@link DefaultSyncSection} for the shared-accumulator rationale
 * and naming-merge semantics. The async section differs only in the
 * builder type it requests from the provider and the
 * {@link AsyncBulkheadBuilder} surface it presents to user code.</p>
 *
 * @since 0.9.0
 */
public final class DefaultAsyncSection implements AsyncSection {

    private final DefaultImperativeSection wrapped;
    private final ParadigmProvider provider;

    public DefaultAsyncSection(DefaultImperativeSection wrapped, ParadigmProvider provider) {
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public AsyncSection bulkhead(String name, Consumer<AsyncBulkheadBuilder> configurer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(configurer, "configurer");
        BulkheadBuilderBase<?> base = provider.createAsyncBulkheadBuilder(name);
        AsyncBulkheadBuilder builder = (AsyncBulkheadBuilder) base;
        configurer.accept(builder);
        wrapped.applyBulkheadPatch(name, base.toPatch());
        return this;
    }

    @Override
    public AsyncSection removeBulkhead(String name) {
        wrapped.markBulkheadRemoval(name);
        return this;
    }
}
