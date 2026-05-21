package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.paradigm.ParadigmTag;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link AnnotationEvaluator}. Composes the
 * package-private resolvers from sub-steps 3 and 4 and projects their
 * outputs onto a {@link MethodPlan} per method of the service interface.
 *
 * @since 0.8.0
 */
final class DefaultAnnotationEvaluator implements AnnotationEvaluator {

    private final InheritanceResolver inheritanceResolver;
    private final OrderingResolver orderingResolver;

    DefaultAnnotationEvaluator() {
        MethodResolver methodResolver = new DefaultMethodResolver();
        this.inheritanceResolver = new DefaultInheritanceResolver(methodResolver);
        this.orderingResolver = new DefaultOrderingResolver();
    }

    @Override
    public <T> EvaluationResult evaluate(
            Class<T> serviceInterface, Class<? extends T> implementationClass) {
        validateArguments(serviceInterface, implementationClass);

        Map<Method, MethodPlan> plans = new LinkedHashMap<>();
        for (Method interfaceMethod : serviceInterface.getMethods()) {
            MethodPlan plan = planFor(serviceInterface, interfaceMethod, implementationClass);
            plans.put(interfaceMethod, plan);
        }
        return new EvaluationResult(plans);
    }

    private static <T> void validateArguments(
            Class<T> serviceInterface, Class<? extends T> implementationClass) {
        if (serviceInterface == null) {
            throw new IllegalArgumentException("serviceInterface must not be null");
        }
        if (implementationClass == null) {
            throw new IllegalArgumentException("implementationClass must not be null");
        }
        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "serviceInterface must be an interface, but was: " + serviceInterface.getName());
        }
        if (!serviceInterface.isAssignableFrom(implementationClass)) {
            throw new IllegalArgumentException(
                    "implementationClass " + implementationClass.getName()
                            + " does not implement " + serviceInterface.getName());
        }
    }

    private MethodPlan planFor(
            Class<?> serviceInterface, Method interfaceMethod, Class<?> implementationClass) {

        ParadigmTag paradigm = ParadigmClassifier.classify(interfaceMethod);
        AnnotatedElement annotatedElement = resolveAnnotatedElement(interfaceMethod, implementationClass);

        if (annotatedElement == null) {
            return new MethodPlan.PassThrough(paradigm);
        }

        List<ElementRef> orderedRefs = new ArrayList<>();
        forEachOrderedElement(serviceInterface, interfaceMethod, annotatedElement,
                (type, name) -> orderedRefs.add(new ElementRef(type, name)));

        return new MethodPlan.Decorated(paradigm, orderedRefs);
    }

    private AnnotatedElement resolveAnnotatedElement(
            Method interfaceMethod, Class<?> implementationClass) {
        AnnotationSource source = inheritanceResolver.resolve(interfaceMethod, implementationClass);
        return switch (source) {
            case AnnotationSource.PassThrough ignored -> null;
            case AnnotationSource.MethodLevel methodLevel -> methodLevel.method();
            case AnnotationSource.ClassLevelOnly classLevel -> classLevel.annotationSourceClass();
        };
    }

    /**
     * Resolves the ordered list of element types for {@code annotatedElement}
     * and invokes {@code consumer} for each {@code (type, name)} pair in
     * order.
     */
    private void forEachOrderedElement(
            Class<?> serviceInterface,
            Method interfaceMethod,
            AnnotatedElement annotatedElement,
            java.util.function.BiConsumer<InqElementType, String> consumer) {

        Map<InqElementType, String> elementNames = collectElementNames(annotatedElement);
        List<InqElementType> ordering = orderingResolver.resolveOrder(annotatedElement);

        for (InqElementType type : ordering) {
            String name = elementNames.get(type);
            if (name == null) {
                // Defensive: the OrderingResolver validates set-equality between customOrder
                // and the annotated element types, so reaching this branch indicates a
                // programming error in one of the resolvers rather than a user-facing
                // configuration problem.
                throw new IllegalStateException(
                        "Ordering for " + describe(serviceInterface, interfaceMethod)
                                + " references element type " + type
                                + " that is not annotated on " + annotatedElement);
            }
            consumer.accept(type, name);
        }
    }

    /**
     * Reads every Inqudium element annotation present on
     * {@code annotatedElement} and projects it onto an
     * {@code (element type, instance name)} map.
     */
    private Map<InqElementType, String> collectElementNames(AnnotatedElement annotatedElement) {
        EnumMap<InqElementType, String> result = new EnumMap<>(InqElementType.class);
        for (ElementAnnotationDescriptor<?> descriptor : ElementAnnotations.DESCRIPTORS) {
            String name = descriptor.readName(annotatedElement);
            if (name == null) {
                continue;
            }
            result.put(descriptor.elementType(), name);
        }
        return result;
    }

    private static String describe(Class<?> serviceInterface, Method interfaceMethod) {
        return serviceInterface.getName() + "#" + interfaceMethod.getName();
    }
}
