package eu.inqudium.core.element.paradigm;

/**
 * The {@code reactor.core.publisher.Mono<T>} shape of the
 * reactive paradigm. Zero-or-one value, single terminal signal.
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code ReactiveMonoTagDefault}. Clients access
 * the canonical instance via {@link ReactiveTag#MONO}.</p>
 */
public sealed interface ReactiveMonoTag extends ReactiveTag
        permits ReactiveMonoTagDefault {
}
