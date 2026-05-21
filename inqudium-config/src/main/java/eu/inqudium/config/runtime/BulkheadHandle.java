package eu.inqudium.config.runtime;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.paradigm.AsyncTag;
import eu.inqudium.core.paradigm.ParadigmTag;
import eu.inqudium.core.paradigm.SyncTag;

/**
 * Paradigm-tagged handle on a {@link BulkheadComponent}.
 *
 * <p>The handle is a lightweight wrapper around the underlying
 * component. It carries the paradigm tag at compile time and exposes
 * the component via {@link #target()}; every other accessor delegates
 * to the wrapped component (the handle adds nothing beyond
 * paradigm-identity and target-access).</p>
 *
 * <p>Multiple handles may point at the same component — one per
 * paradigm tag the component supports. The component itself has
 * exactly one instance per {@code (paradigm-family, name)} registry
 * key.</p>
 *
 * <h3>Paradigm tags</h3>
 *
 * <p>The type parameter {@code P} encodes the paradigm at compile
 * time. A {@code BulkheadHandle<SyncTag>} is obtained from the
 * imperative runtime's sync surface; a
 * {@code BulkheadHandle<AsyncTag>} from the async surface. Both may
 * back the same underlying component.</p>
 *
 * <h3>Accessing the component</h3>
 *
 * <p>Callers needing direct access to the component (e.g. for the
 * decorator methods or for testing lifecycle internals) use
 * {@link #target()}:</p>
 *
 * <pre>{@code
 * BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("payment");
 * InqBulkhead<String, Integer> bh = handle.target();
 * Function<String, Integer> wrapped = bh.decorateFunction(processor);
 * }</pre>
 *
 * <p>The caller's variable type witnesses the concrete component
 * class. Using a wrong type — assigning the result to a variable of
 * an unrelated bulkhead implementation — compiles but raises
 * {@code ClassCastException} at the assignment. The bound
 * {@code <T extends InqElement.Kind.Bulkhead>} catches structurally
 * non-bulkhead types at compile time (assigning to an
 * {@code InqElement.Kind.CircuitBreaker} variable fails to compile).</p>
 *
 * @param <P> the paradigm tag — {@link SyncTag},
 *            {@link AsyncTag}, etc.
 * @since 0.10.0
 */
public sealed interface BulkheadHandle<P extends ParadigmTag>
        extends BulkheadComponent
        permits SyncBulkheadHandle, AsyncBulkheadHandle {

    /**
     * Returns the underlying bulkhead component. The caller's variable
     * type witnesses the concrete component class.
     *
     * @param <T> the caller-witnessed component type, a subtype of
     *            {@link InqElement.Kind.Bulkhead}
     * @return the underlying bulkhead component, cast to {@code T}
     */
    <T extends InqElement.Kind.Bulkhead> T target();

    /**
     * Factory for {@code BulkheadHandle<SyncTag>}. Used by paradigm
     * runtime classes outside this package; the sealed concrete class
     * itself is package-private.
     *
     * @param component the underlying bulkhead component; non-null
     * @return a fresh sync handle wrapping {@code component}
     */
    static BulkheadHandle<SyncTag> sync(BulkheadComponent component) {
        return new SyncBulkheadHandle(component);
    }

    /**
     * Factory for {@code BulkheadHandle<AsyncTag>}. Used by paradigm
     * runtime classes outside this package; the sealed concrete class
     * itself is package-private.
     *
     * @param component the underlying bulkhead component; non-null
     * @return a fresh async handle wrapping {@code component}
     */
    static BulkheadHandle<AsyncTag> async(BulkheadComponent component) {
        return new AsyncBulkheadHandle(component);
    }
}
