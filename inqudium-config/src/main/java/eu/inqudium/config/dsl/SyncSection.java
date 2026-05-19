package eu.inqudium.config.dsl;

import java.util.function.Consumer;

/**
 * The DSL section for synchronous-paradigm components — entry through
 * which users declare and patch bulkheads (and, in later phases,
 * retries, circuit-breakers, etc.) that protect methods whose
 * completion is signalled by method-return.
 *
 * <p>Same contract as {@link ImperativeSection} regarding
 * config-vs-update modes, last-writer-wins semantics for mutually
 * exclusive operations on a name, and rescission of prior calls.
 * Per ADR-046, sync-paradigm components share their underlying state
 * with the corresponding async-paradigm components in the runtime —
 * configuring a bulkhead named {@code "foo"} via {@code .sync(...)}
 * configures the same component as a {@code .async(...)} call against
 * the same name. The DSL surfaces feed into a single shared patch
 * accumulator; per-name last-writer-wins applies across all three
 * surfaces ({@code .imperative(...)}, {@code .sync(...)},
 * {@code .async(...)}).</p>
 *
 * @since 0.9.0
 */
public interface SyncSection {

    /**
     * Configure or patch a bulkhead in this paradigm section. If
     * {@code name} was previously marked for removal in this traversal,
     * the removal is rescinded.
     *
     * @param name       the bulkhead's name; non-null and non-blank.
     * @param configurer fills the supplied builder.
     * @return this section, for chaining.
     */
    SyncSection bulkhead(String name, Consumer<SyncBulkheadBuilder> configurer);

    /**
     * Mark the named bulkhead for structural removal. See
     * {@link ImperativeSection#removeBulkhead(String)} for the full
     * removal contract — the sync and async sections route through the
     * same removal accumulator.
     *
     * @param name the bulkhead's name; non-null and non-blank.
     * @return this section, for chaining.
     */
    SyncSection removeBulkhead(String name);
}
