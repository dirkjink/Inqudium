package eu.inqudium.core.element.paradigm;

/**
 * The {@code reactor.core.publisher.Flux<T>} shape of the
 * reactive paradigm. Zero-or-more values, terminal completion
 * signal.
 *
 * <p>Singleton — use {@link ReactiveTag#FLUX}.</p>
 */
public final class ReactiveFluxTag implements ReactiveTag {

    /** The singleton instance. Package-private; use {@link ReactiveTag#FLUX}. */
    static final ReactiveFluxTag INSTANCE = new ReactiveFluxTag();

    private ReactiveFluxTag() {
    }
}
