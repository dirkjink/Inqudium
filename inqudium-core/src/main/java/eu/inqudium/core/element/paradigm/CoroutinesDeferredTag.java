package eu.inqudium.core.element.paradigm;

/**
 * The {@code kotlinx.coroutines.Deferred<T>} shape of the Kotlin
 * coroutines paradigm. Awaitable handle that completes with a
 * value or an exception.
 *
 * <p>Singleton — use {@link CoroutinesTag#DEFERRED}.</p>
 */
public final class CoroutinesDeferredTag implements CoroutinesTag {

    /** The singleton instance. Package-private; use {@link CoroutinesTag#DEFERRED}. */
    static final CoroutinesDeferredTag INSTANCE = new CoroutinesDeferredTag();

    private CoroutinesDeferredTag() {
    }
}
