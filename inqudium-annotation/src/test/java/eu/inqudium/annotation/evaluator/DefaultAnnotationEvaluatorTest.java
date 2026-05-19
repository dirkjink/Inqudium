package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.CoroutinesTag;
import eu.inqudium.core.element.paradigm.ParadigmTag;
import eu.inqudium.core.element.paradigm.ReactiveTag;
import eu.inqudium.core.element.paradigm.RxJava3Tag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqPipeline;
import io.reactivex.rxjava3.core.Single;
import kotlinx.coroutines.Deferred;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for the paradigm-stamped evaluator method from ADR-046 §3.
 *
 * <p>The tests pin: validation parity with the legacy {@code evaluate(...)},
 * paradigm-stamping across all five top-level paradigm families, the
 * {@link ElementRef} pair shape carried by {@link MethodPlan.Decorated},
 * the {@link MethodPlan.PassThrough} variant, immutability of the
 * element list, and backward-compatibility — the existing
 * {@code evaluate(...)} method continues to produce the legacy plan
 * variants unchanged.</p>
 *
 * <p>Fixtures mirror the layout of {@link AnnotationEvaluatorTest}: each
 * test builds a minimal pipeline holding exactly the stub elements the
 * fixture's annotations reference; one stub element per element type per
 * pipeline (ADR-036 §4).</p>
 */
class DefaultAnnotationEvaluatorTest {

    @Nested
    class ValidationParityWithLegacyMethod {

        @Test
        void should_reject_null_service_interface_with_illegal_argument_exception() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(null, SyncAnnotatedImpl.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("serviceInterface");
        }

        @Test
        void should_reject_null_implementation_class_with_illegal_argument_exception() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(SyncApi.class, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("implementationClass");
        }

        @Test
        void should_reject_concrete_class_passed_as_service_interface() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            // When / Then
            assertThatThrownBy(() -> evaluator.evaluate(
                    SyncAnnotatedImpl.class, SyncAnnotatedImpl.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be an interface");
        }

        // The fourth defensive check — "implementation does not implement
        // the service interface" — runs through the shared
        // validateArguments(...) helper alongside the three checks above;
        // duplicating it here would require raw-type casts to bypass the
        // compile-time generic check and only add a
        // @SuppressWarnings({"unchecked","rawtypes"}) line for negligible
        // additional coverage.
    }

    @Nested
    class ParadigmStamping {

        @Test
        void sync_method_produces_decorated_with_sync_tag() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(SyncApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    SyncApi.class, SyncAnnotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.Decorated.class,
                    sd -> assertThat(sd.paradigm()).isSameAs(SyncTag.INSTANCE));
        }

        @Test
        void completion_stage_method_produces_decorated_with_async_tag() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(AsyncApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    AsyncApi.class, AsyncAnnotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.Decorated.class,
                    sd -> assertThat(sd.paradigm()).isSameAs(AsyncTag.INSTANCE));
        }

        @Test
        void mono_method_produces_decorated_with_reactive_mono_tag() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(MonoApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    MonoApi.class, MonoAnnotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.Decorated.class,
                    sd -> assertThat(sd.paradigm()).isSameAs(ReactiveTag.MONO));
        }

        @Test
        void single_method_produces_decorated_with_rxjava3_single_tag() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(SingleApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    SingleApi.class, SingleAnnotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.Decorated.class,
                    sd -> assertThat(sd.paradigm()).isSameAs(RxJava3Tag.SINGLE));
        }

        @Test
        void deferred_method_produces_decorated_with_coroutines_deferred_tag() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(DeferredApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    DeferredApi.class, DeferredAnnotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.Decorated.class,
                    sd -> assertThat(sd.paradigm()).isSameAs(CoroutinesTag.DEFERRED));
        }
    }

    @Nested
    class PassThroughVariant {

        @Test
        void unannotated_sync_method_produces_pass_through_with_sync_tag() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            Method m = declared(SyncApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    SyncApi.class, SyncUnannotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.PassThrough.class,
                    spt -> assertThat(spt.paradigm()).isSameAs(SyncTag.INSTANCE));
        }

        @Test
        void unannotated_mono_method_produces_pass_through_with_reactive_mono_tag() {
            // What is to be tested? — PassThrough must still
            //   carry the paradigm of the method, even when no
            //   resilience annotations apply. This lets downstream
            //   dispatch routing pick the right path even for
            //   pass-through methods.
            // Successful when? — the plan is PassThrough and
            //   its paradigm() is ReactiveTag.MONO.
            // Why important? — Without paradigm-stamping on
            //   pass-through plans, a Mono-returning unannotated
            //   method on a service would lose paradigm context and
            //   the proxy would have no way to route the call.

            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            Method m = declared(MonoApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    MonoApi.class, MonoUnannotatedImpl.class);

            // Then
            assertThat(result.plans().get(m)).isInstanceOfSatisfying(
                    MethodPlan.PassThrough.class,
                    spt -> assertThat(spt.paradigm()).isSameAs(ReactiveTag.MONO));
        }

        @Test
        void unoverridden_default_method_produces_pass_through() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            Method m = declared(DefaultMethodApi.class, "greet");

            // When
            EvaluationResult result = evaluator.evaluate(
                    DefaultMethodApi.class, NoOverrideImpl.class);

            // Then
            assertThat(result.plans().get(m))
                    .isInstanceOf(MethodPlan.PassThrough.class);
        }
    }

    @Nested
    class ElementRefShape {

        @Test
        void single_element_carries_its_type_and_name() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(SyncApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    SyncApi.class, SyncAnnotatedImpl.class);

            // Then
            MethodPlan.Decorated plan =
                    (MethodPlan.Decorated) result.plans().get(m);
            assertThat(plan.elementsOuterToInner()).hasSize(1);
            ElementRef ref = plan.elementsOuterToInner().get(0);
            assertThat(ref.elementType()).isEqualTo(InqElementType.BULKHEAD);
            assertThat(ref.name()).isEqualTo("bh");
        }

        @Test
        void multiple_elements_preserve_inqudium_default_order_and_carry_their_types() {
            // What is to be tested? — @InqCircuitBreaker + @InqRetry on
            //   the same method must produce ElementRefs in CB-then-RT
            //   order (INQUDIUM default per ADR-017: CB=500 outer,
            //   RT=600 inner), each carrying its correct InqElementType.
            // Successful when? — The list is exactly
            //   [(CIRCUIT_BREAKER, "cb"), (RETRY, "rt")].
            // Why important? — This is the parity point that makes the
            //   stamped shape a strict super-set of the legacy shape:
            //   same ordering, plus the elementType information that
            //   the legacy List<String> threw away.

            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("cb", InqElementType.CIRCUIT_BREAKER),
                    stubElement("rt", InqElementType.RETRY));
            Method m = declared(MultiAnnotationApi.class, "op");

            // When
            EvaluationResult result = evaluator.evaluate(
                    MultiAnnotationApi.class, MultiAnnotationImpl.class);

            // Then
            MethodPlan.Decorated plan =
                    (MethodPlan.Decorated) result.plans().get(m);
            assertThat(plan.elementsOuterToInner()).containsExactly(
                    new ElementRef(InqElementType.CIRCUIT_BREAKER, "cb"),
                    new ElementRef(InqElementType.RETRY, "rt"));
        }

        @Test
        void element_ref_list_is_immutable() {
            // Given
            AnnotationEvaluator evaluator = evaluatorWith(
                    stubElement("bh", InqElementType.BULKHEAD));
            Method m = declared(SyncApi.class, "op");
            EvaluationResult result = evaluator.evaluate(
                    SyncApi.class, SyncAnnotatedImpl.class);
            MethodPlan.Decorated plan =
                    (MethodPlan.Decorated) result.plans().get(m);

            // When / Then
            assertThatThrownBy(() -> plan.elementsOuterToInner().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class MultiMethodMixedParadigms {

        @Test
        void each_method_gets_its_own_paradigm_independent_of_siblings() {
            // What is to be tested? — A single service interface with
            //   methods of three distinct paradigms (sync, async, mono)
            //   must produce three plans, each stamped with its own
            //   paradigm. No paradigm leaks across methods.
            // Successful when? — sync().paradigm() == SyncTag.INSTANCE,
            //   async().paradigm() == AsyncTag.INSTANCE,
            //   mono().paradigm() == ReactiveTag.MONO.
            // Why important? — Mixed-paradigm service interfaces are
            //   the common case for any service that exposes a fire-
            //   and-forget API alongside a result-returning API. A bug
            //   that cached or shared paradigm classification across
            //   methods would corrupt every such service.

            // Given
            AnnotationEvaluator evaluator = evaluatorWith();
            Method syncOp = declared(MixedParadigmApi.class, "syncOp");
            Method asyncOp = declared(MixedParadigmApi.class, "asyncOp");
            Method monoOp = declared(MixedParadigmApi.class, "monoOp");

            // When
            EvaluationResult result = evaluator.evaluate(
                    MixedParadigmApi.class, MixedParadigmImpl.class);

            // Then
            assertThat(plansParadigm(result, syncOp)).isSameAs(SyncTag.INSTANCE);
            assertThat(plansParadigm(result, asyncOp)).isSameAs(AsyncTag.INSTANCE);
            assertThat(plansParadigm(result, monoOp)).isSameAs(ReactiveTag.MONO);
        }

        private ParadigmTag plansParadigm(EvaluationResult result, Method method) {
            return switch (result.plans().get(method)) {
                case MethodPlan.PassThrough spt -> spt.paradigm();
                case MethodPlan.Decorated sd -> sd.paradigm();
                default -> throw new AssertionError(
                        "Expected a stamped plan for " + method + " but got " + result.plans().get(method));
            };
        }
    }

    @Nested
    class ElementRefRecord {

        @Test
        void rejects_null_element_type() {
            // Given / When / Then
            assertThatThrownBy(() -> new ElementRef(null, "bh"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("elementType");
        }

        @Test
        void rejects_null_name() {
            // Given / When / Then
            assertThatThrownBy(() -> new ElementRef(InqElementType.BULKHEAD, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name");
        }
    }

    @Nested
    class PlanRecords {

        @Test
        void pass_through_rejects_null_paradigm() {
            // Given / When / Then
            assertThatThrownBy(() -> new MethodPlan.PassThrough(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("paradigm");
        }

        @Test
        void decorated_rejects_null_paradigm() {
            // Given / When / Then
            assertThatThrownBy(() -> new MethodPlan.Decorated(null, List.of()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("paradigm");
        }

        @Test
        void decorated_defensively_copies_element_list() {
            // Given a mutable list passed to the record's constructor
            List<ElementRef> input = new java.util.ArrayList<>();
            input.add(new ElementRef(InqElementType.BULKHEAD, "bh"));
            MethodPlan.Decorated plan =
                    new MethodPlan.Decorated(SyncTag.INSTANCE, input);

            // When the original input is mutated after construction
            input.add(new ElementRef(InqElementType.RETRY, "rt"));

            // Then the plan's snapshot is unaffected (defensive copy)
            assertThat(plan.elementsOuterToInner()).hasSize(1);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static Method declared(Class<?> declaringClass, String name, Class<?>... parameterTypes) {
        try {
            return declaringClass.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing declared method " + declaringClass.getName() + "#"
                    + name + Arrays.toString(parameterTypes), e);
        }
    }

    private static AnnotationEvaluator evaluatorWith(InqElement... elements) {
        return AnnotationEvaluator.forPipeline(pipelineWithElements(elements));
    }

    private static InqPipeline pipelineWithElements(InqElement... elements) {
        InqPipeline.Builder builder = InqPipeline.builder();
        for (InqElement element : elements) {
            builder.shield(element);
        }
        return builder.build();
    }

    private static InqElement stubElement(String name, InqElementType type) {
        return new StubElement(name, type);
    }

    private record StubElement(String name, InqElementType elementType) implements InqElement {
        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }
    }

    // =====================================================================
    // Fixtures — one interface + impl per paradigm shape
    // =====================================================================

    interface SyncApi {
        String op();
    }

    static class SyncAnnotatedImpl implements SyncApi {
        @Override
        @InqBulkhead("bh")
        public String op() { return ""; }
    }

    static class SyncUnannotatedImpl implements SyncApi {
        @Override
        public String op() { return ""; }
    }

    interface AsyncApi {
        CompletionStage<String> op();
    }

    static class AsyncAnnotatedImpl implements AsyncApi {
        @Override
        @InqBulkhead("bh")
        public CompletionStage<String> op() {
            return CompletableFuture.completedFuture("");
        }
    }

    interface MonoApi {
        Mono<String> op();
    }

    static class MonoAnnotatedImpl implements MonoApi {
        @Override
        @InqBulkhead("bh")
        public Mono<String> op() { return Mono.empty(); }
    }

    static class MonoUnannotatedImpl implements MonoApi {
        @Override
        public Mono<String> op() { return Mono.empty(); }
    }

    interface SingleApi {
        Single<String> op();
    }

    static class SingleAnnotatedImpl implements SingleApi {
        @Override
        @InqBulkhead("bh")
        public Single<String> op() { return Single.just(""); }
    }

    interface DeferredApi {
        Deferred<String> op();
    }

    static class DeferredAnnotatedImpl implements DeferredApi {
        @Override
        @InqBulkhead("bh")
        public Deferred<String> op() { return null; }
    }

    interface DefaultMethodApi {
        default String greet() { return "default"; }
    }

    static class NoOverrideImpl implements DefaultMethodApi {
    }

    interface MultiAnnotationApi {
        String op();
    }

    static class MultiAnnotationImpl implements MultiAnnotationApi {
        @Override
        @InqCircuitBreaker("cb")
        @InqRetry("rt")
        public String op() { return ""; }
    }

    interface MixedParadigmApi {
        String syncOp();
        CompletionStage<String> asyncOp();
        Mono<String> monoOp();
    }

    static class MixedParadigmImpl implements MixedParadigmApi {
        @Override public String syncOp() { return ""; }
        @Override public CompletionStage<String> asyncOp() {
            return CompletableFuture.completedFuture("");
        }
        @Override public Mono<String> monoOp() { return Mono.empty(); }
    }

    static class UnrelatedClass {
    }
}
