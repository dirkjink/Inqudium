package eu.inqudium.pipeline.introspection;

import eu.inqudium.core.element.InqElement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Introspection DTO for a proxy stack, per ADR-039.
 *
 * <p><strong>Public API.</strong> Implements
 * {@link InqStackInfo} as the proxy-paradigm permit of the
 * sealed introspection hierarchy.</p>
 *
 * @param stackId      the per-proxy stack ID allocated by
 *                     {@code PipelineIds.nextStackId()} (per ADR-034)
 * @param targetType   the service interface the proxy implements;
 *                     always present for the proxy paradigm but
 *                     declared as {@link Optional} per ADR-039
 *                     so the type matches the
 *                     {@link InqStackInfo#targetType() sealed-interface contract}
 * @param elements     the pipeline's elements at construction time
 *                     (immutable snapshot)
 * @param methodLayers one {@link MethodLayers} per service method
 *                     (Object methods, default methods, and pass-through
 *                     methods produce empty layer lists)
 */
public record ProxyStackInfo(
        long stackId,
        Optional<Class<?>> targetType,
        List<InqElement> elements,
        List<MethodLayers> methodLayers
) implements InqStackInfo {

    public ProxyStackInfo {
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(methodLayers, "methodLayers");
        elements = List.copyOf(elements);
        methodLayers = List.copyOf(methodLayers);
    }
}
