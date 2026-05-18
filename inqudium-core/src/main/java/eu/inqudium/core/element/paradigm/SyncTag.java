package eu.inqudium.core.element.paradigm;

/**
 * The synchronous imperative paradigm. Methods returning a direct
 * value (including {@code void}) whose completion is signalled by
 * return-from-method.
 *
 * <p>Permit-release happens at method-return. The
 * {@code CompletionStage}-counterpart is {@link AsyncTag}.</p>
 *
 * <p>Singleton — use {@link #INSTANCE}.</p>
 */
public final class SyncTag implements ParadigmTag {

    /** The singleton instance. */
    public static final SyncTag INSTANCE = new SyncTag();

    private SyncTag() {
    }
}
