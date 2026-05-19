/**
 * Library-end-to-end tests for the imperative bulkhead.
 *
 * <p>The tests in this module exercise bulkhead library behaviour end-to-end under
 * realistic conditions. After Phase A of the post-polish refactor sequence stubbed
 * the annotation-driven aspect modules, the surviving content clusters around three
 * themes:
 *
 * <ul>
 *   <li><strong>Concurrency races</strong> — {@code BulkheadConcurrentRemovalAndPatchTest}
 *       interleaves runtime patches and structural removals at the snapshot-listener
 *       seam.</li>
 *   <li><strong>Lifecycle transitions</strong> — {@code BulkheadWrapperLifecycleTest}
 *       pins that decorator wrappers stay correct across cold-to-hot, strategy hot-swap,
 *       and structural removal of the underlying bulkhead.</li>
 *   <li><strong>Spring Boot graceful shutdown</strong> — {@code BulkheadSpringBootShutdownTest}
 *       pins that {@code InqRuntime#close()} fires through Spring's
 *       {@code DisposableBean} lifecycle when the application context is closed.</li>
 * </ul>
 *
 * <p>Each test class addresses one theme; method names describe one user scenario.
 * Reading the suite top-to-bottom is intended to feel like a tutorial of the library's
 * end-to-end behaviour.
 *
 * <p><strong>These are NOT examples of how to test a user's application.</strong> The
 * library-end-to-end tests live here so a future reader is not tempted to copy them into
 * their own test suite as a template — they are the library's safety net, not application
 * test patterns.
 *
 * <p>The aspect-driven AOP routing and hot-swap-through-AOP scenarios that previously
 * lived here (the {@code BulkheadSpringBoot*Test} integration + hot-swap pair, plus
 * {@code BulkheadWrapperFamilyTest}'s {@code AspectPipelineTerminal} smoke check) were
 * removed in Phase A of the post-polish refactor sequence — they depended on the
 * {@code eu.inqudium.spring.InqShieldAspect} bean, which the
 * {@code inqudium-spring} module no longer provides. A future Phase B (ADR-039) rebuilds
 * the aspect stack and the associated coverage.
 *
 * <p>The module produces no production artifact. Module-internal collaborators (synthetic
 * strategies, throwing closeables, tiny test-only services) live as static nested types
 * on the test classes that need them.
 *
 * <p>Closes the following carried-forward audit findings:
 * <ul>
 *   <li>2.12.3 — race between {@code markRemoved} and {@code onSnapshotChange} during
 *       hot-swap.</li>
 *   <li>2.17.3 — wrapper compatibility across the cold/hot/removed transitions.</li>
 * </ul>
 *
 * <p>Two related findings are closed elsewhere and are listed here only for cross-reference:
 * <ul>
 *   <li>2.12.4 ({@code closeStrategy} throw on hot-swap, strategy construction failure on
 *       cold-to-hot) — closed by {@code BulkheadHotPhaseFailureModeTest} in
 *       {@code inqudium-imperative}.</li>
 * </ul>
 *
 * <p>Findings whose Phase-A-removed coverage will be reinstated in Phase B (ADR-039):
 * <ul>
 *   <li>2.17.4 — wrapper and proxy tests against real bulkheads (post ADR-033); the
 *       {@code AspectPipelineTerminal} portion is deferred until the aspect stack is
 *       rebuilt.</li>
 *   <li>F-2.18-3 — AspectJ integration against a real bulkhead.</li>
 *   <li>F-2.19-6 — annotation-driven async dispatch through {@code InqBulkhead}.</li>
 *   <li>F-2.19-7 — Spring Boot integration against a real bulkhead via the aspect.</li>
 * </ul>
 */
package eu.inqudium.bulkhead.integration;
