package eu.inqudium.config.runtime;

import eu.inqudium.core.element.paradigm.AsyncTag;

import java.util.Optional;
import java.util.Set;

/**
 * Asynchronous imperative paradigm container.
 *
 * <p>Returned by {@link InqRuntime#async()}. Provides typed access to the
 * imperative components configured for the runtime, viewed under the
 * {@link AsyncTag} paradigm — methods returning
 * {@link java.util.concurrent.CompletionStage}.</p>
 *
 * <p>The handles returned here are <strong>typed views</strong> over the
 * same underlying component instances that {@link Sync} returns under
 * the synchronous paradigm. Two views per bulkhead, one backing
 * instance — runtime updates propagate to both views simultaneously.</p>
 *
 * <p>Per ADR-046, the {@link AsyncTag} paradigm covers methods whose
 * completion is signalled by stage-completion via
 * {@code CompletionStage.whenComplete}. The same logical bulkhead's
 * permit-release semantics differ from the sync paradigm — sync releases
 * at method-return, async releases at stage-completion — but the
 * configuration (max permits, timeout, etc.) and runtime state (current
 * permits available, snapshot) are shared.</p>
 *
 * @since 0.9.0
 */
public interface Async extends ParadigmContainer<AsyncTag> {

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle.
     * @throws IllegalArgumentException if no bulkhead with this name is configured.
     */
    BulkheadHandle<AsyncTag> bulkhead(String name);

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle if one is configured, otherwise empty.
     */
    Optional<BulkheadHandle<AsyncTag>> findBulkhead(String name);

    /**
     * @return the names of every configured bulkhead, in registration order.
     */
    Set<String> bulkheadNames();
}
