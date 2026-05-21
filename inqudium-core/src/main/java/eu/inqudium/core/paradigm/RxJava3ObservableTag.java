package eu.inqudium.core.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Observable<T>} shape of
 * the RxJava 3 paradigm. Zero-or-more values without
 * backpressure support.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code RxJava3ObservableTagDefault}. Clients
 * access the canonical instance via {@link RxJava3Tag#OBSERVABLE}.</p>
 */
public sealed interface RxJava3ObservableTag extends RxJava3Tag
        permits RxJava3ObservableTag.RxJava3ObservableTagDefault {
    /**
     * Package-private default implementation of {@link RxJava3ObservableTag}.
     *
     * <p>Singleton — clients access via {@link RxJava3Tag#OBSERVABLE}.</p>
     */
    final class RxJava3ObservableTagDefault implements RxJava3ObservableTag {

        static final RxJava3ObservableTagDefault INSTANCE =
                new RxJava3ObservableTagDefault();

        private RxJava3ObservableTagDefault() {
        }
    }
}
