package eu.inqudium.core.element.paradigm;

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
        permits CoroutinesDeferredTagDefault {
}
