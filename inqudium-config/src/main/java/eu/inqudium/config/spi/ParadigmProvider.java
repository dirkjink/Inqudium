package eu.inqudium.config.spi;

import eu.inqudium.config.dsl.BulkheadBuilderBase;
import eu.inqudium.config.runtime.ParadigmContainer;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.config.snapshot.GeneralSnapshot;

/**
 * Service-provider interface that links a paradigm module ({@code inqudium-imperative},
 * {@code inqudium-reactive}, {@code inqudium-rxjava3}, {@code inqudium-kotlin}) into the
 * runtime.
 *
 * <p>Each paradigm contributes one provider, registered via
 * {@code META-INF/services/eu.inqudium.config.spi.ParadigmProvider}. At runtime build time, the
 * top-level builder loads providers via {@link java.util.ServiceLoader}, matches each declared
 * paradigm section to the corresponding provider, and asks the provider to materialize a
 * {@link ParadigmContainer} from the section's accumulated patches plus the
 * {@link GeneralSnapshot}.
 *
 * <p>If a paradigm is referenced in the DSL but no provider for it is on the classpath, the
 * runtime raises {@link eu.inqudium.config.runtime.ParadigmUnavailableException
 * ParadigmUnavailableException} with a message naming the missing module.
 */
public interface ParadigmProvider {

    /**
     * @return the tag identifying the paradigm this provider materializes.
     */
    ParadigmTag paradigm();

    /**
     * Create a paradigm-specific bulkhead builder for the named bulkhead. The DSL section invokes
     * this on every {@code .bulkhead("name", ...)} call, hands the returned builder to the
     * user's configurer (after casting to the paradigm-specific sub-interface), then extracts
     * the resulting patch via {@link BulkheadBuilderBase#toPatch()}.
     *
     * @param name the bulkhead's name; non-null and non-blank.
     * @return a paradigm-specific {@code BulkheadBuilderBase} instance. The concrete return type
     *         implements the paradigm's bulkhead-builder sub-interface (e.g.
     *         {@code ImperativeBulkheadBuilder}).
     */
    BulkheadBuilderBase<?> createBulkheadBuilder(String name);

    /**
     * Create a sync-paradigm bulkhead builder for the named bulkhead.
     * Called by {@link eu.inqudium.config.dsl.DefaultSyncSection} on
     * every {@code .sync(...).bulkhead("name", ...)} call.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}. Paradigm providers that
     * implement sync-paradigm support override it to return a builder
     * implementing {@link eu.inqudium.config.dsl.SyncBulkheadBuilder}.
     * The {@link eu.inqudium.imperative.runtime.ImperativeProvider
     * ImperativeProvider} overrides it.</p>
     *
     * @param name the bulkhead's name; non-null and non-blank.
     * @return a paradigm-specific {@code BulkheadBuilderBase} instance
     *         implementing {@link eu.inqudium.config.dsl.SyncBulkheadBuilder}.
     *
     * @since 0.9.0
     */
    default BulkheadBuilderBase<?> createSyncBulkheadBuilder(String name) {
        throw new UnsupportedOperationException(
                "Paradigm provider " + getClass().getName()
                        + " does not implement createSyncBulkheadBuilder");
    }

    /**
     * Create an async-paradigm bulkhead builder for the named bulkhead.
     * Counterpart to {@link #createSyncBulkheadBuilder(String)} for
     * {@link eu.inqudium.config.dsl.DefaultAsyncSection}.
     *
     * @param name the bulkhead's name; non-null and non-blank.
     * @return a paradigm-specific {@code BulkheadBuilderBase} instance
     *         implementing {@link eu.inqudium.config.dsl.AsyncBulkheadBuilder}.
     *
     * @since 0.9.0
     */
    default BulkheadBuilderBase<?> createAsyncBulkheadBuilder(String name) {
        throw new UnsupportedOperationException(
                "Paradigm provider " + getClass().getName()
                        + " does not implement createAsyncBulkheadBuilder");
    }

    /**
     * Materialize a paradigm container from the given general snapshot and the patches the DSL
     * accumulated for this paradigm's section.
     *
     * @param general the runtime-level configuration (clock, event publisher, ...).
     * @param patches the patches for this paradigm's section, in registration order.
     * @return a paradigm container holding the materialized components.
     */
    ParadigmContainer<?> createContainer(GeneralSnapshot general, ParadigmSectionPatches patches);
}
