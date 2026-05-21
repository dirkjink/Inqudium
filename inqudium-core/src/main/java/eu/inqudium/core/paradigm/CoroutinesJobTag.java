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
        permits CoroutinesJobTagDefault {
}
