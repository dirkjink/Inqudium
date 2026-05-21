package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.paradigm.RxJava3Tag;

import java.util.Optional;

/**
 * Probe for the RxJava 3 paradigm. Detects the five reactive
 * return types via lazy class loading; if RxJava 3 is not on the
 * classpath, every probe call returns {@link Optional#empty()}.
 *
 * <p>This class never references {@code io.reactivex.rxjava3.*}
 * by class literal — its bytecode is loadable on any classpath.</p>
 *
 * <p>The five RxJava 3 types are unrelated at the JVM-subtype
 * level (no one is a subtype of another within the set the
 * library cares about). Order of checks below is therefore
 * arbitrary — the helper checks them in declaration order.</p>
 *
 * <p>Package-private — used only by {@link ParadigmClassifier}.</p>
 */
final class RxJava3 {

    private static final Optional<Class<?>> SINGLE_CLASS =
            loadType("io.reactivex.rxjava3.core.Single");

    private static final Optional<Class<?>> MAYBE_CLASS =
            loadType("io.reactivex.rxjava3.core.Maybe");

    private static final Optional<Class<?>> COMPLETABLE_CLASS =
            loadType("io.reactivex.rxjava3.core.Completable");

    private static final Optional<Class<?>> FLOWABLE_CLASS =
            loadType("io.reactivex.rxjava3.core.Flowable");

    private static final Optional<Class<?>> OBSERVABLE_CLASS =
            loadType("io.reactivex.rxjava3.core.Observable");

    private RxJava3() {
    }

    /**
     * Returns the {@link RxJava3Tag} sub-tag for the given return
     * type, or {@link Optional#empty()} if the return type is not
     * an RxJava 3 type.
     */
    static Optional<RxJava3Tag> classify(Class<?> returnType) {
        if (SINGLE_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(RxJava3Tag.SINGLE);
        }
        if (MAYBE_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(RxJava3Tag.MAYBE);
        }
        if (COMPLETABLE_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(RxJava3Tag.COMPLETABLE);
        }
        if (FLOWABLE_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(RxJava3Tag.FLOWABLE);
        }
        if (OBSERVABLE_CLASS.map(c -> c.isAssignableFrom(returnType)).orElse(false)) {
            return Optional.of(RxJava3Tag.OBSERVABLE);
        }
        return Optional.empty();
    }

    private static Optional<Class<?>> loadType(String fqn) {
        try {
            return Optional.of(
                    Class.forName(fqn, false, RxJava3.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
