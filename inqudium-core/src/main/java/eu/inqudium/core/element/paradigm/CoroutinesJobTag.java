package eu.inqudium.core.element.paradigm;

/**
 * The {@code kotlinx.coroutines.Job} shape of the Kotlin
 * coroutines paradigm. Lifecycle handle that signals completion
 * without carrying a result value.
 *
 * <p>Singleton — use {@link CoroutinesTag#JOB}.</p>
 */
public final class CoroutinesJobTag implements CoroutinesTag {

    /** The singleton instance. Package-private; use {@link CoroutinesTag#JOB}. */
    static final CoroutinesJobTag INSTANCE = new CoroutinesJobTag();

    private CoroutinesJobTag() {
    }
}
