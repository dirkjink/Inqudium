/**
 * Sealed type-level identifiers for resilience-method paradigms.
 *
 * <p>The {@link eu.inqudium.core.element.paradigm.ParadigmTag}
 * sealed interface and its sub-family interfaces
 * ({@link eu.inqudium.core.element.paradigm.ReactiveTag},
 * {@link eu.inqudium.core.element.paradigm.RxJava3Tag},
 * {@link eu.inqudium.core.element.paradigm.CoroutinesTag})
 * classify the programming style of a resilience-protected
 * method. The top-level {@link eu.inqudium.core.element.paradigm.SyncTag}
 * and {@link eu.inqudium.core.element.paradigm.AsyncTag} cover
 * the synchronous and {@code CompletionStage}-based imperative
 * paradigms.</p>
 *
 * <h2>Hierarchy</h2>
 *
 * <pre>
 * ParadigmTag (sealed)
 * ├── SyncTag                          (top-level, final)
 * ├── AsyncTag                         (top-level, final)
 * ├── ReactiveTag (sealed)
 * │   ├── ReactiveMonoTag
 * │   └── ReactiveFluxTag
 * ├── RxJava3Tag (sealed)
 * │   ├── RxJava3SingleTag
 * │   ├── RxJava3MaybeTag
 * │   ├── RxJava3CompletableTag
 * │   ├── RxJava3FlowableTag
 * │   └── RxJava3ObservableTag
 * └── CoroutinesTag (sealed)
 *     ├── CoroutinesSuspendTag
 *     ├── CoroutinesDeferredTag
 *     ├── CoroutinesJobTag
 *     └── CoroutinesFlowTag
 * </pre>
 *
 * <h2>Usage</h2>
 *
 * <p>Tags are singletons. Access via the constant declared on
 * the family interface:</p>
 *
 * <pre>
 * ParadigmTag tag = ReactiveTag.MONO;     // ReactiveMonoTag.INSTANCE
 * ParadigmTag tag = CoroutinesTag.JOB;    // CoroutinesJobTag.INSTANCE
 * ParadigmTag tag = SyncTag.INSTANCE;     // top-level access
 * </pre>
 *
 * <p>Exhaustive {@code switch} works at any level — pattern-
 * match the top-level interface, then nested switch on the
 * sub-family interface.</p>
 *
 * <p>See ADR-046 for the rationale.</p>
 */
package eu.inqudium.core.element.paradigm;
