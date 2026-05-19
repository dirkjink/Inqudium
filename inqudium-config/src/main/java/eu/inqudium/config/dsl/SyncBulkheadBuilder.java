package eu.inqudium.config.dsl;

import eu.inqudium.core.element.paradigm.SyncTag;

/**
 * Sync-paradigm extension of {@link BulkheadBuilder}.
 *
 * <p>The sync variant currently adds no methods of its own — the
 * inherited setters cover every field of
 * {@link eu.inqudium.config.snapshot.BulkheadSnapshot BulkheadSnapshot}
 * including the strategy DSL ({@code .semaphore() / .codel(...) /
 * .adaptive(...) / .adaptiveNonBlocking(...)}). The sub-interface
 * exists so sync-only extensions can be added later without forcing
 * them onto the async, reactive, or coroutine variants.</p>
 *
 * @since 0.9.0
 */
public interface SyncBulkheadBuilder extends BulkheadBuilder<SyncTag> {
}
