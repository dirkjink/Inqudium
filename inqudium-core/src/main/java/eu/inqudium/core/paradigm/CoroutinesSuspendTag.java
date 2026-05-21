package eu.inqudium.core.paradigm;

/**
 * The {@code suspend fun} shape of the Kotlin coroutines
 * paradigm. Detected at the JVM bytecode level by the
 * {@code kotlin.coroutines.Continuation} parameter that the
 * compiler appends to suspend functions.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code CoroutinesSuspendTagDefault}. Clients
 * access the canonical instance via {@link CoroutinesTag#SUSPEND}.</p>
 */
public sealed interface CoroutinesSuspendTag extends CoroutinesTag
        permits CoroutinesSuspendTag.CoroutinesSuspendTagDefault {
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
}
