/**
 * Phase-three dispatch helpers: {@code ObjectMethodHandler} implements
 * {@code equals} / {@code hashCode} / {@code toString} and the
 * {@code wait} / {@code notify} family on proxies per ADR-035 §8, and
 * {@code DetectionAsync} probes for {@code inqudium-imperative} on the
 * classpath per ADR-037 §4.
 *
 * <p>Paradigm classification — previously housed here in
 * {@code ParadigmDetector} — is now performed by the annotation
 * evaluator (ADR-046) and read from {@code MethodPlan.paradigm()};
 * see {@link eu.inqudium.proxy.construction.MethodDispatchEntryFactory}
 * for the dispatch routing.</p>
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.dispatch;
