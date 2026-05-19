package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link RxJava3CompletableTag}.
 *
 * <p>Singleton — clients access via {@link RxJava3Tag#COMPLETABLE}.</p>
 */
final class RxJava3CompletableTagDefault implements RxJava3CompletableTag {

    static final RxJava3CompletableTagDefault INSTANCE =
            new RxJava3CompletableTagDefault();

    private RxJava3CompletableTagDefault() {
    }
}
