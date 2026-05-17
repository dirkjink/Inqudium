package eu.inqudium.proxy.invocation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * {@link MethodInvoker} implementation that uses
 * {@link Method#invoke(Object, Object...)} directly. Selected when
 * the JVM property {@code inqudium.proxy.invoker=reflective}. Mostly
 * useful for benchmarks comparing against the
 * {@link MethodHandleInvoker} default.
 *
 * <p>Unlike the {@code MethodHandle} variant, this implementation
 * wraps the method's thrown exceptions in
 * {@link InvocationTargetException}. Callers unwrap via
 * {@code ThrowableUnwrap}.</p>
 *
 * <p><strong>JPMS note.</strong> The constructor calls
 * {@code setAccessible(true)} on the target method, which under
 * strict JPMS configurations requires the target's module to be
 * open to {@code eu.inqudium.proxy}. Users running with
 * {@code inqudium.proxy.invoker=reflective} on a strict JPMS
 * deployment may need an {@code --add-opens} JVM flag specifying
 * the target's module. The default {@code mh}
 * (MethodHandle-based) path is not affected because it uses
 * lookup-based access rather than {@code setAccessible}.</p>
 */
final class ReflectiveInvoker implements MethodInvoker {

    private final Object target;
    private final Method method;

    ReflectiveInvoker(Object target, Method method) {
        this.target = target;
        this.method = method;
        // The proxy bypasses access checks on service-interface methods.
        // setAccessible(true) is safe here because the method is part of
        // a service-interface contract the application is actively using.
        method.setAccessible(true);
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            // Should not happen — setAccessible(true) ran in the constructor.
            throw new IllegalStateException(
                    "Reflective invocation refused for " + method, e);
        }
        // InvocationTargetException is left unwrapped here; the caller
        // (typically ExceptionClassifier via ThrowableUnwrap) unwraps it.
    }
}
