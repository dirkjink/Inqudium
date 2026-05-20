/**
 * Proxy-side introspection adapter per ADR-039.
 *
 * <p>This package exposes two public types:</p>
 * <ul>
 *   <li>{@link eu.inqudium.proxy.introspection.ProxyStackAdapter} —
 *       static {@code supports} / {@code inspect} methods for
 *       proxies produced by {@code ProxyDispatcher.protect(...)}.</li>
 *   <li>{@link eu.inqudium.proxy.introspection.MethodSignatureFormatter}
 *       — utility producing ADR-039's canonical
 *       {@code Class.method(P1, P2)} format.</li>
 * </ul>
 *
 * <p>The introspection DTOs themselves —
 * {@link eu.inqudium.pipeline.introspection.ProxyStackInfo
 * ProxyStackInfo} and its
 * {@link eu.inqudium.pipeline.introspection.MethodLayers MethodLayers}
 * components — live in
 * {@code eu.inqudium.pipeline.introspection} as permits of the
 * {@link eu.inqudium.pipeline.introspection.InqStackInfo InqStackInfo}
 * sealed hierarchy. {@code ProxyStackAdapter.inspect(...)} returns a
 * {@code ProxyStackInfo} populated from the proxy's
 * {@code InqInvocationHandler}.</p>
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.introspection;
