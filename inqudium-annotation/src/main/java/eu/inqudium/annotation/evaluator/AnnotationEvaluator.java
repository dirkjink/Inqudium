package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.pipeline.InqPipeline;

/**
 * Reads the resilience-element annotations on a service interface's
 * implementation class and produces a per-method protection plan, validated
 * against a given {@link InqPipeline}.
 *
 * <p>The evaluator is the library-internal entry point described in
 * ADR-036. Annotation rules (which method to inspect, how inheritance
 * applies, how multiple annotations are composed, which configurations are
 * invalid) are encoded in its collaborators in this package.</p>
 *
 * <p>Instances are created via the static factory
 * {@link #forPipeline(InqPipeline)}; the returned evaluator holds the
 * pipeline reference and can {@link #evaluate(Class, Class) evaluate}
 * multiple service interfaces against it.</p>
 *
 * @since 0.8.0
 */
public interface AnnotationEvaluator {

    /**
     * Creates an evaluator that resolves annotations against the given
     * pipeline. Element names referenced by annotations must exist in the
     * pipeline; otherwise the evaluator throws at evaluation time.
     *
     * @param pipeline the pipeline whose elements back the annotations;
     *                 must not be {@code null}
     * @return a new evaluator bound to {@code pipeline}
     * @throws IllegalArgumentException if {@code pipeline} is {@code null}
     */
    static AnnotationEvaluator forPipeline(InqPipeline pipeline) {
        return new DefaultAnnotationEvaluator(pipeline);
    }

    /**
     * Evaluates the annotations on {@code implementationClass} for each
     * method of {@code serviceInterface}, producing a per-method
     * {@link MethodPlan}.
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
     * @throws IllegalArgumentException             if either argument is
     *                                              {@code null}, if
     *                                              {@code serviceInterface}
     *                                              is not an interface, or
     *                                              if {@code implementationClass}
     *                                              does not implement
     *                                              {@code serviceInterface}
     * @throws InqAnnotationConfigurationException  if any annotation
     *                                              references an element
     *                                              name not present in the
     *                                              pipeline, or if any
     *                                              other ADR-036 validation
     *                                              rule is violated
     */
    <T> EvaluationResult evaluate(Class<T> serviceInterface, Class<? extends T> implementationClass);

    /**
     * Like {@link #evaluate(Class, Class)}, but additionally classifies
     * each method's paradigm via {@code ParadigmClassifier} and returns
     * plans using the {@link MethodPlan.StampedPassThrough} and
     * {@link MethodPlan.StampedDecorated} record variants.
     *
     * <p>Each per-method plan carries the method's
     * {@link eu.inqudium.core.element.paradigm.ParadigmTag}; decorated
     * plans additionally reference their pipeline elements by
     * {@link ElementRef} pair rather than by name alone, so paradigm-aware
     * resolvers can disambiguate elements that share a name across
     * element types.</p>
     *
     * <p>This is the paradigm-aware entry point (ADR-046). Consumers
     * migrate from {@link #evaluate(Class, Class)} as they become
     * paradigm-aware; both methods coexist during the migration and apply
     * identical argument validation.</p>
     *
     * @param <T>                  the service interface type; the
     *                             implementation class must be a subtype
     * @param serviceInterface     the interface whose methods are evaluated;
     *                             must not be {@code null} and must be an
     *                             interface
     * @param implementationClass  the concrete implementation class; must
     *                             not be {@code null} and must implement
     *                             {@code serviceInterface}
     * @return the per-method paradigm-stamped plans, keyed by interface method
     * @throws IllegalArgumentException             if either argument is
     *                                              {@code null}, if
     *                                              {@code serviceInterface}
     *                                              is not an interface, or
     *                                              if {@code implementationClass}
     *                                              does not implement
     *                                              {@code serviceInterface}
     * @throws InqAnnotationConfigurationException  if any annotation
     *                                              references an element
     *                                              name not present in the
     *                                              pipeline, or if any
     *                                              other ADR-036 validation
     *                                              rule is violated
     *
     * @since 0.9.0
     */
    <T> EvaluationResult evaluateStamped(
            Class<T> serviceInterface, Class<? extends T> implementationClass);
}
