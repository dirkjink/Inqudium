package eu.inqudium.core.element.paradigm;

/**
 * The {@code reactor.core.publisher.Mono<T>} shape of the
 * reactive paradigm. Zero-or-one value, single terminal signal.
 *
 * <p>Singleton — use {@link ReactiveTag#MONO}.</p>
 */
public final class ReactiveMonoTag implements ReactiveTag {

    /** The singleton instance. Package-private; use {@link ReactiveTag#MONO}. */
    static final ReactiveMonoTag INSTANCE = new ReactiveMonoTag();

    private ReactiveMonoTag() {
    }
}
