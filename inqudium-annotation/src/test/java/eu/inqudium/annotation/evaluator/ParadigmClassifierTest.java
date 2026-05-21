package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.paradigm.AsyncTag;
import eu.inqudium.core.paradigm.CoroutinesTag;
import eu.inqudium.core.paradigm.ParadigmTag;
import eu.inqudium.core.paradigm.ReactiveTag;
import eu.inqudium.core.paradigm.RxJava3Tag;
import eu.inqudium.core.paradigm.SyncTag;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.Flow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ParadigmClassifier} and its three probe collaborators
 * ({@link Reactive}, {@link RxJava3}, {@link Coroutines}).
 *
 * <p>Each nested group covers one paradigm family or a specific algorithmic
 * concern. Fixture methods cover every paradigm sub-shape so the classifier
 * is exercised end-to-end against real external library types.</p>
 */
class ParadigmClassifierTest {

    @Nested
    class SyncFallback {

        @Test
        void void_method_classifies_as_sync() throws Exception {
            // Given
            Method method = Fixtures.class.getMethod("voidReturn");
            // When
            ParadigmTag tag = ParadigmClassifier.classify(method);
            // Then
            assertThat(tag).isSameAs(SyncTag.INSTANCE);
        }

        @Test
        void string_returning_method_classifies_as_sync() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("stringReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(SyncTag.INSTANCE);
        }

        @Test
        void primitive_returning_method_classifies_as_sync() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("intReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(SyncTag.INSTANCE);
        }

        @Test
        void custom_user_type_classifies_as_sync() throws Exception {
            // Given a method returning a user-defined type with no
            // paradigm classification
            Method method = Fixtures.class.getMethod("customTypeReturn");
            // When
            ParadigmTag tag = ParadigmClassifier.classify(method);
            // Then the conservative SyncTag fallback applies
            assertThat(tag).isSameAs(SyncTag.INSTANCE);
        }
    }

    @Nested
    class AsyncImperative {

        @Test
        void completion_stage_returning_method_classifies_as_async() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("completionStageReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(AsyncTag.INSTANCE);
        }

        @Test
        void completable_future_of_void_classifies_as_async() throws Exception {
            // What is to be tested? — A CompletableFuture<Void> method
            //   should classify as async. This confirms the ADR-046
            //   example: void-result async is still async by completion
            //   semantics, not sync by absence of a value.
            // Successful when? — the classifier returns AsyncTag.INSTANCE.
            // Why important? — CompletableFuture is a CompletionStage
            //   subtype; pinning the assignment-compatible check here
            //   guards the ladder ordering.
            Method method = Fixtures.class.getMethod("completableFutureVoidReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(AsyncTag.INSTANCE);
        }

        @Test
        void completable_future_of_string_classifies_as_async() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("completableFutureStringReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(AsyncTag.INSTANCE);
        }
    }

    @Nested
    class Reactor {

        @Test
        void mono_returning_method_classifies_as_reactive_mono() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("monoReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(ReactiveTag.MONO);
        }

        @Test
        void flux_returning_method_classifies_as_reactive_flux() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("fluxReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(ReactiveTag.FLUX);
        }
    }

    @Nested
    class RxJava3Suite {

        @Test
        void single_returning_method_classifies_as_rxjava3_single() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("singleReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(RxJava3Tag.SINGLE);
        }

        @Test
        void maybe_returning_method_classifies_as_rxjava3_maybe() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("maybeReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(RxJava3Tag.MAYBE);
        }

        @Test
        void completable_returning_method_classifies_as_rxjava3_completable() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("completableReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(RxJava3Tag.COMPLETABLE);
        }

        @Test
        void flowable_returning_method_classifies_as_rxjava3_flowable() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("flowableReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(RxJava3Tag.FLOWABLE);
        }

        @Test
        void observable_returning_method_classifies_as_rxjava3_observable() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("observableReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(RxJava3Tag.OBSERVABLE);
        }
    }

    @Nested
    class CoroutinesSuite {

        @Test
        void method_with_continuation_last_param_classifies_as_suspend() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("suspendStyleMethod", Continuation.class);
            assertThat(ParadigmClassifier.classify(method)).isSameAs(CoroutinesTag.SUSPEND);
        }

        @Test
        void method_without_continuation_param_does_not_classify_as_suspend() throws Exception {
            // What is to be tested? — A non-suspend Kotlin-style method
            //   (no Continuation last parameter) must NOT classify as
            //   SUSPEND. This guards against false positives in the
            //   isSuspendFunction probe.
            // Successful when? — the classifier returns something other
            //   than CoroutinesTag.SUSPEND for a plain String-returning
            //   method.
            // Why important? — The Continuation parameter is the only
            //   correct discriminator; a regression that flagged any
            //   method as suspend would mis-route all subsequent
            //   pipeline composition.
            Method method = Fixtures.class.getMethod("stringReturn");
            ParadigmTag tag = ParadigmClassifier.classify(method);
            assertThat(tag).isNotSameAs(CoroutinesTag.SUSPEND);
            assertThat(tag).isSameAs(SyncTag.INSTANCE);
        }

        @Test
        void deferred_returning_method_classifies_as_deferred() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("deferredReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(CoroutinesTag.DEFERRED);
        }

        @Test
        void job_returning_method_classifies_as_job() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("jobReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(CoroutinesTag.JOB);
        }

        @Test
        void flow_returning_method_classifies_as_flow() throws Exception {
            // Given / When / Then
            Method method = Fixtures.class.getMethod("flowReturn");
            assertThat(ParadigmClassifier.classify(method)).isSameAs(CoroutinesTag.FLOW);
        }

        @Test
        void deferred_is_classified_as_deferred_not_job() throws Exception {
            // What is to be tested? — Deferred<T> extends Job in the
            //   Kotlin coroutines class hierarchy. If the classifier
            //   checked Job before Deferred, every Deferred would
            //   classify as Job. This test pins the Deferred-before-Job
            //   order in Coroutines#classify(Method).
            // Successful when? — a Deferred-returning method classifies
            //   as DEFERRED, never as JOB.
            // Why important? — Without this ordering, all async-result
            //   coroutines would be mis-classified as fire-and-forget
            //   jobs, and pipeline composition would lose result
            //   propagation entirely.
            Method method = Fixtures.class.getMethod("deferredReturn");
            ParadigmTag tag = ParadigmClassifier.classify(method);
            assertThat(tag).isSameAs(CoroutinesTag.DEFERRED);
            assertThat(tag).isNotSameAs(CoroutinesTag.JOB);
        }
    }

    @Nested
    class NullHandling {

        @Test
        void classify_throws_npe_when_method_is_null() {
            // Given / When / Then
            assertThatThrownBy(() -> ParadigmClassifier.classify(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("method");
        }
    }

    /**
     * Probe-level tests that bypass {@link ParadigmClassifier} and call
     * the individual probes directly. The probes are package-private —
     * accessible from the test because the test lives in the same
     * package. The purpose here is to pin the empty-return contract:
     * every probe must return {@link java.util.Optional#empty()} for a
     * type outside its own family, regardless of whether the family's
     * library is on the classpath.
     */
    @Nested
    class ProbeReturnsEmptyForUnrelatedTypes {

        @Test
        void reactive_probe_returns_empty_for_a_string_return_type() {
            // Given / When
            var result = Reactive.classify(String.class);
            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void rxjava3_probe_returns_empty_for_a_string_return_type() {
            // Given / When
            var result = RxJava3.classify(String.class);
            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void coroutines_probe_returns_empty_for_a_non_suspend_method() throws Exception {
            // Given a plain method with no Continuation parameter and a
            // non-coroutine return type
            Method method = Fixtures.class.getMethod("stringReturn");
            // When
            var result = Coroutines.classify(method);
            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void coroutines_probe_returns_empty_for_zero_arg_method() throws Exception {
            // What is to be tested? — The isSuspendFunction helper
            //   guards against empty parameter arrays before indexing
            //   into them. A zero-arg method exercises that guard.
            // Successful when? — the probe returns Optional.empty(),
            //   never throws ArrayIndexOutOfBoundsException.
            // Why important? — Without the guard the probe would
            //   throw on every parameter-less method on every service
            //   interface, breaking the classifier across the entire
            //   codebase.
            Method method = Fixtures.class.getMethod("voidReturn");
            var result = Coroutines.classify(method);
            assertThat(result).isEmpty();
        }
    }

    /**
     * Fixture methods covering every paradigm sub-shape. Each
     * method is {@code public} so {@link Class#getMethod} works
     * across packages.
     *
     * <p>Method bodies are intentionally trivial — only the
     * signature matters for paradigm classification.</p>
     */
    public static class Fixtures {

        public void voidReturn() { }

        public String stringReturn() { return ""; }

        public int intReturn() { return 0; }

        public CustomType customTypeReturn() { return new CustomType(); }

        public CompletionStage<String> completionStageReturn() {
            return CompletableFuture.completedFuture("");
        }

        public CompletableFuture<Void> completableFutureVoidReturn() {
            return CompletableFuture.completedFuture(null);
        }

        public CompletableFuture<String> completableFutureStringReturn() {
            return CompletableFuture.completedFuture("");
        }

        public Mono<String> monoReturn() { return Mono.empty(); }

        public Flux<String> fluxReturn() { return Flux.empty(); }

        public Single<String> singleReturn() { return Single.just(""); }

        public Maybe<String> maybeReturn() { return Maybe.empty(); }

        public Completable completableReturn() { return Completable.complete(); }

        public Flowable<String> flowableReturn() { return Flowable.empty(); }

        public Observable<String> observableReturn() { return Observable.empty(); }

        public Object suspendStyleMethod(Continuation<? super String> cont) {
            return null;
        }

        public Deferred<String> deferredReturn() { return null; }

        public Job jobReturn() { return null; }

        public Flow<String> flowReturn() { return null; }
    }

    public static class CustomType { }
}
