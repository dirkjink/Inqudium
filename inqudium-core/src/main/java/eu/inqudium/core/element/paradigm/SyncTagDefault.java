package eu.inqudium.core.element.paradigm;

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
