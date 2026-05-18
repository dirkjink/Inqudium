package eu.inqudium.core.element.paradigm;

/**
 * The {@code suspend fun} shape of the Kotlin coroutines
 * paradigm. Detected at the JVM bytecode level by the
 * {@code kotlin.coroutines.Continuation} parameter that the
 * compiler appends to suspend functions.
 *
 * <p>Singleton — use {@link CoroutinesTag#SUSPEND}.</p>
 */
public final class CoroutinesSuspendTag implements CoroutinesTag {

    /** The singleton instance. Package-private; use {@link CoroutinesTag#SUSPEND}. */
    static final CoroutinesSuspendTag INSTANCE = new CoroutinesSuspendTag();

    private CoroutinesSuspendTag() {
    }
}
