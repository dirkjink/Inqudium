package eu.inqudium.core.element.paradigm;

/**
 * Package-private default implementation of {@link RxJava3SingleTag}.
 *
 * <p>Singleton — clients access via {@link RxJava3Tag#SINGLE}.</p>
 */
final class RxJava3SingleTagDefault implements RxJava3SingleTag {

    static final RxJava3SingleTagDefault INSTANCE = new RxJava3SingleTagDefault();

    private RxJava3SingleTagDefault() {
    }
}
