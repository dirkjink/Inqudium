package eu.inqudium.core.element.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Maybe<T>} shape of the
 * RxJava 3 paradigm. Optional single value: completes with one
 * value, empty, or error.
 *
 * <p>Singleton — use {@link RxJava3Tag#MAYBE}.</p>
 */
public final class RxJava3MaybeTag implements RxJava3Tag {

    /** The singleton instance. Package-private; use {@link RxJava3Tag#MAYBE}. */
    static final RxJava3MaybeTag INSTANCE = new RxJava3MaybeTag();

    private RxJava3MaybeTag() {
    }
}
