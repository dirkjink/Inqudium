package eu.inqudium.core.paradigm;

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
