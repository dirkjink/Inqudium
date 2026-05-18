package eu.inqudium.core.element.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Flowable<T>} shape of the
 * RxJava 3 paradigm. Zero-or-more values with Reactive Streams
 * backpressure support.
 *
 * <p>Singleton — use {@link RxJava3Tag#FLOWABLE}.</p>
 */
public final class RxJava3FlowableTag implements RxJava3Tag {

    /** The singleton instance. Package-private; use {@link RxJava3Tag#FLOWABLE}. */
    static final RxJava3FlowableTag INSTANCE = new RxJava3FlowableTag();

    private RxJava3FlowableTag() {
    }
}
