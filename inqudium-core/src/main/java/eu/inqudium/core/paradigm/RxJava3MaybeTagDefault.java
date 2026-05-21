package eu.inqudium.core.paradigm;

/**
 * Package-private default implementation of {@link RxJava3MaybeTag}.
 *
 * <p>Singleton — clients access via {@link RxJava3Tag#MAYBE}.</p>
 */
final class RxJava3MaybeTagDefault implements RxJava3MaybeTag {

    static final RxJava3MaybeTagDefault INSTANCE = new RxJava3MaybeTagDefault();

    private RxJava3MaybeTagDefault() {
    }
}
