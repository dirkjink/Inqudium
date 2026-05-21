package eu.inqudium.pipeline;

import eu.inqudium.annotation.evaluator.ElementAnnotations;
import eu.inqudium.annotation.evaluator.ElementRef;
import eu.inqudium.annotation.evaluator.EvaluationResult;
import eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.paradigm.ParadigmTag;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Composition primitive: a finite, ordered list of resilience elements
 * shared across all paradigms (sync, async, future reactive). The
 * pipeline is the unit of composition that integrations (proxy,
 * functional decoration, AspectJ, Spring) consume to apply resilience
 * around target code.
 *
 * <p>An {@code InqPipeline} is constructed exclusively via
 * {@link #builder()} and the {@link InqPipelineBuilder} it returns.
 * The pipeline is structurally immutable once built — no element can
 * be added, replaced, or reordered after {@link InqPipelineBuilder#build()}
 * returns.</p>
 *
 * <p>Per ADR-040, the interface intentionally allows multiple
 * implementations. The default builder produces one
 * {@code DefaultInqPipeline}; integration modules may wrap a pipeline
 * in additional behaviour (e.g. a diagnostic wrapper that records all
 * applied elements) by implementing this interface.</p>
 *
 * @see InqPipelineBuilder
 */
public interface InqPipeline {

    /**
     * Returns the pipeline's elements in canonical composition order
     * (outermost first). The list is unmodifiable; modification
     * attempts throw {@link UnsupportedOperationException}.
     *
     * <p>Ordering follows ADR-041 — the builder reorders elements at
     * {@code build()} time according to the configured ordering
     * strategy. The {@code shield(...)} call order in the builder is
     * not the composition order.</p>
     *
     * @return the ordered, unmodifiable element list
     */
    List<InqElement> elements();

    /**
     * Returns a JDK dynamic proxy that implements
     * {@code serviceInterface} and routes every method invocation
     * through the resilience elements declared in this pipeline. The
     * proxy applies the elements according to the per-method plan
     * computed by the annotation evaluator (ADR-036).
     *
     * <p>This default method requires {@code inqudium-proxy} on the
     * classpath. The probe is performed by {@link DetectionProxy}; if
     * the module is absent, an {@link IllegalStateException} is raised
     * with a message pointing at the required dependency. When the
     * proxy module is present, the call is delegated to
     * {@code eu.inqudium.proxy.ProxyDispatcher.protect(...)} via the
     * {@link ProxyDelegation} reflective bridge (string-name lookup
     * avoids the Maven cycle that a direct class-literal reference
     * would create).</p>
     *
     * @param serviceInterface  the interface the proxy will implement;
     *                          must be an interface, not a concrete
     *                          class
     * @param target            the real implementation to which the
     *                          proxy delegates after applying the
     *                          pipeline
     * @param <T>               the service interface type
     * @return                  a proxy of {@code serviceInterface}
     * @throws IllegalStateException        if the proxy module is not
     *                                      on the classpath
     */
    default <T> T protect(Class<T> serviceInterface, T target) {
        if (!DetectionProxy.isPresent()) {
            throw new IllegalStateException(
                    "ProxyDispatcher is not on the classpath. Add "
                            + "inqudium-proxy as a runtime dependency to enable "
                            + "proxy-based protection of service interfaces.");
        }
        return ProxyDelegation.delegateProtect(this, serviceInterface, target);
    }

    /**
     * Creates a new, single-use {@link InqPipelineBuilder}. Each call
     * returns a fresh builder; builders are not reusable after their
     * {@code build()} method returns.
     *
     * @return a fresh builder
     */
    static InqPipelineBuilder builder() {
        return new InqPipelineBuilder();
    }

    /**
     * Validates that every {@link ElementRef} in the given evaluation
     * result resolves to an element in this pipeline whose
     * {@link InqElement#paradigmTags()} contains the method's paradigm
     * tag.
     *
     * <p>Used by integration dispatchers (proxy, future aspectj/spring/
     * function) to fail fast on annotation-pipeline mismatches before
     * any wrapping work begins. The validation walks each method's plan
     * once; unresolved references raise
     * {@link InqAnnotationConfigurationException} with an error message
     * identifying the annotation type, the service method, the
     * referenced name, and the required paradigm.</p>
     *
     * <p>The triple {@code (elementType, paradigmTag, name)} is the
     * identity of an element reference (per ADR-040 §3 Invariant 2).
     * Validation passes when, for each decorated method's references,
     * a pipeline element exists whose {@code elementType()} and
     * {@code name()} match the reference and whose
     * {@code paradigmTags()} contains the method's paradigm tag.</p>
     *
     * @param evaluation       the annotation evaluation result whose
     *                         references are validated
     * @param serviceInterface the service interface for the evaluation;
     *                         used in error messages
     * @throws InqAnnotationConfigurationException if any reference does
     *         not resolve to a pipeline element matching the full triple
     *
     * @since 0.10.0
     */
    default void validateReferences(EvaluationResult evaluation, Class<?> serviceInterface) {
        Map<Method, MethodPlan> plans = evaluation.plans();
        for (Map.Entry<Method, MethodPlan> entry : plans.entrySet()) {
            Method method = entry.getKey();
            if (!(entry.getValue() instanceof MethodPlan.Decorated decorated)) {
                continue;
            }
            ParadigmTag methodTag = decorated.paradigm();
            for (ElementRef ref : decorated.elementsOuterToInner()) {
                if (!hasMatchingElement(ref, methodTag)) {
                    throw new InqAnnotationConfigurationException(
                            buildValidationMessage(ref, methodTag, method, serviceInterface));
                }
            }
        }
    }

    private boolean hasMatchingElement(ElementRef ref, ParadigmTag methodTag) {
        for (InqElement element : elements()) {
            if (ref.elementType() == element.elementType()
                    && ref.name().equals(element.name())
                    && element.paradigmTags().contains(methodTag)) {
                return true;
            }
        }
        return false;
    }

    private static String buildValidationMessage(
            ElementRef ref, ParadigmTag methodTag, Method method, Class<?> serviceInterface) {
        Class<? extends Annotation> annotationClass =
                ElementAnnotations.annotationFor(ref.elementType());
        String paradigmName = paradigmTagName(methodTag);
        return "@" + annotationClass.getSimpleName()
                + " on " + serviceInterface.getName() + "#" + method.getName()
                + " names '" + ref.name() + "' for paradigm " + paradigmName
                + " but pipeline has no matching ("
                + ref.elementType() + ", " + paradigmName
                + ", '" + ref.name() + "') element";
    }

    /**
     * Returns the canonical name of a {@link ParadigmTag} for use in
     * diagnostic messages. The concrete default implementation classes
     * are named {@code *Default} (e.g. {@code SyncTagDefault}); the
     * sealed parent interface they directly implement carries the
     * canonical paradigm name (e.g. {@code SyncTag}). Reading
     * {@code getInterfaces()[0]} is reliable here because every
     * {@code ParadigmTag} concrete implementation is the sole permitted
     * default of its sealed parent and declares that parent as its
     * first (and only) interface.
     */
    private static String paradigmTagName(ParadigmTag tag) {
        Class<?>[] interfaces = tag.getClass().getInterfaces();
        if (interfaces.length == 0) {
            return tag.getClass().getSimpleName();
        }
        return interfaces[0].getSimpleName();
    }
}
