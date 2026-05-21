package eu.inqudium.core.paradigm;

/**
 * Package-private default implementation of {@link AsyncTag}.
 *
 * <p>Singleton — clients access via {@link AsyncTag#INSTANCE}.</p>
 */
final class AsyncTagDefault implements AsyncTag {

    static final AsyncTagDefault INSTANCE = new AsyncTagDefault();

    private AsyncTagDefault() {
    }
}
