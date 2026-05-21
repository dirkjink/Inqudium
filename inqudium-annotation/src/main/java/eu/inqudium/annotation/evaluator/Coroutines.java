package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.paradigm.CoroutinesTag;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Probe for the Kotlin coroutines paradigm. Detects four shapes:
 *
 * <ul>
 *   <li>{@code suspend fun} — recognised by a
 *       {@code kotlin.coroutines.Continuation} parameter as the
 *       last formal parameter (added by the Kotlin compiler to
 *       the JVM-level signature).</li>
 *   <li>{@code kotlinx.coroutines.Deferred<T>} return type.</li>
 *   <li>{@code kotlinx.coroutines.Job} return type.</li>
 *   <li>{@code kotlinx.coroutines.flow.Flow<T>} return type.</li>
 * </ul>
 *
 * <p>Ordering matters: {@code Deferred<T>} extends {@code Job} in
 * Kotlin coroutines, so {@code Deferred} must be checked before
 * {@code Job}. Otherwise every {@code Deferred} classifies as
 * {@code Job}.</p>
 *
 * <p>This class never references {@code kotlinx.coroutines.*} or
 * {@code kotlin.coroutines.*} by class literal — its bytecode is
 * loadable on any classpath.</p>
 *
 * <p>Package-private — used only by {@link ParadigmClassifier}.</p>
 */
final class Coroutines {

    private static final Optional<Class<?>> CONTINUATION_CLASS =
            loadType("kotlin.coroutines.Continuation");

    private static final Optional<Class<?>> DEFERRED_CLASS =
            loadType("kotlinx.coroutines.Deferred");

    private static final Optional<Class<?>> JOB_CLASS =
            loadType("kotlinx.coroutines.Job");

    private static final Optional<Class<?>> FLOW_CLASS =
            loadType("kotlinx.coroutines.flow.Flow");

    private Coroutines() {
    }

    /**
     * Returns the {@link CoroutinesTag} sub-tag for the given
     * method, or {@link Optional#empty()} if the method is not a
     * coroutine shape.
     */
    static Optional<CoroutinesTag> classify(Method method) {
        if (isSuspendFunction(method)) {
            return Optional.of(CoroutinesTag.SUSPEND);
        }

        Class<?> returnType = method.getReturnType();

        // Deferred is a subtype of Job — check Deferred first.
        if (DEFERRED_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(CoroutinesTag.DEFERRED);
        }
        if (JOB_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(CoroutinesTag.JOB);
        }
        if (FLOW_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(CoroutinesTag.FLOW);
        }
        return Optional.empty();
    }

    private static boolean isSuspendFunction(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return false;
        }
        Class<?> lastParam = params[params.length - 1];
        return CONTINUATION_CLASS
                .map(c -> c.isAssignableFrom(lastParam))
                .orElse(false);
    }

    private static Optional<Class<?>> loadType(String fqn) {
        try {
            return Optional.of(
                    Class.forName(fqn, false, Coroutines.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
