package eu.inqudium.pipeline.introspection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflective bridge from {@link InqIntrospector#inspect(Object)}
 * to {@code eu.inqudium.proxy.introspection.ProxyStackAdapter}.
 *
 * <p>This bridge exists because a direct class-literal reference
 * from {@code inqudium-pipeline} to
 * {@code eu.inqudium.proxy.introspection.ProxyStackAdapter}
 * would require {@code inqudium-pipeline} to compile-depend on
 * {@code inqudium-proxy}. That is impossible: {@code inqudium-proxy}
 * already depends on {@code inqudium-pipeline}, so a
 * class-literal back-reference would create a Maven cycle. The
 * same asymmetry is documented for {@code ProxyDelegation} in
 * the {@code eu.inqudium.pipeline} package; this class applies
 * the same pattern to introspection dispatch.</p>
 *
 * <p>Loaded lazily — only when {@link InqIntrospector#inspect(Object)}
 * is actually called and {@code DetectionProxy.isPresent()} has
 * returned {@code true}. The static initialiser's
 * {@link Class#forName(String, boolean, ClassLoader)} therefore
 * succeeds by precondition; if it fails, the classpath is
 * inconsistent with what {@code DetectionProxy} reported.</p>
 *
 * <p>Two reflective lookups at class initialisation; one
 * {@link Method#invoke(Object, Object...)} per
 * {@code InqIntrospector.inspect(...)} call. The reflection
 * overhead is cold-path — introspection is a debugging /
 * diagnostic concern, not a per-method-invocation hot path.</p>
 *
 * <p><strong>Transitional bridge.</strong> If a future refactor
 * splits the introspection API differently (SPI via
 * ServiceLoader, for instance), this class can be replaced.
 * The reflection pattern is the simplest correct solution for
 * the directional asymmetry imposed by ADR-037.</p>
 *
 * @since 0.10.0
 */
final class ProxyStackAdapterDelegation {

    private static final Method SUPPORTS_METHOD;
    private static final Method INSPECT_METHOD;

    static {
        try {
            Class<?> adapter = Class.forName(
                    "eu.inqudium.proxy.introspection.ProxyStackAdapter",
                    false,
                    ProxyStackAdapterDelegation.class.getClassLoader());
            SUPPORTS_METHOD = adapter.getMethod("supports", Object.class);
            INSPECT_METHOD = adapter.getMethod("inspect", Object.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalStateException(
                    "DetectionProxy reported the proxy module as present "
                            + "but ProxyStackAdapter is not loadable. "
                            + "This indicates a corrupt or mismatched classpath.", e);
        }
    }

    private ProxyStackAdapterDelegation() {
        // utility class
    }

    /**
     * @return whether {@code ProxyStackAdapter.supports(instance)}
     *         returns {@code true}; bridged reflectively.
     */
    static boolean supports(Object instance) {
        try {
            return (boolean) SUPPORTS_METHOD.invoke(null, instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Reflective invocation of ProxyStackAdapter.supports refused", e);
        } catch (InvocationTargetException ite) {
            return unwrapAndRethrow(ite);
        }
    }

    /**
     * @return the {@code ProxyStackInfo} produced by
     *         {@code ProxyStackAdapter.inspect(instance)};
     *         bridged reflectively.
     */
    static ProxyStackInfo inspect(Object instance) {
        try {
            return (ProxyStackInfo) INSPECT_METHOD.invoke(null, instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Reflective invocation of ProxyStackAdapter.inspect refused", e);
        } catch (InvocationTargetException ite) {
            return unwrapAndRethrow(ite);
        }
    }

    private static <T> T unwrapAndRethrow(InvocationTargetException ite) {
        Throwable cause = ite.getCause();
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        throw new IllegalStateException(
                "ProxyStackAdapter threw a checked exception "
                        + "(this should not happen — supports/inspect "
                        + "declare no checked throws)", cause);
    }
}
