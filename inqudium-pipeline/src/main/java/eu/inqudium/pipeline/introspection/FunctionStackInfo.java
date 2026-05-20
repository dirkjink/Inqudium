package eu.inqudium.pipeline.introspection;

import eu.inqudium.core.element.InqElement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Introspection DTO for a function-wrapper stack per ADR-039.
 *
 * <p>Built by {@code FunctionStackAdapter} (B.3) from an
 * {@code AbstractBaseWrapper<?, ?>} instance and its chain.
 * The wrapper chain has no single target type — function
 * wrappers decorate arbitrary {@code Supplier}/{@code Runnable}/
 * {@code Function}/{@code Callable} instances — so
 * {@link #targetType()} is always {@link Optional#empty()}.</p>
 *
 * <p>The wrapper chain captures one SAM method, so
 * {@link #methodLayers()} has exactly one entry whose
 * {@link MethodLayers#method() method()} is
 * {@link Optional#empty()} (tier-1 reflection-based resolution
 * doesn't apply to lambdas; see ADR-039's "SerializedLambda
 * tier-2 method resolution" — deferred future work).</p>
 *
 * @param stackId      the per-stack ID allocated by
 *                     {@code PipelineIds.nextStackId()}
 * @param elements     outer-to-inner snapshot of the chain's
 *                     {@link InqElement}s
 * @param methodLayers always single-entry: the SAM method
 *                     and its layer descriptions
 * @since 0.10.0
 */
public record FunctionStackInfo(
        long stackId,
        List<InqElement> elements,
        List<MethodLayers> methodLayers
) implements InqStackInfo {

    public FunctionStackInfo {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(methodLayers, "methodLayers");
        elements = List.copyOf(elements);
        methodLayers = List.copyOf(methodLayers);
    }

    @Override
    public Optional<Class<?>> targetType() {
        return Optional.empty();
    }
}
