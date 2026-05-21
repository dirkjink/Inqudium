package eu.inqudium.core.paradigm;

/**
 * Package-private default implementation of {@link RxJava3FlowableTag}.
 *
 * <p>Singleton — clients access via {@link RxJava3Tag#FLOWABLE}.</p>
 */
final class RxJava3FlowableTagDefault implements RxJava3FlowableTag {

    static final RxJava3FlowableTagDefault INSTANCE = new RxJava3FlowableTagDefault();

    private RxJava3FlowableTagDefault() {
    }
}
