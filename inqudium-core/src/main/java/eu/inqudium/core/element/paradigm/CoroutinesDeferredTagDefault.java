package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link CoroutinesDeferredTag}.
 *
 * <p>Singleton — clients access via {@link CoroutinesTag#DEFERRED}.</p>
 */
final class CoroutinesDeferredTagDefault implements CoroutinesDeferredTag {

    static final CoroutinesDeferredTagDefault INSTANCE =
            new CoroutinesDeferredTagDefault();

    private CoroutinesDeferredTagDefault() {
    }
}
