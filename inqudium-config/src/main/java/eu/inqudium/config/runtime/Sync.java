package eu.inqudium.config.runtime;

import eu.inqudium.core.paradigm.SyncTag;

import java.util.Optional;
import java.util.Set;

/**
 * Synchronous imperative paradigm container.
 *
 * <p>Returned by {@link InqRuntime#sync()}. Provides typed access to the
 * imperative components configured for the runtime, viewed under the
 * {@link SyncTag} paradigm: bulkheads today, with retry, circuit-breaker,
 * rate-limiter, and time-limiter following the same shape in later
 * phases.</p>
 *
 * <p>The handles returned here are <strong>typed views</strong> over the
 * same underlying component instances that {@link Async} returns under
 * the asynchronous paradigm. Two views per bulkhead, one backing
 * instance — runtime updates propagate to both views simultaneously.</p>
 *
 * <p>Per ADR-046, the {@link SyncTag} paradigm covers methods whose
 * completion is signalled by method-return (including {@code void}
 * methods).</p>
 *
 * @since 0.9.0
 */
public interface Sync extends ParadigmContainer<SyncTag> {

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle.
     * @throws IllegalArgumentException if no bulkhead with this name is configured.
     */
    BulkheadHandle<SyncTag> bulkhead(String name);

    /**
     * @param name the bulkhead's name.
     * @return the bulkhead handle if one is configured, otherwise empty.
     */
    Optional<BulkheadHandle<SyncTag>> findBulkhead(String name);

    /**
     * @return the names of every configured bulkhead, in registration order.
     */
    Set<String> bulkheadNames();
}
