package eu.inqudium.core.element.paradigm;

/**
 * The {@code kotlinx.coroutines.flow.Flow<T>} shape of the
 * Kotlin coroutines paradigm. Cold asynchronous stream.
 *
 * <p>Singleton — use {@link CoroutinesTag#FLOW}.</p>
 */
public final class CoroutinesFlowTag implements CoroutinesTag {

    /** The singleton instance. Package-private; use {@link CoroutinesTag#FLOW}. */
    static final CoroutinesFlowTag INSTANCE = new CoroutinesFlowTag();

    private CoroutinesFlowTag() {
    }
}
