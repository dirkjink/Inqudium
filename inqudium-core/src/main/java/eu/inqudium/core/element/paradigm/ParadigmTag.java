package eu.inqudium.core.element.paradigm;

/**
 * Sealed type-level identifier for the paradigm of a resilience-
 * protected method.
 *
 * <p>The library identifies four broad paradigms (plus one
 * coroutines variant), each with its own correctness contract:</p>
 *
 * <ul>
 *   <li>{@link SyncTag} — synchronous imperative. Method-return
 *       signals operation completion.</li>
 *   <li>{@link AsyncTag} — asynchronous imperative. The returned
 *       {@code CompletionStage<T>} signals operation completion.</li>
 *   <li>{@link ReactiveTag} — Project Reactor's {@code Mono<T>}
 *       and {@code Flux<T>}. Subscription-driven; terminal signal
 *       carries completion.</li>
 *   <li>{@link RxJava3Tag} — RxJava 3's five reactive types:
 *       {@code Single}, {@code Maybe}, {@code Completable},
 *       {@code Flowable}, {@code Observable}.</li>
 *   <li>{@link CoroutinesTag} — Kotlin coroutines:
 *       {@code suspend fun}, {@code Deferred<T>}, {@code Job},
 *       {@code Flow<T>}.</li>
 * </ul>
 *
 * <p>Tags carry no runtime data; they exist to make paradigm a
 * compile-time fact for routing (which dispatch chain) and
 * registry identity (the {@code (paradigm, name)} component
 * key).</p>
 *
 * <p>The sealed hierarchy is two levels deep:
 * {@code SyncTag} and {@code AsyncTag} are top-level concrete
 * tags with no sub-shapes;
 * {@code ReactiveTag}, {@code RxJava3Tag}, and {@code CoroutinesTag}
 * are sealed sub-family interfaces whose permitted concrete tags
 * name the paradigm's resilience-relevant return-type shapes.</p>
 *
 * <p>See ADR-046 for the rationale.</p>
 */
public sealed interface ParadigmTag
        permits SyncTag, AsyncTag, ReactiveTag, RxJava3Tag, CoroutinesTag {
}
