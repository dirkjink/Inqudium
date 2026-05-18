package eu.inqudium.core.element.paradigm;

/**
 * Legacy type tag identifying the imperative paradigm. The tag predates
 * the split into {@link SyncTag} and {@link AsyncTag} introduced by
 * ADR-046 §2 and is retained transitionally so existing consumers
 * (DSL builders, runtime maps, integration examples) continue to
 * compile and run while the migration progresses.
 *
 * <p>New code should choose {@link SyncTag} or {@link AsyncTag}
 * explicitly. The {@code ImperativeTag} permit is removed in a future
 * release; consumers are migrated by Q.5b / Q.7 of
 * {@code REFACTORING_PARADIGM_TAGGING.md}.</p>
 *
 * <p>The tag is a singleton enum: it carries no runtime data, has free
 * {@code equals}/{@code hashCode}, and produces a stable identity for
 * use in maps and switch statements.</p>
 *
 * @deprecated since 0.9.0. Use {@link SyncTag#INSTANCE} or
 *             {@link AsyncTag#INSTANCE} instead.
 */
@Deprecated(since = "0.9.0")
public enum ImperativeTag implements ParadigmTag {

    /** The single imperative tag instance. */
    INSTANCE
}
