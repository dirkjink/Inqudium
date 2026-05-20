package eu.inqudium.pipeline.introspection;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-method layer description, per ADR-039.
 *
 * <p>For the proxy paradigm, {@link #method()} is always
 * {@link Optional#of(Object) Optional.of(method)} — ADR-039's
 * tier-1 method resolution applies (the proxy's invocation handler
 * has a concrete {@link Method} for every dispatched call). For the
 * function paradigm, {@link #method()} is {@link Optional#empty()}
 * because lambda SAM-method resolution is deferred future work
 * (ADR-039's tier-2 {@code SerializedLambda} resolution).</p>
 *
 * <p><strong>Public API.</strong> Component of the
 * {@link InqStackInfo} sealed hierarchy: every permit's
 * {@link InqStackInfo#methodLayers() methodLayers()} returns a list
 * of {@code MethodLayers}.</p>
 *
 * @param methodSignature   the ADR-039-canonical signature (computed
 *                          by the paradigm's adapter when it builds
 *                          this record)
 * @param layerDescriptions outer-to-inner names of the resilience
 *                          layers wrapping this method; empty for
 *                          pass-through, default-method, and
 *                          Object-method routes
 * @param method            the original {@link Method} for canonical
 *                          identity disambiguation, or
 *                          {@link Optional#empty()} when no reflective
 *                          {@code Method} is available (function
 *                          paradigm)
 */
public record MethodLayers(
        String methodSignature,
        List<String> layerDescriptions,
        Optional<Method> method) {

    public MethodLayers {
        Objects.requireNonNull(methodSignature, "methodSignature");
        Objects.requireNonNull(method, "method");
        layerDescriptions = List.copyOf(
                Objects.requireNonNull(layerDescriptions, "layerDescriptions"));
    }
}
