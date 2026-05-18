package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.ElementRef;
import eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.CoroutinesTag;
import eu.inqudium.core.element.paradigm.ReactiveTag;
import eu.inqudium.core.element.paradigm.RxJava3Tag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@code createStampedEntry(...)}, the paradigm-aware
 * factory entry-point introduced by Q.4 of
 * {@code REFACTORING_PARADIGM_TAGGING.md}.
 *
 * <p>Coverage targets:</p>
 * <ul>
 *   <li>Pass-through dispatch for every top-level paradigm
 *       family — sync, async, reactive, rxjava3, coroutines —
 *       confirming the {@link MethodPlan.StampedPassThrough} path
 *       never fails on an unsupported paradigm.</li>
 *   <li>Sync and async decorated dispatch produce the right
 *       cached chain entry and propagate the layer descriptions.</li>
 *   <li>Fail-fast for each of the six paradigm sub-shapes lacking a
 *       resilience-element implementation: Mono, Flux, Single,
 *       Deferred, Job, Flow. Each annotated {@link MethodPlan.StampedDecorated}
 *       on those paradigms must throw
 *       {@link InqAnnotationConfigurationException} at construction
 *       time with an actionable error message.</li>
 *   <li>Legacy plan permits (PassThrough/Decorated) are rejected by
 *       the stamped factory entry-point with a pointer at the
 *       legacy {@code createEntry(...)} sibling.</li>
 * </ul>
 *
 * <p>The factory dispatches on {@code plan.paradigm()} (and only
 * for {@link AsyncTag} additionally on the runtime presence of
 * {@code inqudium-imperative}). It does not inspect the method's
 * actual return type — that's the evaluator's responsibility. The
 * fail-fast and pass-through tests therefore use plain {@code String}
 * / {@code CompletableFuture} methods and pin the dispatch path via
 * the {@link MethodPlan} construction alone.</p>
 */
class MethodDispatchEntryFactoryStampedTest {

    // =====================================================================
    // Fixtures
    // =====================================================================

    public interface TestService {

        String simple();

        String decorated();

        CompletableFuture<String> asyncMethod();

        CompletableFuture<String> asyncDecorated();
    }

    public static class TestServiceImpl implements TestService {
        @Override public String simple() { return "simple"; }
        @Override public String decorated() { return "decorated"; }
        @Override public CompletableFuture<String> asyncMethod() {
            return CompletableFuture.completedFuture("async");
        }
        @Override public CompletableFuture<String> asyncDecorated() {
            return CompletableFuture.completedFuture("asyncDecorated");
        }
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return TestService.class.getDeclaredMethod(name, params);
    }

    private static InqPipeline syncPipelineWithBulkhead() {
        return InqPipeline.builder()
                .shield(new FakeDecorator("bh", InqElementType.BULKHEAD))
                .build();
    }

    private static InqPipeline asyncPipelineWithBulkhead() {
        return InqPipeline.builder()
                .shield(new FakeAsyncDecorator("bh", InqElementType.BULKHEAD))
                .build();
    }

    // =====================================================================
    // Pass-through paths — every paradigm family supported
    // =====================================================================

    @Nested
    class PassThroughDispatch {

        @Test
        void should_produce_pass_through_entry_for_sync_method() throws Throwable {
            // Given
            Method m = method("simple");
            InqPipeline pipeline = syncPipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.StampedPassThrough(SyncTag.INSTANCE),
                    pipeline, target, TestServiceImpl.class);

            // Then — pass-through entries carry no layer descriptions
            assertThat(entry.layerDescriptions()).isEmpty();
            assertThat(entry.dispatch(target, null, new Object[0]))
                    .isEqualTo("simple");
        }

        @Test
        void should_produce_pass_through_entry_for_async_method() throws Throwable {
            // What is to be tested? — A CompletionStage-returning
            //   method classified as AsyncTag with no resilience
            //   annotation must dispatch as pass-through, never
            //   require inqudium-imperative for construction. The
            //   classifier path through async pass-through must not
            //   invoke the DetectionAsync gate.
            // Successful when? — dispatch returns the async result
            //   and the entry carries no layer descriptions.
            // Why important? — Confirms AsyncTag pass-through is a
            //   first-class path, parallel to SyncTag pass-through —
            //   not lumped with the async-decorated fail-fast logic.

            // Given
            Method m = method("asyncMethod");
            InqPipeline pipeline = syncPipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.StampedPassThrough(AsyncTag.INSTANCE),
                    pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.layerDescriptions()).isEmpty();
            Object result = entry.dispatch(target, null, new Object[0]);
            assertThat(result).isInstanceOf(CompletionStage.class);
        }

        @Test
        void should_produce_pass_through_entry_for_reactive_mono_paradigm() throws Throwable {
            // What is to be tested? — A method tagged with
            //   ReactiveTag.MONO and no resilience annotations
            //   (StampedPassThrough) must dispatch normally — the
            //   library does not need to implement reactive
            //   resilience elements to route a reactive
            //   pass-through call.
            // Successful when? — dispatch invokes the real target
            //   without throwing.
            // Why important? — Without this, every reactive method
            //   on a sync-only service would fail the proxy build,
            //   blocking mixed-paradigm interfaces from sync
            //   services that occasionally return a Mono.

            // Given
            Method m = method("simple"); // any method; tag drives dispatch
            InqPipeline pipeline = syncPipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.StampedPassThrough(ReactiveTag.MONO),
                    pipeline, target, TestServiceImpl.class);

            // Then
            assertThat(entry.layerDescriptions()).isEmpty();
            assertThat(entry.dispatch(target, null, new Object[0]))
                    .isEqualTo("simple");
        }

        @Test
        void should_produce_pass_through_entry_for_rxjava3_single_paradigm() throws Throwable {
            // Given / When / Then
            Method m = method("simple");
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.StampedPassThrough(RxJava3Tag.SINGLE),
                    syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class);
            assertThat(entry.layerDescriptions()).isEmpty();
            assertThat(entry.dispatch(new TestServiceImpl(), null, new Object[0]))
                    .isEqualTo("simple");
        }

        @Test
        void should_produce_pass_through_entry_for_coroutines_deferred_paradigm() throws Throwable {
            // Given / When / Then
            Method m = method("simple");
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.StampedPassThrough(CoroutinesTag.DEFERRED),
                    syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class);
            assertThat(entry.layerDescriptions()).isEmpty();
            assertThat(entry.dispatch(new TestServiceImpl(), null, new Object[0]))
                    .isEqualTo("simple");
        }
    }

    // =====================================================================
    // Decorated paths — sync and async
    // =====================================================================

    @Nested
    class SyncDecoratedDispatch {

        @Test
        void should_produce_sync_cache_entry_for_annotated_sync_method() {
            // Given
            Method m;
            try {
                m = method("decorated");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            InqPipeline pipeline = syncPipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();
            MethodPlan plan = new MethodPlan.StampedDecorated(
                    SyncTag.INSTANCE,
                    List.of(new ElementRef(InqElementType.BULKHEAD, "bh")));

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, plan, pipeline, target, TestServiceImpl.class);

            // Then — sync cache entries carry their layer descriptions
            assertThat(entry.layerDescriptions()).containsExactly("bh");
        }
    }

    @Nested
    class AsyncDecoratedDispatch {

        @Test
        void should_produce_async_cache_entry_for_annotated_completion_stage_method() {
            // Given
            Method m;
            try {
                m = method("asyncDecorated");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            InqPipeline pipeline = asyncPipelineWithBulkhead();
            TestServiceImpl target = new TestServiceImpl();
            MethodPlan plan = new MethodPlan.StampedDecorated(
                    AsyncTag.INSTANCE,
                    List.of(new ElementRef(InqElementType.BULKHEAD, "bh")));

            // When
            MethodDispatchEntry entry = MethodDispatchEntryFactory.createStampedEntry(
                    m, plan, pipeline, target, TestServiceImpl.class);

            // Then — async cache entries carry their layer descriptions
            assertThat(entry.layerDescriptions()).containsExactly("bh");
        }
    }

    // =====================================================================
    // Fail-fast: unsupported-paradigm × annotated
    // =====================================================================

    @Nested
    class FailFastUnsupportedParadigms {

        @Test
        void should_fail_fast_for_annotated_mono_paradigm() throws NoSuchMethodException {
            assertFailFast(ReactiveTag.MONO, "ReactiveTag", "Mono/Flux");
        }

        @Test
        void should_fail_fast_for_annotated_flux_paradigm() throws NoSuchMethodException {
            assertFailFast(ReactiveTag.FLUX, "ReactiveTag", "Mono/Flux");
        }

        @Test
        void should_fail_fast_for_annotated_single_paradigm() throws NoSuchMethodException {
            assertFailFast(RxJava3Tag.SINGLE, "RxJava3Tag", "RxJava 3");
        }

        @Test
        void should_fail_fast_for_annotated_maybe_paradigm() throws NoSuchMethodException {
            assertFailFast(RxJava3Tag.MAYBE, "RxJava3Tag", "RxJava 3");
        }

        @Test
        void should_fail_fast_for_annotated_completable_paradigm() throws NoSuchMethodException {
            assertFailFast(RxJava3Tag.COMPLETABLE, "RxJava3Tag", "RxJava 3");
        }

        @Test
        void should_fail_fast_for_annotated_flowable_paradigm() throws NoSuchMethodException {
            assertFailFast(RxJava3Tag.FLOWABLE, "RxJava3Tag", "RxJava 3");
        }

        @Test
        void should_fail_fast_for_annotated_observable_paradigm() throws NoSuchMethodException {
            assertFailFast(RxJava3Tag.OBSERVABLE, "RxJava3Tag", "RxJava 3");
        }

        @Test
        void should_fail_fast_for_annotated_suspend_paradigm() throws NoSuchMethodException {
            assertFailFast(CoroutinesTag.SUSPEND, "CoroutinesTag", "Kotlin coroutines");
        }

        @Test
        void should_fail_fast_for_annotated_deferred_paradigm() throws NoSuchMethodException {
            assertFailFast(CoroutinesTag.DEFERRED, "CoroutinesTag", "Kotlin coroutines");
        }

        @Test
        void should_fail_fast_for_annotated_job_paradigm() throws NoSuchMethodException {
            assertFailFast(CoroutinesTag.JOB, "CoroutinesTag", "Kotlin coroutines");
        }

        @Test
        void should_fail_fast_for_annotated_flow_paradigm() throws NoSuchMethodException {
            assertFailFast(CoroutinesTag.FLOW, "CoroutinesTag", "Kotlin coroutines");
        }

        @Test
        void fail_fast_message_names_the_method_the_paradigm_and_the_annotated_elements()
                throws NoSuchMethodException {
            // What is to be tested? — The error message produced
            //   when an unsupported-paradigm method carries a
            //   resilience annotation must be actionable: it should
            //   tell the user which method, which paradigm, and
            //   which annotated element triggered the failure, and
            //   it should suggest concrete fixes.
            // Successful when? — the message contains the method
            //   name, the simple class name (TestService), the
            //   paradigm family ("ReactiveTag"), the friendly
            //   description ("Mono/Flux"), the element type and
            //   name ("BULKHEAD 'bh'"), and one of the suggested
            //   fixes ("remove the resilience annotation").
            // Why important? — Without a clear message the user
            //   only sees a stack trace and has to grep through
            //   the codebase to find what went wrong. The proxy is
            //   often integrated by application developers
            //   unfamiliar with the library internals.

            Method m = method("simple");
            MethodPlan plan = new MethodPlan.StampedDecorated(
                    ReactiveTag.MONO,
                    List.of(new ElementRef(InqElementType.BULKHEAD, "bh")));

            assertThatThrownBy(() -> MethodDispatchEntryFactory.createStampedEntry(
                    m, plan, syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining("TestService#simple")
                    .hasMessageContaining("ReactiveTag")
                    .hasMessageContaining("Mono/Flux")
                    .hasMessageContaining("BULKHEAD")
                    .hasMessageContaining("'bh'")
                    .hasMessageContaining("remove the resilience annotation");
        }

        private void assertFailFast(eu.inqudium.core.element.paradigm.ParadigmTag tag,
                                    String tagFamilyInMessage,
                                    String descriptionInMessage) throws NoSuchMethodException {
            Method m = method("simple");
            MethodPlan plan = new MethodPlan.StampedDecorated(
                    tag,
                    List.of(new ElementRef(InqElementType.BULKHEAD, "bh")));

            assertThatThrownBy(() -> MethodDispatchEntryFactory.createStampedEntry(
                    m, plan, syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class))
                    .isInstanceOf(InqAnnotationConfigurationException.class)
                    .hasMessageContaining(tagFamilyInMessage)
                    .hasMessageContaining(descriptionInMessage);
        }
    }

    // =====================================================================
    // Legacy plan rejection
    // =====================================================================

    @Nested
    class LegacyPlanRejection {

        @Test
        void should_reject_legacy_pass_through_plan() throws NoSuchMethodException {
            // Given
            Method m = method("simple");

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.PassThrough(),
                    syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Legacy")
                    .hasMessageContaining("createStampedEntry");
        }

        @Test
        void should_reject_legacy_decorated_plan() throws NoSuchMethodException {
            // Given
            Method m = method("decorated");

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createStampedEntry(
                    m, new MethodPlan.Decorated(List.of("bh")),
                    syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Legacy")
                    .hasMessageContaining("createStampedEntry");
        }
    }

    @Nested
    class LegacyFactoryRejectsStampedPlans {

        @Test
        void should_reject_stamped_pass_through_in_legacy_create_entry() throws NoSuchMethodException {
            // Given
            Method m = method("simple");

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createEntry(
                    m, new MethodPlan.StampedPassThrough(SyncTag.INSTANCE),
                    syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stamped")
                    .hasMessageContaining("createStampedEntry");
        }

        @Test
        void should_reject_stamped_decorated_in_legacy_create_entry() throws NoSuchMethodException {
            // Given
            Method m = method("decorated");

            // When / Then
            assertThatThrownBy(() -> MethodDispatchEntryFactory.createEntry(
                    m, new MethodPlan.StampedDecorated(
                            SyncTag.INSTANCE,
                            List.of(new ElementRef(InqElementType.BULKHEAD, "bh"))),
                    syncPipelineWithBulkhead(),
                    new TestServiceImpl(), TestServiceImpl.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stamped")
                    .hasMessageContaining("createStampedEntry");
        }
    }
}
