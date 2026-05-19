package eu.inqudium.imperative.bulkhead.dsl;

import eu.inqudium.config.dsl.AsyncBulkheadBuilder;
import eu.inqudium.config.dsl.BulkheadBuilderBase;
import eu.inqudium.core.element.paradigm.AsyncTag;

/**
 * Concrete async-paradigm bulkhead builder. A thin shell over
 * {@link BulkheadBuilderBase BulkheadBuilderBase&lt;AsyncTag&gt;}
 * — every setter is inherited unchanged. See
 * {@link DefaultSyncBulkheadBuilder} for the shared-patch rationale.
 *
 * @since 0.9.0
 */
public final class DefaultAsyncBulkheadBuilder
        extends BulkheadBuilderBase<AsyncTag>
        implements AsyncBulkheadBuilder {

    /**
     * @param name the bulkhead's name; non-null and non-blank. Validated by the base class.
     */
    public DefaultAsyncBulkheadBuilder(String name) {
        super(name);
    }
}
