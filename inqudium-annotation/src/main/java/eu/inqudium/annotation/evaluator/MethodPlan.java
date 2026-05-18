package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.element.paradigm.ParadigmTag;

import java.util.List;
import java.util.Objects;

/**
 * Per-method protection plan produced by {@link AnnotationEvaluator}.
 *
 * <p>Four variants apply to each method on a service interface:</p>
 *
 * <ul>
 *   <li>{@link PassThrough} (legacy) — no resilience protection,
 *       no paradigm information.</li>
 *   <li>{@link Decorated} (legacy) — wrapped in the listed pipeline
 *       elements by name only.</li>
 *   <li>{@link StampedPassThrough} — no resilience protection, paradigm
 *       classification recorded.</li>
 *   <li>{@link StampedDecorated} — wrapped in pipeline elements identified
 *       by {@code (elementType, name)} pair, paradigm classification
 *       recorded.</li>
 * </ul>
 *
 * <p>The {@code Stamped*} variants are added by
 * {@link DefaultAnnotationEvaluator#evaluateStamped(Class, Class)} and
 * consumed by paradigm-aware integration mechanisms (the proxy as of Q.4).
 * Per ADR-046, the legacy variants are removed in a future cleanup step
 * once all consumers have migrated; the {@code Stamped*} prefix is
 * temporary disambiguation, dropped in Q.6 when the legacy permits go
 * away.</p>
 *
 * @since 0.8.0
 */
public sealed interface MethodPlan {

    /**
     * The method receives no resilience protection — neither a method-level
     * nor a class-level resilience annotation applies, or the method is an
     * unoverridden interface default method (ADR-036 §7).
     */
    record PassThrough() implements MethodPlan {
    }

    /**
     * The method is wrapped by the named pipeline elements, in the given
     * order. The first entry is the outermost wrapping element; the last
     * entry is closest to the method itself.
     *
     * <p>The list is non-empty whenever the record is produced by
     * {@link AnnotationEvaluator}. An empty list is possible only when the
     * record is constructed directly by application code; the
     * {@code AnnotationEvaluator} itself produces an empty {@code Decorated}
     * for no annotated source — it produces {@link PassThrough} instead.</p>
     *
     * @param elementNamesOuterToInner the ordered element names, outermost
     *                                 first; defensively copied into an
     *                                 immutable list
     */
    record Decorated(List<String> elementNamesOuterToInner) implements MethodPlan {

        /**
         * Defensively copies the input into an immutable list so callers
         * cannot mutate the plan after construction.
         */
        public Decorated {
            elementNamesOuterToInner = List.copyOf(elementNamesOuterToInner);
        }
    }

    /**
     * The method receives no resilience protection, but its paradigm has
     * been classified and recorded. The paradigm allows downstream
     * consumers (proxy, aspect, function-style) to route the call through
     * the matching dispatch path even when no resilience chain wraps it.
     *
     * @param paradigm the method's paradigm classification; never null
     *
     * @since 0.9.0
     */
    record StampedPassThrough(ParadigmTag paradigm) implements MethodPlan {

        public StampedPassThrough {
            Objects.requireNonNull(paradigm, "paradigm");
        }
    }

    /**
     * The method is wrapped by pipeline elements identified by
     * {@code (elementType, name)} pair, in the given order, with its
     * paradigm classification recorded.
     *
     * <p>The first entry is the outermost wrapping element; the last entry
     * is closest to the method itself. The {@code (elementType, name)} pair
     * disambiguates elements that share a name across element types
     * (e.g. a bulkhead and a retry both named {@code "orderOp"}).</p>
     *
     * <p>Paradigm is recorded per-method, not per-element: all elements
     * wrapping a single method share the method's paradigm by
     * construction.</p>
     *
     * @param paradigm              the method's paradigm classification;
     *                              never null
     * @param elementsOuterToInner  the ordered element references,
     *                              outermost first; defensively copied
     *                              into an immutable list
     *
     * @since 0.9.0
     */
    record StampedDecorated(ParadigmTag paradigm, List<ElementRef> elementsOuterToInner)
            implements MethodPlan {

        public StampedDecorated {
            Objects.requireNonNull(paradigm, "paradigm");
            elementsOuterToInner = List.copyOf(elementsOuterToInner);
        }
    }
}
