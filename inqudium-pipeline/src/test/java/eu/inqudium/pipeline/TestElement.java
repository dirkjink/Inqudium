package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.core.event.InqEventPublisher;

import java.util.Set;

/**
 * Minimal {@link InqElement} fixture used by the pipeline tests in this
 * module. Deliberately lives in the test source set so we do not pull
 * in any paradigm module (e.g. {@code inqudium-imperative}).
 *
 * <p>{@link InqPipelineBuilder} only consults {@link #elementType()}
 * and {@link #name()}; {@link #eventPublisher()} is never invoked, so
 * this fixture returns {@code null} from it.</p>
 *
 * <p>{@link #paradigmTags()} defaults to {@link SyncTag#INSTANCE}; the
 * overloaded constructor takes an explicit set so reference-validation
 * tests can exercise paradigm-mismatch scenarios.</p>
 */
final class TestElement implements InqElement {

    private final InqElementType type;
    private final String name;
    private final Set<ParadigmTag> paradigmTags;

    TestElement(InqElementType type, String name) {
        this(type, name, Set.of(SyncTag.INSTANCE));
    }

    TestElement(InqElementType type, String name, Set<ParadigmTag> paradigmTags) {
        this.type = type;
        this.name = name;
        this.paradigmTags = Set.copyOf(paradigmTags);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InqElementType elementType() {
        return type;
    }

    @Override
    public InqEventPublisher eventPublisher() {
        return null;
    }

    @Override
    public Set<ParadigmTag> paradigmTags() {
        return paradigmTags;
    }

    @Override
    public String toString() {
        return type + "(" + name + ")";
    }
}
