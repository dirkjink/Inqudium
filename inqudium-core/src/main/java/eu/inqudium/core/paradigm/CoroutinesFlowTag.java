package eu.inqudium.core.paradigm;

/**
 * The {@code kotlinx.coroutines.flow.Flow<T>} shape of the
 * Kotlin coroutines paradigm. Cold asynchronous stream.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code CoroutinesFlowTagDefault}. Clients
 * access the canonical instance via {@link CoroutinesTag#FLOW}.</p>
 */
public sealed interface CoroutinesFlowTag extends CoroutinesTag
        permits CoroutinesFlowTag.CoroutinesFlowTagDefault {
    /**
     * Package-private default implementation of {@link CoroutinesFlowTag}.
     *
     * <p>Singleton — clients access via {@link CoroutinesTag#FLOW}.</p>
     */
    final class CoroutinesFlowTagDefault implements CoroutinesFlowTag {

        static final CoroutinesFlowTagDefault INSTANCE = new CoroutinesFlowTagDefault();

        private CoroutinesFlowTagDefault() {
        }
    }
}
