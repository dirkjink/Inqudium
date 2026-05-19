package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.CoroutinesTag;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.ReactiveTag;
import eu.inqudium.core.element.paradigm.RxJava3Tag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.dispatch.DetectionAsync;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.folding.SyncChainFolder;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Classifies a {@code (method, plan)} pair and builds the
 * appropriate {@link MethodDispatchEntry}. The plan carries the
 * paradigm classification (ADR-046); this factory routes on it
 * directly — no per-call paradigm detection.
 *
 * <pre>
 * classify(method, plan, implClass):
 *   if plan instanceof PassThrough:
 *     if method.isDefault() &amp;&amp; !overriddenByImpl(method, implClass) &rarr; DefaultMethodEntry
 *     else                                                                &rarr; PassThroughEntry
 *   else (plan instanceof Decorated sd):
 *     elements = resolveTriples(sd.elementsOuterToInner, byTypeAndName)
 *     switch (sd.paradigm):
 *       case SyncTag      &rarr; SyncCacheEntry
 *       case AsyncTag     &rarr; require DetectionAsync.isPresent(); AsyncCacheEntry
 *       case ReactiveTag,
 *            RxJava3Tag,
 *            CoroutinesTag &rarr; throw InqAnnotationConfigurationException
 *                              (no element implementation for these paradigms yet)
 * </pre>
 *
 * <p>{@code Object}-declared methods are <strong>not</strong> handled
 * by this factory. {@code serviceInterface.getMethods()} on an
 * interface excludes {@link Object} methods, so the evaluator's plans
 * never reference them; {@code ProxyBuilder} seeds Object-method
 * entries directly via
 * {@link MethodDispatchEntry#objectMethod(eu.inqudium.proxy.handler.ObjectMethodHandler.Kind)}
 * after the evaluator pass.</p>
 *
 * <p><strong>Class-loading discipline</strong> (ADR-037 §6 /
 * ARCHITECTURE.md §13). This class carries <strong>no</strong>
 * compile-time references to {@code inqudium-imperative} types.
 * The entire async-build flow lives in {@link AsyncEntryBuilder};
 * the async branch reaches it via a plain {@code invokestatic} call,
 * which the JVM resolves lazily per JVMS §5.4 — the imperative types
 * load only when an async method is actually present and
 * {@link DetectionAsync#isPresent()} has returned {@code true}.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code ProxyBuilder}; not part of the stable public
 * surface.</p>
 */
public final class MethodDispatchEntryFactory {

    private MethodDispatchEntryFactory() {
        // utility class
    }

    /**
     * Convenience overload that builds the type-and-name index
     * internally. Prefer
     * {@link #createEntry(Method, MethodPlan, InqPipeline, Object,
     * Class, Map)} when constructing entries for multiple methods
     * against the same pipeline — that form lets the caller share the
     * index across calls.
     */
    public static MethodDispatchEntry createEntry(
            Method method,
            MethodPlan plan,
            InqPipeline pipeline,
            Object target,
            Class<?> implClass) {
        return createEntry(method, plan, pipeline, target, implClass,
                ElementResolver.indexByTypeAndName(pipeline));
    }

    /**
     * Builds the entry for one service method from a paradigm-stamped
     * plan. The paradigm is read directly from the plan; no per-call
     * detection is performed.
     *
     * <p>For {@link MethodPlan.Decorated} plans classified as a
     * paradigm without a resilience-element implementation
     * ({@link ReactiveTag}, {@link RxJava3Tag}, {@link CoroutinesTag}),
     * throws {@link InqAnnotationConfigurationException} at construction
     * time with a specific, actionable error message naming the method,
     * the paradigm, and the annotated elements. The corresponding
     * {@link MethodPlan.PassThrough} plans (same paradigms but no
     * resilience annotation in effect) dispatch normally as
     * pass-through.</p>
     */
    public static MethodDispatchEntry createEntry(
            Method method,
            MethodPlan plan,
            InqPipeline pipeline,
            Object target,
            Class<?> implClass,
            Map<ElementResolver.ElementTypeAndName, InqElement> elementsByTypeAndName) {

        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(implClass, "implClass");
        Objects.requireNonNull(elementsByTypeAndName, "elementsByTypeAndName");

        return switch (plan) {
            case MethodPlan.PassThrough pt ->
                    buildPassThrough(method, target, implClass);
            case MethodPlan.Decorated d ->
                    buildDecorated(method, d, target, elementsByTypeAndName);
        };
    }

    private static MethodDispatchEntry buildPassThrough(
            Method method, Object target, Class<?> implClass) {
        if (method.isDefault() && !overriddenByImpl(method, implClass)) {
            return MethodDispatchEntry.defaultMethod(method);
        }
        MethodInvoker invoker = MethodInvoker.create(target, method);
        return MethodDispatchEntry.passThrough(invoker);
    }

    private static MethodDispatchEntry buildDecorated(
            Method method,
            MethodPlan.Decorated plan,
            Object target,
            Map<ElementResolver.ElementTypeAndName, InqElement> elementsByTypeAndName) {

        ParadigmTag paradigm = plan.paradigm();
        return switch (paradigm) {
            case SyncTag s ->
                    buildSyncDecorated(method, plan, target, elementsByTypeAndName);
            case AsyncTag a -> {
                if (!DetectionAsync.isPresent()) {
                    throw new IllegalStateException(
                            "Method " + method + " is classified as AsyncTag "
                                    + "but inqudium-imperative is not on the "
                                    + "classpath. Add inqudium-imperative as a "
                                    + "runtime dependency to enable async "
                                    + "dispatch (ADR-037 §3).");
                }
                // Class-loading discipline: AsyncEntryBuilder is reached
                // via a plain invokestatic, which the JVM resolves lazily
                // per JVMS §5.4. No imperative type literal appears in
                // this class.
                yield buildAsyncDecorated(method, plan, target, elementsByTypeAndName);
            }
            case ReactiveTag r ->
                    throw unsupportedParadigm(method, "ReactiveTag",
                            "reactive (Mono/Flux)", plan);
            case RxJava3Tag rx ->
                    throw unsupportedParadigm(method, "RxJava3Tag",
                            "RxJava 3", plan);
            case CoroutinesTag c ->
                    throw unsupportedParadigm(method, "CoroutinesTag",
                            "Kotlin coroutines", plan);
        };
    }

    private static MethodDispatchEntry buildSyncDecorated(
            Method method,
            MethodPlan.Decorated plan,
            Object target,
            Map<ElementResolver.ElementTypeAndName, InqElement> elementsByTypeAndName) {

        List<InqElement> elements = ElementResolver.resolveTriples(
                plan.elementsOuterToInner(), elementsByTypeAndName);
        SyncParadigmValidator.validate(elements, method);

        List<LayerAction<Void, Object>> layerActions = elements.stream()
                .map(MethodDispatchEntryFactory::toLayerAction)
                .toList();

        MethodInvoker invoker = MethodInvoker.create(target, method);
        FoldedSyncChain chain = SyncChainFolder.fold(layerActions, invoker);

        List<String> layerDescriptions = elements.stream()
                .map(InqElement::name)
                .toList();

        return MethodDispatchEntry.syncCache(chain, layerDescriptions);
    }

    private static MethodDispatchEntry buildAsyncDecorated(
            Method method,
            MethodPlan.Decorated plan,
            Object target,
            Map<ElementResolver.ElementTypeAndName, InqElement> elementsByTypeAndName) {
        return AsyncEntryBuilder.build(method, plan, target, elementsByTypeAndName);
    }

    private static InqAnnotationConfigurationException unsupportedParadigm(
            Method method,
            String tagFamily,
            String paradigmDescription,
            MethodPlan.Decorated plan) {

        String elementSummary = plan.elementsOuterToInner().stream()
                .map(ref -> ref.elementType() + " '" + ref.name() + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");

        return new InqAnnotationConfigurationException(
                "Method " + method.getDeclaringClass().getSimpleName() + "#"
                        + method.getName() + "(...) is classified as "
                        + tagFamily + " (" + paradigmDescription + ") but the "
                        + "resilience-element implementation for this paradigm "
                        + "is not yet available. The library currently "
                        + "implements resilience elements only for the sync "
                        + "(SyncTag) and async (AsyncTag) paradigms. "
                        + "Annotated elements on this method: " + elementSummary
                        + ". Either remove the resilience annotation(s) from "
                        + "this method, or restrict the method's return type "
                        + "to a sync or async imperative shape.");
    }

    /**
     * Re-types an element to the storage parameterisation
     * {@code LayerAction<Void, Object>}. Since
     * {@link SyncParadigmValidator} ran first, every element here is
     * an {@link InqDecorator}, which extends {@link LayerAction}.
     *
     * <p>Storage typing vs. call-time typing (per ADR-035 §4): the
     * element implements {@code InqDecorator<A, R>} for some
     * {@code A}, {@code R}. At storage time we erase to
     * {@code LayerAction<Void, Object>}; the
     * {@link SyncChainFolder} re-parameterises the list at fold time.
     * The wildcard intermediate cast bridges from the compile-time
     * {@link InqElement} view (which the compiler does not statically
     * see as a {@link LayerAction}) to the storage view.</p>
     *
     * <p>See ARCHITECTURE.md §7.2 for the full discussion.</p>
     */
    private static LayerAction<Void, Object> toLayerAction(InqElement element) {
        InqDecorator<?, ?> decorator = (InqDecorator<?, ?>) element;
        return (LayerAction<Void, Object>) (LayerAction<?, ?>) decorator;
    }

    /**
     * Returns {@code true} if the implementation class declares a
     * non-default method that overrides the given interface default
     * method.
     */
    private static boolean overriddenByImpl(Method defaultMethod, Class<?> implClass) {
        try {
            Method implMethod = implClass.getMethod(
                    defaultMethod.getName(),
                    defaultMethod.getParameterTypes());
            return !implMethod.isDefault();
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
