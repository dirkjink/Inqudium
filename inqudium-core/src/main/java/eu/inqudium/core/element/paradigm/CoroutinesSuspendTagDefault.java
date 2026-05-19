package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link CoroutinesSuspendTag}.
 *
 * <p>Singleton — clients access via {@link CoroutinesTag#SUSPEND}.</p>
 */
final class CoroutinesSuspendTagDefault implements CoroutinesSuspendTag {

    static final CoroutinesSuspendTagDefault INSTANCE =
            new CoroutinesSuspendTagDefault();

    private CoroutinesSuspendTagDefault() {
    }
}
