package eu.inqudium.core.paradigm;

/**
 * The {@code reactor.core.publisher.Flux<T>} shape of the
 * reactive paradigm. Zero-or-more values, terminal completion
 * signal.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code ReactiveFluxTagDefault}. Clients access
 * the canonical instance via {@link ReactiveTag#FLUX}.</p>
 */
public sealed interface ReactiveFluxTag extends ReactiveTag
        permits ReactiveFluxTag.ReactiveFluxTagDefault {
    /**
     * Package-private default implementation of {@link ReactiveFluxTag}.
     *
     * <p>Singleton — clients access via {@link ReactiveTag#FLUX}.</p>
     */
    final class ReactiveFluxTagDefault implements ReactiveFluxTag {

        static final ReactiveFluxTagDefault INSTANCE = new ReactiveFluxTagDefault();

        private ReactiveFluxTagDefault() {
        }
    }
}
