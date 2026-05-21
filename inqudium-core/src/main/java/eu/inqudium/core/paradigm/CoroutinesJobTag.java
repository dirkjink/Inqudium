package eu.inqudium.core.paradigm;

/**
 * The {@code kotlinx.coroutines.Job} shape of the Kotlin
 * coroutines paradigm. Lifecycle handle that signals completion
 * without carrying a result value.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code CoroutinesJobTagDefault}. Clients access
 * the canonical instance via {@link CoroutinesTag#JOB}.</p>
 */
public sealed interface CoroutinesJobTag extends CoroutinesTag
        permits CoroutinesJobTag.CoroutinesJobTagDefault {
    /**
     * Package-private default implementation of {@link CoroutinesJobTag}.
     *
     * <p>Singleton — clients access via {@link CoroutinesTag#JOB}.</p>
     */
    final class CoroutinesJobTagDefault implements CoroutinesJobTag {

        static final CoroutinesJobTagDefault INSTANCE = new CoroutinesJobTagDefault();

        private CoroutinesJobTagDefault() {
        }
    }
}
