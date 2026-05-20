/**
 * ADR-039 stack-introspection DTOs, central dispatch, and
 * paradigm-agnostic rendering.
 *
 * <p>The {@link eu.inqudium.pipeline.introspection.InqStackInfo
 * InqStackInfo} sealed hierarchy and its permits define the DTO
 * shape. {@link eu.inqudium.pipeline.introspection.InqIntrospector
 * InqIntrospector} dispatches an arbitrary input object to the
 * matching paradigm adapter.
 * {@link eu.inqudium.pipeline.introspection.InqStackRenderer
 * InqStackRenderer} renders an {@code InqStackInfo} as a
 * human-readable Unicode tree or a machine-readable JSON
 * document.</p>
 *
 * <p>Phase B's bare-bones implementation wires the proxy
 * adapter only. {@code FunctionStackAdapter} (deferred per the
 * B.3 deferral notice in {@code REFACTORING_ADR_039.md}),
 * {@code AspectJStackAdapter}, and
 * {@code SpringAspectStackAdapter} are anticipated when their
 * respective integration infrastructure exists.</p>
 *
 * @since 0.10.0
 */
package eu.inqudium.pipeline.introspection;
