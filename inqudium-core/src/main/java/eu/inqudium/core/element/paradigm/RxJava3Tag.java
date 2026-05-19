package eu.inqudium.core.element.paradigm;

/**
 * The RxJava 3 paradigm. Sealed sub-family with five permitted
 * concrete tags, one per resilience-relevant return-type shape:
 *
 * <ul>
 *   <li>{@link RxJava3SingleTag} — {@code Single<T>}, must
 *       produce a value or error.</li>
 *   <li>{@link RxJava3MaybeTag} — {@code Maybe<T>}, optional
 *       single value.</li>
 *   <li>{@link RxJava3CompletableTag} — {@code Completable},
 *       signal-only.</li>
 *   <li>{@link RxJava3FlowableTag} — {@code Flowable<T>},
 *       backpressure-aware stream.</li>
 *   <li>{@link RxJava3ObservableTag} — {@code Observable<T>},
 *       stream without backpressure.</li>
 * </ul>
 *
 * <p>All five share subscription-driven execution;
 * permit-release uses {@code doFinally} or equivalent. The
 * resilience element may treat all five uniformly
 * ({@code BulkheadHandle<RxJava3Tag>}) or differentiate by
 * sub-shape — the sealed hierarchy preserves the distinction at
 * the type level for elements that need it.</p>
 */
public sealed interface RxJava3Tag extends ParadigmTag
        permits RxJava3SingleTag, RxJava3MaybeTag,
                RxJava3CompletableTag, RxJava3FlowableTag,
                RxJava3ObservableTag {

    /** The {@code Single<T>} sub-shape. */
    RxJava3SingleTag SINGLE = RxJava3SingleTagDefault.INSTANCE;

    /** The {@code Maybe<T>} sub-shape. */
    RxJava3MaybeTag MAYBE = RxJava3MaybeTagDefault.INSTANCE;

    /** The {@code Completable} sub-shape. */
    RxJava3CompletableTag COMPLETABLE = RxJava3CompletableTagDefault.INSTANCE;

    /** The {@code Flowable<T>} sub-shape. */
    RxJava3FlowableTag FLOWABLE = RxJava3FlowableTagDefault.INSTANCE;

    /** The {@code Observable<T>} sub-shape. */
    RxJava3ObservableTag OBSERVABLE = RxJava3ObservableTagDefault.INSTANCE;
}
