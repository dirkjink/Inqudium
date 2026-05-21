package eu.inqudium.core.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Maybe<T>} shape of the
 * RxJava 3 paradigm. Optional single value: completes with one
 * value, empty, or error.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code RxJava3MaybeTagDefault}. Clients access
 * the canonical instance via {@link RxJava3Tag#MAYBE}.</p>
 */
public sealed interface RxJava3MaybeTag extends RxJava3Tag
        permits RxJava3MaybeTag.RxJava3MaybeTagDefault {
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
}
