package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.ElementRef;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.pipeline.InqPipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves layer-element names (from
 * {@code MethodPlan.Decorated.elementNamesOuterToInner()}) to the
 * matching {@link InqElement} instances in the pipeline.
 *
 * <p>The annotation evaluator (ADR-036) has already validated that
 * every name referenced from the service interface exists in the
 * pipeline. This resolver therefore assumes lookup never misses;
 * a miss indicates evaluator/pipeline drift and raises
 * {@link IllegalStateException}.</p>
 *
 * <p>Per ARCHITECTURE.md §7.1: pipelines are small (typically &le; 6
 * elements), so constructing a name &rarr; element map per resolution
 * call is acceptable. The resolver runs on the cold construction
 * path, not per method invocation.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from sibling subpackages within {@code inqudium-proxy};
 * not part of the stable public surface.</p>
 */
public final class ElementResolver {

    private ElementResolver() {
        // utility class
    }

    /**
     * Builds the lookup map from element name to {@link InqElement}.
     *
     * <p>Currently assumes globally-unique element names — see
     * finding 1.1 in {@code REFACTORING_PROXY_POLISH.md} (sub-step
     * P.3). When that finding is resolved this method's contract may
     * change.</p>
     */
    public static Map<String, InqElement> indexByName(InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        return pipeline.elements().stream()
                .collect(Collectors.toMap(InqElement::name, Function.identity()));
    }

    /**
     * Resolves names against a pre-built name→element index.
     * Preferred for cases where multiple resolutions occur against
     * the same pipeline (e.g. proxy construction resolving one set of
     * names per service-interface method).
     *
     * @throws IllegalStateException if a name is not present in the
     *                               pipeline (defensive guard against
     *                               evaluator/pipeline drift)
     */
    public static List<InqElement> resolve(
            List<String> names, Map<String, InqElement> byName) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(byName, "byName");
        return names.stream()
                .map(name -> {
                    InqElement element = byName.get(name);
                    if (element == null) {
                        throw new IllegalStateException(
                                "Element '" + name + "' was referenced by an "
                                        + "annotation but is not present in the "
                                        + "pipeline. This should have been caught "
                                        + "by the annotation evaluator (ADR-036) "
                                        + "before construction.");
                    }
                    return element;
                })
                .toList();
    }

    /**
     * Convenience overload that builds the name index internally.
     * Equivalent to {@code resolve(names, indexByName(pipeline))}.
     *
     * <p>For multi-method resolution against the same pipeline (the
     * common case in proxy construction), prefer
     * {@link #indexByName(InqPipeline)} once followed by
     * {@link #resolve(List, Map)} per method.</p>
     */
    public static List<InqElement> resolveNames(
            List<String> names, InqPipeline pipeline) {
        return resolve(names, indexByName(pipeline));
    }

    /**
     * Composite key for paradigm-aware element resolution.
     *
     * <p>Pairs an element type with a name. Per ADR-046, elements are
     * uniquely identified within a pipeline by this pair — not by name
     * alone. Two elements with the same name but different element
     * types coexist legally; the {@code (elementType, name)} pair
     * disambiguates them by construction.</p>
     *
     * @since 0.9.0
     */
    public record ElementTypeAndName(InqElementType elementType, String name) {

        public ElementTypeAndName {
            Objects.requireNonNull(elementType, "elementType");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Builds a lookup map from {@code (elementType, name)} pair to
     * {@link InqElement}. Parallel to {@link #indexByName(InqPipeline)};
     * the pair-keyed form supports paradigm-aware resolution where two
     * elements may share a name across types.
     *
     * <p>Dissolves finding 1.1 from {@code REFACTORING_PROXY_POLISH.md}
     * by construction: keying on the pair never collides on shared
     * names across types.</p>
     *
     * @since 0.9.0
     */
    public static Map<ElementTypeAndName, InqElement> indexByTypeAndName(
            InqPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        return pipeline.elements().stream()
                .collect(Collectors.toMap(
                        el -> new ElementTypeAndName(el.elementType(), el.name()),
                        Function.identity()));
    }

    /**
     * Resolves a list of {@link ElementRef} entries against a
     * pre-built type-and-name index. Each ref is resolved by its
     * {@code (elementType, name)} pair; an unresolvable ref triggers
     * {@link IllegalStateException} with a precise message naming the
     * missing pair.
     *
     * <p>Parallel to {@link #resolve(List, Map)} — that method
     * resolves names against an index keyed on names alone, which
     * carries the latent crash mode documented as finding 1.1 in
     * {@code REFACTORING_PROXY_POLISH.md}. The triple-keyed form here
     * dissolves that finding: duplicate names across element types
     * are inherently safe.</p>
     *
     * @throws IllegalStateException if a ref is not present in the
     *                               index (defensive guard against
     *                               evaluator/pipeline drift)
     *
     * @since 0.9.0
     */
    public static List<InqElement> resolveTriples(
            List<ElementRef> refs,
            Map<ElementTypeAndName, InqElement> byTypeAndName) {
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(byTypeAndName, "byTypeAndName");
        return refs.stream()
                .map(ref -> {
                    ElementTypeAndName key =
                            new ElementTypeAndName(ref.elementType(), ref.name());
                    InqElement element = byTypeAndName.get(key);
                    if (element == null) {
                        throw new IllegalStateException(
                                "Element with type " + ref.elementType()
                                        + " and name '" + ref.name()
                                        + "' was referenced by an annotation but "
                                        + "is not present in the pipeline. This "
                                        + "should have been caught by the "
                                        + "annotation evaluator (ADR-036) before "
                                        + "construction.");
                    }
                    return element;
                })
                .toList();
    }
}
