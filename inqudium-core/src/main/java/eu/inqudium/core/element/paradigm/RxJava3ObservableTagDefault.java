package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link RxJava3ObservableTag}.
 *
 * <p>Singleton — clients access via {@link RxJava3Tag#OBSERVABLE}.</p>
 */
final class RxJava3ObservableTagDefault implements RxJava3ObservableTag {

    static final RxJava3ObservableTagDefault INSTANCE =
            new RxJava3ObservableTagDefault();

    private RxJava3ObservableTagDefault() {
    }
}
