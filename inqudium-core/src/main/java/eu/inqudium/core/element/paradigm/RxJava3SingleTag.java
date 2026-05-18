package eu.inqudium.core.element.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Single<T>} shape of the
 * RxJava 3 paradigm. Must produce exactly one value or an
 * error; never empty.
 *
 * <p>Singleton — use {@link RxJava3Tag#SINGLE}.</p>
 */
public final class RxJava3SingleTag implements RxJava3Tag {

    /** The singleton instance. Package-private; use {@link RxJava3Tag#SINGLE}. */
    static final RxJava3SingleTag INSTANCE = new RxJava3SingleTag();

    private RxJava3SingleTag() {
    }
}
