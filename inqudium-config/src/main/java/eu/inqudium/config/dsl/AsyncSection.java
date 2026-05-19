package eu.inqudium.config.dsl;

import java.util.function.Consumer;

/**
 * The DSL section for asynchronous-paradigm components — entry through
 * which users declare and patch bulkheads that protect methods returning
 * {@link java.util.concurrent.CompletionStage}.
 *
 * <p>See {@link SyncSection} for the shared-accumulator semantics across
 * the {@code .imperative(...) / .sync(...) / .async(...)} surfaces.</p>
 *
 * @since 0.9.0
 */
public interface AsyncSection {

    /**
     * Configure or patch a bulkhead in this paradigm section. If
     * {@code name} was previously marked for removal in this traversal,
     * the removal is rescinded.
     *
     * @param name       the bulkhead's name; non-null and non-blank.
     * @param configurer fills the supplied builder.
     * @return this section, for chaining.
     */
    AsyncSection bulkhead(String name, Consumer<AsyncBulkheadBuilder> configurer);

    /**
     * Mark the named bulkhead for structural removal.
     *
     * @param name the bulkhead's name; non-null and non-blank.
     * @return this section, for chaining.
     */
    AsyncSection removeBulkhead(String name);
}
