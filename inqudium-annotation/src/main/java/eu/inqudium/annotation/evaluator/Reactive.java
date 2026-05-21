package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.paradigm.ReactiveTag;

import java.util.Optional;

/**
 * Probe for the Project Reactor paradigm. Detects {@code Mono<T>}
 * and {@code Flux<T>} return types via lazy class loading; if
 * Reactor is not on the classpath, every probe call returns
 * {@link Optional#empty()}.
 *
 * <p>This class never references {@code reactor.core.publisher.*}
 * by class literal — its bytecode is loadable on any classpath.
 * The external types are resolved at class init via
 * {@link Class#forName(String, boolean, ClassLoader)} with
 * {@code initialize=false}, and wrapped in {@link Optional}.</p>
 *
 * <p>Package-private — used only by {@link ParadigmClassifier}.</p>
 */
final class Reactive {

    private static final Optional<Class<?>> MONO_CLASS =
            loadType("reactor.core.publisher.Mono");

    private static final Optional<Class<?>> FLUX_CLASS =
            loadType("reactor.core.publisher.Flux");

    private Reactive() {
    }

    /**
     * Returns the {@link ReactiveTag} sub-tag for the given return
     * type, or {@link Optional#empty()} if the return type is not
     * a reactor type (including the case where Reactor is absent
     * from the classpath).
     */
    static Optional<ReactiveTag> classify(Class<?> returnType) {
        if (MONO_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(ReactiveTag.MONO);
        }
        if (FLUX_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(ReactiveTag.FLUX);
        }
        return Optional.empty();
    }

    private static Optional<Class<?>> loadType(String fqn) {
        try {
            return Optional.of(
                    Class.forName(fqn, false, Reactive.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
