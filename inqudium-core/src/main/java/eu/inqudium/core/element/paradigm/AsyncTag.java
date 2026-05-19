package eu.inqudium.core.element.paradigm;

/**
 * The asynchronous imperative paradigm. Methods returning a
 * {@link java.util.concurrent.CompletionStage}; the stage's
 * completion signals operation completion.
 *
 * <p>Permit-release happens at stage completion (success or
 * failure), via {@code whenComplete} or equivalent.</p>
 *
 * <p>Includes any {@code CompletionStage} subtype:
 * {@code CompletableFuture}, {@code MinimalCompletionStage}, and
 * user-defined implementations — all share the same completion
 * contract and require no sub-shape distinction.</p>
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code AsyncTagDefault}. Clients access the
 * canonical instance via {@link #INSTANCE}.</p>
 */
public sealed interface AsyncTag extends ParadigmTag
        permits AsyncTagDefault {

    /** The canonical singleton instance. */
    AsyncTag INSTANCE = AsyncTagDefault.INSTANCE;
}
