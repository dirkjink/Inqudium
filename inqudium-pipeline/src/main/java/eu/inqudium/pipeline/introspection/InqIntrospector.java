package eu.inqudium.pipeline.introspection;

import eu.inqudium.pipeline.DetectionProxy;

import java.util.Optional;

/**
 * Central dispatch utility for paradigm-agnostic stack
 * introspection per ADR-039.
 *
 * <p>{@code InqIntrospector.inspect(instance)} examines an
 * arbitrary object and, if it represents a resilience stack
 * the library can introspect, returns an {@link InqStackInfo}
 * describing its composition. Callers (debugging tools,
 * diagnostic logging, educational examples) receive a
 * paradigm-agnostic DTO and can render it via
 * {@link InqStackRenderer} without knowing which paradigm
 * produced the stack.</p>
 *
 * <h2>Adapter dispatch</h2>
 *
 * <p>The dispatch chain is hardwired and closed for
 * third-party extension — new paradigms must be proposed
 * upstream, consistent with ADR-037's paradigm-dispatch
 * rationale. Phase B's bare-bones implementation registers
 * one adapter:</p>
 *
 * <ul>
 *   <li>{@link ProxyStackInfo proxy paradigm} —
 *       gated by {@link DetectionProxy#isPresent()};
 *       dispatched via {@link ProxyStackAdapterDelegation}
 *       since {@code inqudium-pipeline} cannot directly
 *       reference {@code inqudium-proxy} (ADR-037 module
 *       direction).</li>
 * </ul>
 *
 * <p>Three further adapters are anticipated when their
 * respective integration infrastructure exists:</p>
 *
 * <ul>
 *   <li><strong>FunctionStackAdapter</strong> — requires
 *       Function-Dispatch-Integration on {@link
 *       eu.inqudium.pipeline.InqPipeline} ({@code
 *       protect(Supplier)} and siblings per ADR-040 §6).
 *       Deferred per B.3 deferral notice in
 *       {@code REFACTORING_ADR_039.md}.</li>
 *   <li><strong>AspectJStackAdapter</strong> — requires
 *       {@code inqudium-aspect} to be rebuilt (currently
 *       stubbed per Phase A).</li>
 *   <li><strong>SpringAspectStackAdapter</strong> —
 *       requires {@code inqudium-spring} to be rebuilt
 *       (currently stubbed per Phase A).</li>
 * </ul>
 *
 * <p>Adding a future adapter is strictly additive: a new
 * detection probe (if the integration module is optional),
 * a new delegation bridge, and a new branch in
 * {@link #inspect(Object)} before the {@code return
 * Optional.empty()} terminus.</p>
 *
 * @since 0.10.0
 */
public final class InqIntrospector {

    private InqIntrospector() {
        // utility class
    }

    /**
     * Examines the given instance and, if any registered
     * adapter can introspect it, returns the corresponding
     * {@link InqStackInfo}.
     *
     * <p>If no adapter recognises the instance, returns
     * {@link Optional#empty()}. This includes the case
     * where the instance is unrelated to inqudium
     * (arbitrary user objects) and the case where the
     * relevant integration module is absent from the
     * classpath. {@code null} input is treated the same
     * way as an unrecognised object.</p>
     *
     * @param instance the object to inspect; may be
     *                 {@code null} (returns
     *                 {@link Optional#empty()})
     * @return the introspection DTO, or
     *         {@link Optional#empty()}
     */
    public static Optional<InqStackInfo> inspect(Object instance) {
        if (instance == null) {
            return Optional.empty();
        }

        // Proxy paradigm — gated by classpath probe + adapter's
        // own supports() check. Reflection cost is cold-path
        // (debugging, not per-invocation).
        if (DetectionProxy.isPresent()
                && ProxyStackAdapterDelegation.supports(instance)) {
            return Optional.of(ProxyStackAdapterDelegation.inspect(instance));
        }

        // Future: FunctionStackAdapter, AspectJStackAdapter,
        // SpringAspectStackAdapter branches slot in here, each
        // following the same pattern (Detection probe +
        // adapter delegation).

        return Optional.empty();
    }
}
