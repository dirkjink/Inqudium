package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.element.InqElementType;

import java.util.Objects;

/**
 * Reference to a pipeline element by {@code (elementType, name)} pair.
 *
 * <p>An {@link ElementRef} forms two-thirds of the
 * {@code (paradigm, elementType, name)} registry-key triple used by
 * paradigm-aware integration mechanisms; the third component, the paradigm
 * tag, is carried by the surrounding
 * {@link MethodPlan.StampedDecorated} or
 * {@link MethodPlan.StampedPassThrough}.</p>
 *
 * <p>Name uniqueness assumptions: per ADR-046, the
 * {@code (elementType, name)} pair is sufficient to disambiguate pipeline
 * elements within a paradigm-homogeneous pipeline. Two elements with the
 * same name but different element types (e.g. a bulkhead and a retry both
 * named {@code "orderOp"}) are legal by construction; the resolver looks
 * them up by pair, not by name alone.</p>
 *
 * @param elementType the element's type (e.g. {@link InqElementType#BULKHEAD},
 *                    {@link InqElementType#RETRY}); must not be null
 * @param name        the element's name within the pipeline; must not be null
 *
 * @since 0.9.0
 */
public record ElementRef(InqElementType elementType, String name) {

    public ElementRef {
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(name, "name");
    }
}
