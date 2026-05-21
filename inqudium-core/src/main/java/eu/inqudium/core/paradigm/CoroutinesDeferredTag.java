package eu.inqudium.core.paradigm;

/**
 * The {@code kotlinx.coroutines.Deferred<T>} shape of the Kotlin
 * coroutines paradigm. Awaitable handle that completes with a
 * value or an exception.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code CoroutinesDeferredTagDefault}. Clients
 * access the canonical instance via {@link CoroutinesTag#DEFERRED}.</p>
 */
public sealed interface CoroutinesDeferredTag extends CoroutinesTag
        permits CoroutinesDeferredTag.CoroutinesDeferredTagDefault {
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
}
