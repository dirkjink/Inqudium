package eu.inqudium.core.paradigm;

/**
 * The synchronous imperative paradigm. Methods returning a direct
 * value (including {@code void}) whose completion is signalled by
 * return-from-method.
 *
 * <p>Permit-release happens at method-return. The
 * {@code CompletionStage}-counterpart is {@link AsyncTag}.</p>
 *
 * <p>Sealed interface with one package-private default
 * implementation, {@code SyncTagDefault}. Clients access the
 * canonical instance via {@link #INSTANCE}.</p>
 */
public sealed interface SyncTag extends ParadigmTag
        permits SyncTag.SyncTagDefault {

    /** The canonical singleton instance. */
    SyncTag INSTANCE = SyncTagDefault.INSTANCE;

    /**
     * Package-private default implementation of {@link SyncTag}.
     *
     * <p>Singleton — clients access via {@link SyncTag#INSTANCE}.</p>
     */
    final class SyncTagDefault implements SyncTag {

        static final SyncTagDefault INSTANCE = new SyncTagDefault();

        private SyncTagDefault() {
        }
    }
}
