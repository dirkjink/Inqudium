package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.paradigm.ParadigmTag;

import java.util.List;
import java.util.Objects;

/**
 * Per-method protection plan produced by {@link AnnotationEvaluator}.
 *
 * <p>Two variants apply to each method on a service interface:</p>
 *
 * <ul>
 *   <li>{@link PassThrough} — no resilience protection, paradigm
 *       classification recorded. The paradigm allows downstream
 *       consumers (proxy, aspect, function-style) to route the call
 *       through the matching dispatch path even when no resilience
 *       chain wraps it.</li>
 *   <li>{@link Decorated} — the method is wrapped in pipeline elements
 *       identified by {@code (elementType, name)} pair, with its
 *       paradigm classification recorded.</li>
 * </ul>
 *
 * <p>The paradigm is recorded per-method, not per-element: all
 * elements wrapping a single method share the method's paradigm by
 * construction (ADR-046).</p>
 *
 * @since 0.8.0
 */
public sealed interface MethodPlan {

    /**
     * The method receives no resilience protection, but its paradigm
     * has been classified and recorded. Applies when no method-level
     * or class-level resilience annotation is in effect, or when the
     * method is an unoverridden interface default method (ADR-036 §7).
     *
     * @param paradigm the method's paradigm classification; never null.
     */
    record PassThrough(ParadigmTag paradigm) implements MethodPlan {

        public PassThrough {
            Objects.requireNonNull(paradigm, "paradigm");
        }
    }

    /**
     * The method is wrapped by pipeline elements identified by
     * {@code (elementType, name)} pair, in the given order, with its
     * paradigm classification recorded.
     *
     * <p>The first entry is the outermost wrapping element; the last
     * entry is closest to the method itself. The
     * {@code (elementType, name)} pair disambiguates elements that
     * share a name across element types (e.g. a bulkhead and a retry
     * both named {@code "orderOp"}).</p>
     *
     * <p>The list is non-empty whenever the record is produced by
     * {@link AnnotationEvaluator}. An empty list is possible only when
     * the record is constructed directly by application code; the
     * evaluator itself produces {@link PassThrough} for a method with
     * no annotated source rather than an empty {@code Decorated}.</p>
     *
     * @param paradigm              the method's paradigm classification;
     *                              never null.
     * @param elementsOuterToInner  the ordered element references,
     *                              outermost first; defensively copied
     *                              into an immutable list.
     */
    record Decorated(ParadigmTag paradigm, List<ElementRef> elementsOuterToInner)
            implements MethodPlan {

        public Decorated {
            Objects.requireNonNull(paradigm, "paradigm");
            elementsOuterToInner = List.copyOf(elementsOuterToInner);
        }
    }
}
