package eu.inqudium.config.dsl;

import eu.inqudium.config.spi.ParadigmProvider;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Sync-paradigm façade over a shared {@link DefaultImperativeSection}.
 *
 * <p>Holds no state of its own — every operation routes through the
 * wrapped imperative section's package-private accumulator helpers.
 * The shared underlying section means that
 * {@code .sync(...).bulkhead("foo", ...)} and
 * {@code .async(...).bulkhead("foo", ...)} in the same builder
 * traversal configure the same component; per-name last-writer-wins
 * applies across all three DSL surfaces.</p>
 *
 * @since 0.9.0
 */
public final class DefaultSyncSection implements SyncSection {

    private final DefaultImperativeSection wrapped;
    private final ParadigmProvider provider;

    public DefaultSyncSection(DefaultImperativeSection wrapped, ParadigmProvider provider) {
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public SyncSection bulkhead(String name, Consumer<SyncBulkheadBuilder> configurer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(configurer, "configurer");
        BulkheadBuilderBase<?> base = provider.createSyncBulkheadBuilder(name);
        // Safe by SPI contract: ImperativeProvider.createSyncBulkheadBuilder returns
        // an instance implementing SyncBulkheadBuilder.
        SyncBulkheadBuilder builder = (SyncBulkheadBuilder) base;
        configurer.accept(builder);
        wrapped.applyBulkheadPatch(name, base.toPatch());
        return this;
    }

    @Override
    public SyncSection removeBulkhead(String name) {
        wrapped.markBulkheadRemoval(name);
        return this;
    }
}
