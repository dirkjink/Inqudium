package eu.inqudium.config.dsl;

import eu.inqudium.core.paradigm.AsyncTag;

/**
 * Async-paradigm extension of {@link BulkheadBuilder}.
 *
 * <p>The async variant currently adds no methods of its own — the
 * inherited setters cover every field of
 * {@link eu.inqudium.config.snapshot.BulkheadSnapshot BulkheadSnapshot}.
 * The sub-interface exists so async-only extensions can be added
 * later without forcing them onto the sync, reactive, or coroutine
 * variants.</p>
 *
 * <p>Per ADR-046, the {@link AsyncTag} paradigm covers methods whose
 * completion is signalled by stage-completion. Permit release on the
 * async path happens at stage completion, not at method-return — but
 * the bulkhead configuration is identical to the sync variant; only
 * the release hook differs.</p>
 *
 * @since 0.9.0
 */
public interface AsyncBulkheadBuilder extends BulkheadBuilder<AsyncTag> {
}
