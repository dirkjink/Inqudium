package eu.inqudium.annotation.evaluator;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;
import eu.inqudium.core.element.InqElementType;

/**
 * Single source of truth for the Inqudium element-annotation metadata
 * consumed by the evaluator's resolvers. The descriptor list is ordered for
 * deterministic iteration so that diagnostic messages naming annotations
 * refer to them in this sequence regardless of which consumer produced the
 * message.
 *
 * <p>The class is public to expose {@link #annotationFor(InqElementType)} to
 * cross-module consumers (notably {@code inqudium-pipeline}'s reference
 * validation). The descriptor list and map remain package-private — only the
 * reverse-lookup helper is part of the cross-module surface.</p>
 *
 * @since 0.8.0
 */
public final class ElementAnnotations {

    static final List<ElementAnnotationDescriptor<?>> DESCRIPTORS = List.of(
            new ElementAnnotationDescriptor<>(
                    InqCircuitBreaker.class, InqElementType.CIRCUIT_BREAKER, InqCircuitBreaker::value),
            new ElementAnnotationDescriptor<>(
                    InqRetry.class,          InqElementType.RETRY,           InqRetry::value),
            new ElementAnnotationDescriptor<>(
                    InqBulkhead.class,       InqElementType.BULKHEAD,        InqBulkhead::value),
            new ElementAnnotationDescriptor<>(
                    InqRateLimiter.class,    InqElementType.RATE_LIMITER,    InqRateLimiter::value),
            new ElementAnnotationDescriptor<>(
                    InqTimeLimiter.class,    InqElementType.TIME_LIMITER,    InqTimeLimiter::value),
            new ElementAnnotationDescriptor<>(
                    InqTrafficShaper.class,  InqElementType.TRAFFIC_SHAPER,  InqTrafficShaper::value));

    static final Map<Class<? extends Annotation>, InqElementType> ANNOTATION_TO_TYPE;

    static {
        Map<Class<? extends Annotation>, InqElementType> map = new LinkedHashMap<>();
        for (ElementAnnotationDescriptor<?> descriptor : DESCRIPTORS) {
            map.put(descriptor.annotationType(), descriptor.elementType());
        }
        ANNOTATION_TO_TYPE = Map.copyOf(map);
    }

    private ElementAnnotations() {
        // utility class
    }

    /**
     * Returns the annotation class associated with the given element type.
     * Reverse of the annotation-to-type mapping used during evaluation.
     *
     * <p>Used by pipeline-side validation to produce error messages that
     * reference the annotation by its source-level name (e.g.
     * {@code @InqBulkhead}) rather than just its element type
     * ({@code BULKHEAD}).</p>
     *
     * @param elementType the element type to look up; must not be {@code null}
     * @return the associated annotation class
     * @throws IllegalArgumentException if no annotation is registered for the
     *         given element type
     * @since 0.10.0
     */
    public static Class<? extends Annotation> annotationFor(InqElementType elementType) {
        Objects.requireNonNull(elementType, "elementType");
        for (ElementAnnotationDescriptor<?> descriptor : DESCRIPTORS) {
            if (descriptor.elementType() == elementType) {
                return descriptor.annotationType();
            }
        }
        throw new IllegalArgumentException(
                "No annotation registered for element type " + elementType);
    }
}
