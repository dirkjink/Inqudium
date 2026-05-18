package eu.inqudium.core.element.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Observable<T>} shape of
 * the RxJava 3 paradigm. Zero-or-more values without
 * backpressure support.
 *
 * <p>Singleton — use {@link RxJava3Tag#OBSERVABLE}.</p>
 */
public final class RxJava3ObservableTag implements RxJava3Tag {

    /** The singleton instance. Package-private; use {@link RxJava3Tag#OBSERVABLE}. */
    static final RxJava3ObservableTag INSTANCE = new RxJava3ObservableTag();

    private RxJava3ObservableTag() {
    }
}
