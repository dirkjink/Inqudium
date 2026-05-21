package eu.inqudium.core.paradigm;

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
