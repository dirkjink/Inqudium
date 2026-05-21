package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.paradigm.ParadigmTag;

/**
 * Reads the resilience-element annotations on a service interface's
 * implementation class and produces a per-method paradigm-stamped
 * protection plan. The plan references pipeline elements by
 * {@code (elementType, name)} pair via {@link ElementRef}; validation
 * that those references resolve to actual pipeline elements is the
 * responsibility of the consuming side (see
 * {@code InqPipeline#validateReferences} in {@code inqudium-pipeline}).
 *
 * <p>The evaluator is the library-internal entry point described in
 * ADR-036 / ADR-046. Annotation rules (which method to inspect, how
 * inheritance applies, how multiple annotations are composed, which
 * configurations are invalid) are encoded in its collaborators in
 * this package.</p>
 *
 * <p>Instances are obtained via the static factory {@link #instance()};
 * a fresh evaluator can {@link #evaluate(Class, Class) evaluate} any
 * number of service interfaces.</p>
 *
 * @since 0.8.0
 */
public interface AnnotationEvaluator {

    /**
     * Returns a new evaluator instance. The evaluator holds no
     * pipeline reference — reference resolution against pipeline
     * elements is performed by the consumer after evaluation, via
     * {@code InqPipeline#validateReferences}.
     *
     * @return a new evaluator
     */
    static AnnotationEvaluator instance() {
        return new DefaultAnnotationEvaluator();
    }

    /**
     * Evaluates the annotations on {@code implementationClass} for each
     * method of {@code serviceInterface}, producing a per-method
     * paradigm-stamped {@link MethodPlan}.
     *
     * <p>Each per-method plan carries the method's
     * {@link ParadigmTag}; decorated
     * plans reference their pipeline elements by {@link ElementRef} pair
     * so paradigm-aware resolvers can disambiguate elements that share a
     * name across element types (ADR-046).</p>
     *
     * @param <T>                  the service interface type; the
     *                             implementation class must be a subtype
     * @param serviceInterface     the interface whose methods are evaluated;
     *                             must not be {@code null} and must be an
     *                             interface
     * @param implementationClass  the concrete implementation class; must
     *                             not be {@code null} and must implement
     *                             {@code serviceInterface}
     * @return the per-method plans, keyed by interface method
     * @throws IllegalArgumentException if either argument is {@code null},
     *         if {@code serviceInterface} is not an interface, or if
     *         {@code implementationClass} does not implement
     *         {@code serviceInterface}
     * @throws InqAnnotationConfigurationException if any ADR-036 validation
     *         rule is violated by the annotation set itself (note:
     *         reference-name resolution against a pipeline is no longer
     *         performed here; see {@code InqPipeline#validateReferences})
     */
    <T> EvaluationResult evaluate(Class<T> serviceInterface, Class<? extends T> implementationClass);
}
