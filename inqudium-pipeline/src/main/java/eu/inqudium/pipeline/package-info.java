/**
 * Pipeline composition primitive and its builder, per ADR-040.
 *
 * <p>The {@link eu.inqudium.pipeline.InqPipeline} interface and its
 * {@link eu.inqudium.pipeline.InqPipelineBuilder} are the composition
 * layer that integration modules (proxy, future functional dispatch,
 * future aspect adapters) consume. They live in this module rather
 * than in {@code inqudium-core} so that the dependency graph from
 * ADR-037 can be realised.</p>
 *
 * <p>The interface carries the default
 * {@link eu.inqudium.pipeline.InqPipeline#validateReferences
 * validateReferences} method, which checks an annotation
 * {@link eu.inqudium.annotation.evaluator.EvaluationResult} against
 * the pipeline's elements using the
 * {@code (elementType, paradigmTag, name)} triple-key from ADR-040
 * §3 Invariant 2. Integration dispatchers call evaluate-then-validate
 * during construction; see the proxy module's {@code ProxyBuilder}
 * for the orchestration.</p>
 */
package eu.inqudium.pipeline;
