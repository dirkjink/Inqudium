package eu.inqudium.core.element.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Single<T>} shape of the
 * RxJava 3 paradigm. Must produce exactly one value or an error;
 * never empty.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code RxJava3SingleTagDefault}. Clients access
 * the canonical instance via {@link RxJava3Tag#SINGLE}.</p>
 */
public sealed interface RxJava3SingleTag extends RxJava3Tag
        permits RxJava3SingleTagDefault {
}
