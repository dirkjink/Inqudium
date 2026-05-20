package eu.inqudium.pipeline.introspection;

import eu.inqudium.core.element.InqElement;

import java.util.List;
import java.util.Optional;

/**
 * Sealed root of paradigm-specific stack introspection DTOs
 * per ADR-039. Each permit captures the introspection result
 * for one wrapping paradigm.
 *
 * <p>Phase B's bare-bones implementation registers two permits:
 * {@link FunctionStackInfo} for the synchronous-imperative
 * function-wrapper chain and {@link ProxyStackInfo} for the
 * JDK proxy stack. Two further permits — for AspectJ-woven
 * stacks and Spring-AOP-proxied stacks — are anticipated when
 * the {@code inqudium-aspect} and {@code inqudium-spring}
 * modules are rebuilt; until then the sealed declaration's
 * exhaustiveness check is bounded by paradigms whose
 * implementing modules are present.</p>
 *
 * <h2>Common shape</h2>
 *
 * <p>Every paradigm-specific permit exposes the same four
 * accessors:</p>
 *
 * <ul>
 *   <li>{@link #stackId()} — the per-stack ID allocated by
 *       {@code PipelineIds.nextStackId()}.</li>
 *   <li>{@link #targetType()} — the target type if the
 *       paradigm has one (e.g. service interface for the
 *       proxy paradigm; always {@link Optional#empty() empty}
 *       for the function paradigm).</li>
 *   <li>{@link #elements()} — the chain's
 *       {@link InqElement}s in outer-to-inner order, as an
 *       immutable snapshot at construction time.</li>
 *   <li>{@link #methodLayers()} — per-method layer
 *       descriptions; one entry per method the paradigm
 *       captures (one entry for function-paradigm; up to
 *       one per service method for the proxy paradigm).</li>
 * </ul>
 *
 * @see FunctionStackInfo
 * @see ProxyStackInfo
 * @since 0.10.0
 */
public sealed interface InqStackInfo
        permits FunctionStackInfo, ProxyStackInfo {

    /**
     * @return the stack ID identifying this introspection
     *         result. Allocated by
     *         {@code PipelineIds.nextStackId()} (per ADR-034).
     */
    long stackId();

    /**
     * @return the target type if the paradigm has one (e.g.
     *         the service interface for the proxy paradigm).
     *         {@link Optional#empty()} if the paradigm has no
     *         single target type (e.g. function wrappers
     *         decorate an arbitrary functional interface
     *         instance, not a typed service).
     */
    Optional<Class<?>> targetType();

    /**
     * @return the chain's {@link InqElement}s in outer-to-
     *         inner order. Snapshot taken at construction
     *         time of this introspection DTO.
     */
    List<InqElement> elements();

    /**
     * @return per-method layer descriptions. One entry per
     *         method the paradigm captures. For the function
     *         paradigm: a single entry for the SAM method;
     *         for the proxy paradigm: one entry per service
     *         method dispatched.
     */
    List<MethodLayers> methodLayers();
}
