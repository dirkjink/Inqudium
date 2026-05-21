package eu.inqudium.core.paradigm;

/**
 * The {@code io.reactivex.rxjava3.core.Completable} shape of the
 * RxJava 3 paradigm. Signal-only: completes either successfully
 * or with an error; carries no value.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code RxJava3CompletableTagDefault}. Clients
 * access the canonical instance via {@link RxJava3Tag#COMPLETABLE}.</p>
 */
public sealed interface RxJava3CompletableTag extends RxJava3Tag
        permits RxJava3CompletableTagDefault {
}
