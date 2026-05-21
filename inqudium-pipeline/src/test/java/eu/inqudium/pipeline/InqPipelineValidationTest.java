package eu.inqudium.pipeline;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.evaluator.AnnotationEvaluator;
import eu.inqudium.annotation.evaluator.ElementRef;
import eu.inqudium.annotation.evaluator.EvaluationResult;
import eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.paradigm.AsyncTag;
import eu.inqudium.core.paradigm.SyncTag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InqPipeline#validateReferences(EvaluationResult, Class)}.
 *
 * <p>The validation walks every decorated method plan and confirms that
 * each {@code (elementType, paradigmTag, name)} reference resolves to
 * a pipeline element with matching type, name, and paradigm coverage.
 * Pass-through plans carry no references and are always valid.</p>
 *
 * <p>Fixtures use {@link TestElement} so paradigm tags can be configured
 * per test, exercising both the happy path and each failure mode of the
 * triple-key resolution.</p>
 */
class InqPipelineValidationTest {

    @Nested
    class Happy_path {

        @Test
        void should_pass_when_all_references_resolve_to_pipeline_elements_with_matching_paradigm() {
            // Given — pipeline carries a bulkhead "orderBh" with both
            //         SyncTag and AsyncTag in its paradigmTags set.
            //         Evaluation contains a sync method ref'ing it.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(
                            InqElementType.BULKHEAD, "orderBh",
                            Set.of(SyncTag.INSTANCE, AsyncTag.INSTANCE)))
                    .build();
            EvaluationResult evaluation = evaluate(SyncBulkheadApi.class, SyncBulkheadImpl.class);

            // When / Then
            assertThatCode(() -> pipeline.validateReferences(evaluation, SyncBulkheadApi.class))
                    .doesNotThrowAnyException();
        }

        @Test
        void should_pass_for_pass_through_plans_with_no_references() {
            // What is to be tested?
            //   Methods that produce a PassThrough plan (no
            //   annotations) carry no element references. The
            //   validator must skip them and never raise.
            // How will the test case be deemed successful and why?
            //   Validation completes without exception even though
            //   the pipeline element is unrelated to the unannotated
            //   service method.
            // Why is it important to test this test case?
            //   Pass-through methods are the common case; a validator
            //   that mistakenly required pipeline coverage for them
            //   would block every service interface mixing annotated
            //   and unannotated methods.

            // Given — pipeline has an unrelated element; the
            // unannotated method should still pass validation.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "unused"))
                    .build();
            EvaluationResult evaluation = evaluate(UnannotatedApi.class, UnannotatedImpl.class);

            // When / Then
            assertThatCode(() -> pipeline.validateReferences(evaluation, UnannotatedApi.class))
                    .doesNotThrowAnyException();
        }

        @Test
        void should_pass_for_an_empty_evaluation_result_against_any_pipeline() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "unused"))
                    .build();
            EvaluationResult emptyEvaluation = new EvaluationResult(Map.of());

            // When / Then
            assertThatCode(() -> pipeline.validateReferences(emptyEvaluation, SyncBulkheadApi.class))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Reference_resolution_failures {

        @Test
        void should_throw_when_element_name_is_missing_from_pipeline() {
            // Given — pipeline has bulkhead "orderBh"; annotation
            //         references "missingBh".
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "orderBh"))
                    .build();
            EvaluationResult evaluation = evaluate(
                    MissingNameApi.class, MissingNameImpl.class);

            // When / Then
            assertThatThrownBy(
                    () -> pipeline.validateReferences(evaluation, MissingNameApi.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining("@InqBulkhead")
                    .hasMessageContaining("MissingNameApi")
                    .hasMessageContaining("missingBh")
                    .hasMessageContaining("SyncTag");
        }

        @Test
        void should_throw_when_element_type_does_not_match_the_reference() {
            // What is to be tested?
            //   A pipeline element with the right name but the wrong
            //   element type must not satisfy the reference. The
            //   triple (BULKHEAD, "orderOp") cannot be served by a
            //   RETRY element of the same name.
            // How will the test case be deemed successful and why?
            //   InqAnnotationConfigurationException is raised with
            //   the BULKHEAD type in the failure message — pinning
            //   that the validator looks past the name match.
            // Why is it important to test this test case?
            //   Name collisions across element types are a realistic
            //   misconfiguration; silently binding a retry to a
            //   bulkhead reference would corrupt the call path.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.RETRY, "orderOp"))
                    .build();
            EvaluationResult evaluation = evaluate(BulkheadNamedOrderOpApi.class,
                    BulkheadNamedOrderOpImpl.class);

            // When / Then
            assertThatThrownBy(
                    () -> pipeline.validateReferences(evaluation, BulkheadNamedOrderOpApi.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining("BULKHEAD")
                    .hasMessageContaining("orderOp");
        }

        @Test
        void should_throw_when_paradigm_tag_is_not_in_the_elements_paradigm_set() {
            // What is to be tested?
            //   A pipeline element matching elementType and name but
            //   whose paradigmTags() does not include the method's
            //   paradigm must not satisfy the reference. A SyncTag-only
            //   bulkhead cannot wrap an AsyncTag method.
            // How will the test case be deemed successful and why?
            //   InqAnnotationConfigurationException is raised with
            //   AsyncTag in the message; the SyncTag-only pipeline
            //   element is correctly rejected.
            // Why is it important to test this test case?
            //   This is the new triple-key invariant from ADR-040 §3
            //   that B.5.5 introduces: paradigm coverage is part of
            //   the reference identity, not just a runtime check.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(
                            InqElementType.BULKHEAD, "orderBh",
                            Set.of(SyncTag.INSTANCE)))
                    .build();
            EvaluationResult evaluation = evaluate(
                    AsyncBulkheadApi.class, AsyncBulkheadImpl.class);

            // When / Then
            assertThatThrownBy(
                    () -> pipeline.validateReferences(evaluation, AsyncBulkheadApi.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining("AsyncTag")
                    .hasMessageContaining("orderBh");
        }

        @Test
        void should_identify_the_correct_method_in_the_error_message() {
            // What is to be tested?
            //   On a multi-method service interface where only one
            //   method has an unresolved reference, the error message
            //   must name that specific method.
            // How will the test case be deemed successful and why?
            //   The exception message contains the failing method's
            //   simple name ("findById") and not the names of the
            //   other methods.
            // Why is it important to test this test case?
            //   Diagnostic precision: without per-method
            //   identification, a developer would have to grep the
            //   whole service interface to locate the broken
            //   annotation.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "orderBh"))
                    .build();
            EvaluationResult evaluation = evaluate(
                    MixedMethodsApi.class, MixedMethodsImpl.class);

            // When / Then
            assertThatThrownBy(
                    () -> pipeline.validateReferences(evaluation, MixedMethodsApi.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining("findById")
                    .hasMessageContaining("missing");
        }

        @Test
        void should_format_error_message_per_format_alpha() {
            // What is to be tested?
            //   The exact format of the error message follows option α
            //   (single-line prosaic): "@<Annotation> on <FQN>#<method>
            //   names '<name>' for paradigm <Tag> but pipeline has no
            //   matching (<TYPE>, <Tag>, '<name>') element".
            // How will the test case be deemed successful and why?
            //   The exception message is exactly this format for a
            //   known input. Asserting on the full message pins the
            //   contract — incidental wording changes will surface
            //   here for explicit review.
            // Why is it important to test this test case?
            //   The error message is the user-facing diagnostic
            //   contract; downstream tooling (IDE inspections,
            //   compile-time validators) may parse it.

            // Given — pipeline contains an unrelated element so the
            // builder accepts it; the missing reference still fails
            // validation with the expected α-format message.
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.RETRY, "unrelated"))
                    .build();
            EvaluationResult evaluation = evaluate(
                    MissingNameApi.class, MissingNameImpl.class);

            // When / Then
            assertThatThrownBy(
                    () -> pipeline.validateReferences(evaluation, MissingNameApi.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessage(
                            "@InqBulkhead on "
                                    + MissingNameApi.class.getName()
                                    + "#findById names 'missingBh' for paradigm SyncTag"
                                    + " but pipeline has no matching"
                                    + " (BULKHEAD, SyncTag, 'missingBh') element");
        }
    }

    @Nested
    class Multi_element_references {

        @Test
        void should_validate_every_element_in_a_decorated_method_plan() {
            // What is to be tested?
            //   A method annotated with @InqCircuitBreaker AND
            //   @InqBulkhead produces a Decorated plan with two
            //   references. The validator must check both; a
            //   pipeline missing either should fail.
            // How will the test case be deemed successful and why?
            //   With a pipeline holding only the bulkhead, validation
            //   fails on the missing circuit-breaker reference. The
            //   error message identifies the CIRCUIT_BREAKER type.
            // Why is it important to test this test case?
            //   The single-reference loop must not short-circuit
            //   after the first successful match; every reference
            //   needs verification.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(InqElementType.BULKHEAD, "orderBh"))
                    .build();
            EvaluationResult evaluation = evaluate(
                    TwoElementApi.class, TwoElementImpl.class);

            // When / Then
            assertThatThrownBy(
                    () -> pipeline.validateReferences(evaluation, TwoElementApi.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining("CIRCUIT_BREAKER")
                    .hasMessageContaining("orderCb");
        }

        @Test
        void should_resolve_decorated_plan_with_distinct_paradigm_tags_per_method() {
            // What is to be tested?
            //   A pipeline element with paradigmTags = {SyncTag,
            //   AsyncTag} backs both a sync-method reference and an
            //   async-method reference on the same name. Validation
            //   accepts both methods.
            // How will the test case be deemed successful and why?
            //   No exception is thrown for an evaluation containing
            //   a SyncTag-tagged method ref and an AsyncTag-tagged
            //   method ref to the same dual-paradigm element.
            // Why is it important to test this test case?
            //   This is the foundational ADR-040 §3 Invariant 2
            //   property: a single element instance can serve
            //   multiple paradigms within the same pipeline.

            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new TestElement(
                            InqElementType.BULKHEAD, "orderBh",
                            Set.of(SyncTag.INSTANCE, AsyncTag.INSTANCE)))
                    .build();
            EvaluationResult evaluation = evaluate(
                    DualParadigmApi.class, DualParadigmImpl.class);

            // When / Then
            assertThatCode(() -> pipeline.validateReferences(evaluation, DualParadigmApi.class))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Evaluation_result_shape_sanity {

        @Test
        void plans_map_should_carry_the_decorated_plan_we_construct() {
            // Sanity that the test fixtures actually produce Decorated
            // plans; ensures the validation tests are not vacuously
            // passing because every plan is PassThrough.
            EvaluationResult evaluation = evaluate(
                    SyncBulkheadApi.class, SyncBulkheadImpl.class);
            assertThat(evaluation.plans().values())
                    .anyMatch(plan -> plan instanceof MethodPlan.Decorated d
                            && d.elementsOuterToInner().contains(
                                    new ElementRef(InqElementType.BULKHEAD, "orderBh"))
                            && d.paradigm() == SyncTag.INSTANCE);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static <T> EvaluationResult evaluate(
            Class<T> serviceInterface, Class<? extends T> implementationClass) {
        return AnnotationEvaluator.instance().evaluate(serviceInterface, implementationClass);
    }

    // =====================================================================
    // Fixtures — minimal service interfaces and impls
    // =====================================================================

    interface SyncBulkheadApi {
        String findById(String id);
    }

    static final class SyncBulkheadImpl implements SyncBulkheadApi {
        @Override
        @InqBulkhead("orderBh")
        public String findById(String id) {
            return id;
        }
    }

    interface UnannotatedApi {
        String findById(String id);
    }

    static final class UnannotatedImpl implements UnannotatedApi {
        @Override
        public String findById(String id) {
            return id;
        }
    }

    interface MissingNameApi {
        String findById(String id);
    }

    static final class MissingNameImpl implements MissingNameApi {
        @Override
        @InqBulkhead("missingBh")
        public String findById(String id) {
            return id;
        }
    }

    interface BulkheadNamedOrderOpApi {
        String perform(String input);
    }

    static final class BulkheadNamedOrderOpImpl implements BulkheadNamedOrderOpApi {
        @Override
        @InqBulkhead("orderOp")
        public String perform(String input) {
            return input;
        }
    }

    interface AsyncBulkheadApi {
        CompletionStage<String> findById(String id);
    }

    static final class AsyncBulkheadImpl implements AsyncBulkheadApi {
        @Override
        @InqBulkhead("orderBh")
        public CompletionStage<String> findById(String id) {
            return CompletableFuture.completedFuture(id);
        }
    }

    interface MixedMethodsApi {
        String findById(String id);
        String unrelated();
    }

    static final class MixedMethodsImpl implements MixedMethodsApi {
        @Override
        @InqBulkhead("missing")
        public String findById(String id) {
            return id;
        }

        @Override
        public String unrelated() {
            return "ok";
        }
    }

    interface TwoElementApi {
        String perform(String input);
    }

    static final class TwoElementImpl implements TwoElementApi {
        @Override
        @InqCircuitBreaker("orderCb")
        @InqBulkhead("orderBh")
        public String perform(String input) {
            return input;
        }
    }

    interface DualParadigmApi {
        String syncCall(String input);
        CompletionStage<String> asyncCall(String input);
    }

    static final class DualParadigmImpl implements DualParadigmApi {
        @Override
        @InqBulkhead("orderBh")
        public String syncCall(String input) {
            return input;
        }

        @Override
        @InqBulkhead("orderBh")
        public CompletionStage<String> asyncCall(String input) {
            return CompletableFuture.completedFuture(input);
        }
    }
}
