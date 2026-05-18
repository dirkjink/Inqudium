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
 * <p>Singleton — use {@link #INSTANCE}.</p>
 */
public final class AsyncTag implements ParadigmTag {

    /** The singleton instance. */
    public static final AsyncTag INSTANCE = new AsyncTag();

    private AsyncTag() {
    }
}
