package eu.inqudium.imperative.bulkhead.dsl;

import eu.inqudium.config.dsl.BulkheadBuilderBase;
import eu.inqudium.config.dsl.SyncBulkheadBuilder;
import eu.inqudium.core.paradigm.SyncTag;

/**
 * Concrete sync-paradigm bulkhead builder. A thin shell over
 * {@link BulkheadBuilderBase BulkheadBuilderBase&lt;SyncTag&gt;}
 * — every setter is inherited unchanged. Produces an ordinary
 * {@link eu.inqudium.config.patch.BulkheadPatch BulkheadPatch} via
 * {@link #toPatch()}, identical in shape to the patch produced by
 * the imperative or async variants; the runtime registry merges
 * patches across the three DSL surfaces under one underlying
 * bulkhead instance per name (Q.5a façade design).
 *
 * @since 0.9.0
 */
public final class DefaultSyncBulkheadBuilder
        extends BulkheadBuilderBase<SyncTag>
        implements SyncBulkheadBuilder {

    /**
     * @param name the bulkhead's name; non-null and non-blank. Validated by the base class.
     */
    public DefaultSyncBulkheadBuilder(String name) {
        super(name);
    }
}
