package eu.inqudium.core.element.paradigm;

/**
 * The Project Reactor paradigm. Sealed sub-family with two
 * permitted concrete tags: {@link ReactiveMonoTag} for
 * {@code Mono<T>} (zero-or-one value) and {@link ReactiveFluxTag}
 * for {@code Flux<T>} (zero-or-more values).
 *
 * <p>Both share the subscription-driven execution model;
 * permit-release uses {@code doFinally} or equivalent. The
 * resilience element may treat both uniformly
 * ({@code BulkheadHandle<ReactiveTag>}) or differentiate by
 * sub-shape ({@code BulkheadHandle<ReactiveMonoTag>}) — the
 * sealed hierarchy preserves the distinction at the type level
 * for elements that need it.</p>
 */
public sealed interface ReactiveTag extends ParadigmTag
        permits ReactiveMonoTag, ReactiveFluxTag {

    /** The {@code Mono<T>} sub-shape. */
    ReactiveMonoTag MONO = ReactiveMonoTag.INSTANCE;

    /** The {@code Flux<T>} sub-shape. */
    ReactiveFluxTag FLUX = ReactiveFluxTag.INSTANCE;
}
