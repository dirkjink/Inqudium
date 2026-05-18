package eu.inqudium.core.element.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Completable} shape of the
 * RxJava 3 paradigm. Signal-only: completes either successfully
 * or with an error; carries no value.
 *
 * <p>Singleton — use {@link RxJava3Tag#COMPLETABLE}.</p>
 */
public final class RxJava3CompletableTag implements RxJava3Tag {

    /** The singleton instance. Package-private; use {@link RxJava3Tag#COMPLETABLE}. */
    static final RxJava3CompletableTag INSTANCE = new RxJava3CompletableTag();

    private RxJava3CompletableTag() {
    }
}
