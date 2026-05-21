package eu.inqudium.core.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Flowable<T>} shape of the
 * RxJava 3 paradigm. Zero-or-more values with Reactive Streams
 * backpressure support.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code RxJava3FlowableTagDefault}. Clients
 * access the canonical instance via {@link RxJava3Tag#FLOWABLE}.</p>
 */
public sealed interface RxJava3FlowableTag extends RxJava3Tag
        permits RxJava3FlowableTag.RxJava3FlowableTagDefault {
    /**
     * Package-private default implementation of {@link RxJava3FlowableTag}.
     *
     * <p>Singleton — clients access via {@link RxJava3Tag#FLOWABLE}.</p>
     */
    final class RxJava3FlowableTagDefault implements RxJava3FlowableTag {

        static final RxJava3FlowableTagDefault INSTANCE = new RxJava3FlowableTagDefault();

        private RxJava3FlowableTagDefault() {
        }
    }
}
