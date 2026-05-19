package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link ReactiveFluxTag}.
 *
 * <p>Singleton — clients access via {@link ReactiveTag#FLUX}.</p>
 */
final class ReactiveFluxTagDefault implements ReactiveFluxTag {

    static final ReactiveFluxTagDefault INSTANCE = new ReactiveFluxTagDefault();

    private ReactiveFluxTagDefault() {
    }
}
