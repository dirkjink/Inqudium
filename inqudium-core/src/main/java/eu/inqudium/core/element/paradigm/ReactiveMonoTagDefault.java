package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link ReactiveMonoTag}.
 *
 * <p>Singleton — clients access via {@link ReactiveTag#MONO}.</p>
 */
final class ReactiveMonoTagDefault implements ReactiveMonoTag {

    static final ReactiveMonoTagDefault INSTANCE = new ReactiveMonoTagDefault();

    private ReactiveMonoTagDefault() {
    }
}
