package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.CoroutinesTag;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.ReactiveTag;
import eu.inqudium.core.element.paradigm.RxJava3Tag;
import eu.inqudium.core.element.paradigm.SyncTag;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Classifies a {@link Method} by paradigm. The classifier is the
 * single point that decides {@code SyncTag}, {@code AsyncTag},
 * or one of the sub-tags of {@code ReactiveTag}, {@code RxJava3Tag},
 * {@code CoroutinesTag}.
 *
 * <p>Algorithm: a fall-through ladder of paradigm probes, then a
 * {@link CompletionStage} fallback for async-imperative, then
 * {@link SyncTag} as the universal default.</p>
 *
 * <p>The order — Reactive, RxJava3, Coroutines, CompletionStage,
 * Sync — reflects that paradigm-specific external types are
 * checked before the JDK fallback. Reactive's {@code Mono} and
 * {@code Flux} are unrelated to {@link CompletionStage}, so order
 * between Reactive and CompletionStage doesn't matter — the
 * convention is "external libraries first".</p>
 *
 * <p>Custom user types fall through to {@link SyncTag}. This is
 * the correct conservative default — the library's paradigm tags
 * only recognise paradigms the library supports.</p>
 *
 * <p>Package-private — internal collaborator of
 * {@link DefaultAnnotationEvaluator}.</p>
 */
final class ParadigmClassifier {

    private ParadigmClassifier() {
    }

    /**
     * Classifies the given method's paradigm.
     *
     * @param method the method to classify; must not be null
     * @return the most specific {@link ParadigmTag} for the method
     */
    static ParadigmTag classify(Method method) {
        Objects.requireNonNull(method, "method");

        Class<?> returnType = method.getReturnType();

        Optional<ReactiveTag> reactive = Reactive.classify(returnType);
        if (reactive.isPresent()) {
            return reactive.get();
        }

        Optional<RxJava3Tag> rxjava3 = RxJava3.classify(returnType);
        if (rxjava3.isPresent()) {
            return rxjava3.get();
        }

        Optional<CoroutinesTag> coroutines = Coroutines.classify(method);
        if (coroutines.isPresent()) {
            return coroutines.get();
        }

        if (CompletionStage.class.isAssignableFrom(returnType)) {
            return AsyncTag.INSTANCE;
        }

        return SyncTag.INSTANCE;
    }
}
