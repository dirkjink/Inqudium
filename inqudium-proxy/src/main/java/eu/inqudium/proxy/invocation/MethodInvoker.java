package eu.inqudium.proxy.invocation;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Bound invocation site for a single {@code (target, method)} pair.
 * The {@link #invoke(Object[])} method calls the bound method on the
 * bound target with the supplied arguments and returns the result.
 *
 * <p><strong>Internal API.</strong> This interface is {@code public}
 * only because Java without modules requires package-cross visibility
 * for sealed types referenced from sibling subpackages
 * ({@code construction/}, {@code folding/}, {@code entries/}). The
 * type is not part of {@code inqudium-proxy}'s stable public API and
 * may change in future minor releases. Application code should not
 * depend on it.</p>
 *
 * <p>Implementation strategy is selected via the JVM property
 * {@code inqudium.proxy.invoker} (values: {@code mh} default,
 * {@code reflective} fallback). See {@link #create(Object, Method)}.</p>
 *
 * @see MethodHandleInvoker
 * @see ReflectiveInvoker
 */
public sealed interface MethodInvoker permits MethodHandleInvoker, ReflectiveInvoker {

    /**
     * Invokes the bound method on the bound target with the given
     * arguments. Returns the method's return value, or {@code null}
     * for {@code void} methods.
     *
     * <p>If the underlying method throws, the original throwable is
     * propagated — possibly wrapped in
     * {@link java.lang.reflect.InvocationTargetException} (for the
     * reflective implementation). Callers responsible for unwrapping
     * use {@code ThrowableUnwrap}.</p>
     */
    Object invoke(Object[] args) throws Throwable;

    /**
     * Cached value of the JVM property {@code inqudium.proxy.invoker},
     * read once at class init. The value is validated per-call by
     * {@link #create(Object, Method)} — a malformed value surfaces
     * as {@link IllegalArgumentException} the first time the factory
     * is touched (rather than as {@code ExceptionInInitializerError}
     * at class load).
     */
    String CACHED_INVOKER_PROPERTY =
            System.getProperty("inqudium.proxy.invoker", "mh");

    /**
     * Creates a {@code MethodInvoker} bound to {@code (target,
     * method)}, picking the implementation strategy from the JVM
     * property {@code inqudium.proxy.invoker}.
     *
     * <p>The property value is captured once at class init in
     * {@link #CACHED_INVOKER_PROPERTY}; the switch below validates
     * it on every call. Per-call work is one cached-field read plus
     * a {@code String} switch — no {@code System.getProperty(...)}
     * map lookup per call.</p>
     *
     * @param target the receiver instance; must not be {@code null}
     * @param method the method to invoke; must not be {@code null}
     * @throws IllegalArgumentException if the JVM property value is
     *                                  neither {@code mh} nor
     *                                  {@code reflective}
     */
    static MethodInvoker create(Object target, Method method) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        return switch (CACHED_INVOKER_PROPERTY) {
            case "mh" -> new MethodHandleInvoker(target, method);
            case "reflective" -> new ReflectiveInvoker(target, method);
            default -> throw new IllegalArgumentException(
                    "Unknown invoker type '" + CACHED_INVOKER_PROPERTY
                            + "' for property inqudium.proxy.invoker "
                            + "(expected 'mh' or 'reflective')");
        };
    }
}
