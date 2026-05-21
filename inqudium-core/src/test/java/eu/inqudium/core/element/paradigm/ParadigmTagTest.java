package eu.inqudium.core.element.paradigm;

import eu.inqudium.core.paradigm.AsyncTag;
import eu.inqudium.core.paradigm.AsyncTagDefault;
import eu.inqudium.core.paradigm.CoroutinesDeferredTag;
import eu.inqudium.core.paradigm.CoroutinesDeferredTagDefault;
import eu.inqudium.core.paradigm.CoroutinesFlowTag;
import eu.inqudium.core.paradigm.CoroutinesFlowTagDefault;
import eu.inqudium.core.paradigm.CoroutinesJobTag;
import eu.inqudium.core.paradigm.CoroutinesJobTagDefault;
import eu.inqudium.core.paradigm.CoroutinesSuspendTag;
import eu.inqudium.core.paradigm.CoroutinesSuspendTagDefault;
import eu.inqudium.core.paradigm.CoroutinesTag;
import eu.inqudium.core.paradigm.ParadigmTag;
import eu.inqudium.core.paradigm.ReactiveFluxTag;
import eu.inqudium.core.paradigm.ReactiveFluxTagDefault;
import eu.inqudium.core.paradigm.ReactiveMonoTag;
import eu.inqudium.core.paradigm.ReactiveMonoTagDefault;
import eu.inqudium.core.paradigm.ReactiveTag;
import eu.inqudium.core.paradigm.RxJava3CompletableTag;
import eu.inqudium.core.paradigm.RxJava3CompletableTagDefault;
import eu.inqudium.core.paradigm.RxJava3FlowableTag;
import eu.inqudium.core.paradigm.RxJava3FlowableTagDefault;
import eu.inqudium.core.paradigm.RxJava3MaybeTag;
import eu.inqudium.core.paradigm.RxJava3MaybeTagDefault;
import eu.inqudium.core.paradigm.RxJava3ObservableTag;
import eu.inqudium.core.paradigm.RxJava3ObservableTagDefault;
import eu.inqudium.core.paradigm.RxJava3SingleTag;
import eu.inqudium.core.paradigm.RxJava3SingleTagDefault;
import eu.inqudium.core.paradigm.RxJava3Tag;
import eu.inqudium.core.paradigm.SyncTag;
import eu.inqudium.core.paradigm.SyncTagDefault;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ParadigmTag} sealed hierarchy introduced by ADR-046 §2.
 *
 * <p>The tests cover singleton identity, the exact shape of the sealed
 * {@code permits} clauses at every level of the hierarchy, exhaustive
 * {@code switch} behaviour over both the top-level interface and the
 * sub-family interfaces, and the no-public-constructors invariant that
 * keeps every concrete tag a singleton.</p>
 */
class ParadigmTagTest {

    @Nested
    class InstanceConstants {

        @Test
        void sync_tag_instance_is_non_null() {
            // Given / When / Then
            assertThat(SyncTag.INSTANCE).isNotNull();
        }

        @Test
        void async_tag_instance_is_non_null() {
            // Given / When / Then
            assertThat(AsyncTag.INSTANCE).isNotNull();
        }

        @Test
        void reactive_tag_constants_are_non_null() {
            // Given / When / Then
            assertThat(ReactiveTag.MONO).isNotNull();
            assertThat(ReactiveTag.FLUX).isNotNull();
        }

        @Test
        void rxjava3_tag_constants_are_non_null() {
            // Given / When / Then
            assertThat(RxJava3Tag.SINGLE).isNotNull();
            assertThat(RxJava3Tag.MAYBE).isNotNull();
            assertThat(RxJava3Tag.COMPLETABLE).isNotNull();
            assertThat(RxJava3Tag.FLOWABLE).isNotNull();
            assertThat(RxJava3Tag.OBSERVABLE).isNotNull();
        }

        @Test
        void coroutines_tag_constants_are_non_null() {
            // Given / When / Then
            assertThat(CoroutinesTag.SUSPEND).isNotNull();
            assertThat(CoroutinesTag.DEFERRED).isNotNull();
            assertThat(CoroutinesTag.JOB).isNotNull();
            assertThat(CoroutinesTag.FLOW).isNotNull();
        }

        @Test
        void sync_tag_constant_returns_the_same_singleton_on_every_access() {
            // Given / When
            SyncTag a = SyncTag.INSTANCE;
            SyncTag b = SyncTag.INSTANCE;
            // Then
            assertThat(a).isSameAs(b);
        }

        @Test
        void async_tag_constant_returns_the_same_singleton_on_every_access() {
            // Given / When
            AsyncTag a = AsyncTag.INSTANCE;
            AsyncTag b = AsyncTag.INSTANCE;
            // Then
            assertThat(a).isSameAs(b);
        }

        @Test
        void reactive_mono_tag_constant_returns_the_same_singleton_on_every_access() {
            // Given / When
            ReactiveMonoTag a = ReactiveTag.MONO;
            ReactiveMonoTag b = ReactiveTag.MONO;
            // Then
            assertThat(a).isSameAs(b);
        }

        @Test
        void rxjava3_single_tag_constant_returns_the_same_singleton_on_every_access() {
            // Given / When
            RxJava3SingleTag a = RxJava3Tag.SINGLE;
            RxJava3SingleTag b = RxJava3Tag.SINGLE;
            // Then
            assertThat(a).isSameAs(b);
        }

        @Test
        void coroutines_suspend_tag_constant_returns_the_same_singleton_on_every_access() {
            // Given / When
            CoroutinesSuspendTag a = CoroutinesTag.SUSPEND;
            CoroutinesSuspendTag b = CoroutinesTag.SUSPEND;
            // Then
            assertThat(a).isSameAs(b);
        }
    }

    @Nested
    class SealedHierarchy {

        @Test
        void paradigm_tag_permits_exactly_the_five_top_level_families() {
            // Given
            Class<?>[] permitted = ParadigmTag.class.getPermittedSubclasses();
            // When
            Set<Class<?>> permittedSet = Set.of(permitted);
            // Then — the five canonical families per ADR-046
            assertThat(permittedSet).containsExactlyInAnyOrder(
                    SyncTag.class, AsyncTag.class,
                    ReactiveTag.class, RxJava3Tag.class, CoroutinesTag.class);
        }

        @Test
        void reactive_tag_permits_exactly_mono_and_flux() {
            // Given / When
            Class<?>[] permitted = ReactiveTag.class.getPermittedSubclasses();
            // Then
            assertThat(Set.of(permitted)).containsExactlyInAnyOrder(
                    ReactiveMonoTag.class, ReactiveFluxTag.class);
        }

        @Test
        void rxjava3_tag_permits_exactly_five_sub_shapes() {
            // Given / When
            Class<?>[] permitted = RxJava3Tag.class.getPermittedSubclasses();
            // Then
            assertThat(Set.of(permitted)).containsExactlyInAnyOrder(
                    RxJava3SingleTag.class, RxJava3MaybeTag.class,
                    RxJava3CompletableTag.class, RxJava3FlowableTag.class,
                    RxJava3ObservableTag.class);
        }

        @Test
        void coroutines_tag_permits_exactly_four_sub_shapes() {
            // Given / When
            Class<?>[] permitted = CoroutinesTag.class.getPermittedSubclasses();
            // Then
            assertThat(Set.of(permitted)).containsExactlyInAnyOrder(
                    CoroutinesSuspendTag.class, CoroutinesDeferredTag.class,
                    CoroutinesJobTag.class, CoroutinesFlowTag.class);
        }

        @Test
        void every_top_level_tag_is_a_sealed_interface() {
            // Given the top-level ParadigmTag permits
            // When inspecting each permit's modifiers
            // Then all five family-level interfaces are sealed. Post-Q.7.5
            // the imperative leaf tags (SyncTag/AsyncTag) are also sealed
            // interfaces (was: final classes), bringing the entire
            // hierarchy into structural consistency.
            assertThat(SyncTag.class.isSealed()).isTrue();
            assertThat(AsyncTag.class.isSealed()).isTrue();
            assertThat(ReactiveTag.class.isSealed()).isTrue();
            assertThat(RxJava3Tag.class.isSealed()).isTrue();
            assertThat(CoroutinesTag.class.isSealed()).isTrue();
        }
    }

    @Nested
    class ExhaustiveSwitch {

        @Test
        void switch_over_paradigm_tag_handles_every_sub_family() {
            // Given a tag from each top-level family
            List<ParadigmTag> tags = List.of(
                    SyncTag.INSTANCE,
                    AsyncTag.INSTANCE,
                    ReactiveTag.MONO,
                    RxJava3Tag.SINGLE,
                    CoroutinesTag.SUSPEND);

            // When described via exhaustive switch
            // Then every tag produces a non-blank description (no
            // pattern-match-fall-through exception, no null result)
            for (ParadigmTag tag : tags) {
                assertThat(describe(tag)).isNotBlank();
            }
        }

        @Test
        void exhaustive_switch_returns_the_expected_label_for_each_tag() {
            // Given / When / Then
            assertThat(describe(SyncTag.INSTANCE)).isEqualTo("synchronous");
            assertThat(describe(AsyncTag.INSTANCE)).isEqualTo("asynchronous");
            assertThat(describe(ReactiveTag.MONO)).isEqualTo("reactive Mono");
            assertThat(describe(ReactiveTag.FLUX)).isEqualTo("reactive Flux");
            assertThat(describe(RxJava3Tag.SINGLE)).isEqualTo("rxjava3 Single");
            assertThat(describe(RxJava3Tag.MAYBE)).isEqualTo("rxjava3 Maybe");
            assertThat(describe(RxJava3Tag.COMPLETABLE)).isEqualTo("rxjava3 Completable");
            assertThat(describe(RxJava3Tag.FLOWABLE)).isEqualTo("rxjava3 Flowable");
            assertThat(describe(RxJava3Tag.OBSERVABLE)).isEqualTo("rxjava3 Observable");
            assertThat(describe(CoroutinesTag.SUSPEND)).isEqualTo("coroutine suspend fun");
            assertThat(describe(CoroutinesTag.DEFERRED)).isEqualTo("coroutine Deferred");
            assertThat(describe(CoroutinesTag.JOB)).isEqualTo("coroutine Job");
            assertThat(describe(CoroutinesTag.FLOW)).isEqualTo("coroutine Flow");
        }

        @Test
        void nested_switch_correctly_discriminates_within_reactive_family() {
            // Given
            ReactiveTag tag = ReactiveTag.MONO;
            // When
            String result = switch (tag) {
                case ReactiveMonoTag m -> "mono";
                case ReactiveFluxTag f -> "flux";
            };
            // Then
            assertThat(result).isEqualTo("mono");
        }

        @Test
        void nested_switch_correctly_discriminates_within_rxjava3_family() {
            // Given
            RxJava3Tag tag = RxJava3Tag.COMPLETABLE;
            // When
            String result = switch (tag) {
                case RxJava3SingleTag s -> "single";
                case RxJava3MaybeTag m -> "maybe";
                case RxJava3CompletableTag c -> "completable";
                case RxJava3FlowableTag f -> "flowable";
                case RxJava3ObservableTag o -> "observable";
            };
            // Then
            assertThat(result).isEqualTo("completable");
        }

        @Test
        void nested_switch_correctly_discriminates_within_coroutines_family() {
            // Given
            CoroutinesTag tag = CoroutinesTag.FLOW;
            // When
            String result = switch (tag) {
                case CoroutinesSuspendTag s -> "suspend";
                case CoroutinesDeferredTag d -> "deferred";
                case CoroutinesJobTag j -> "job";
                case CoroutinesFlowTag f -> "flow";
            };
            // Then
            assertThat(result).isEqualTo("flow");
        }

        private String describe(ParadigmTag tag) {
            return switch (tag) {
                case SyncTag s -> "synchronous";
                case AsyncTag a -> "asynchronous";
                case ReactiveTag r -> switch (r) {
                    case ReactiveMonoTag m -> "reactive Mono";
                    case ReactiveFluxTag f -> "reactive Flux";
                };
                case RxJava3Tag rx -> switch (rx) {
                    case RxJava3SingleTag s -> "rxjava3 Single";
                    case RxJava3MaybeTag m -> "rxjava3 Maybe";
                    case RxJava3CompletableTag c -> "rxjava3 Completable";
                    case RxJava3FlowableTag f -> "rxjava3 Flowable";
                    case RxJava3ObservableTag o -> "rxjava3 Observable";
                };
                case CoroutinesTag c -> switch (c) {
                    case CoroutinesSuspendTag s -> "coroutine suspend fun";
                    case CoroutinesDeferredTag d -> "coroutine Deferred";
                    case CoroutinesJobTag j -> "coroutine Job";
                    case CoroutinesFlowTag f -> "coroutine Flow";
                };
            };
        }
    }

    @Nested
    class NoPublicConstructors {

        @Test
        void every_concrete_default_class_has_only_a_private_constructor() {
            // Given the full list of *Default singleton classes — the
            // package-private concrete implementations introduced by
            // Q.7.5. The tag interfaces themselves have no constructors,
            // so the check moves to the *Default classes.
            List<Class<?>> defaultClasses = List.of(
                    SyncTagDefault.class, AsyncTagDefault.class,
                    ReactiveMonoTagDefault.class, ReactiveFluxTagDefault.class,
                    RxJava3SingleTagDefault.class, RxJava3MaybeTagDefault.class,
                    RxJava3CompletableTagDefault.class, RxJava3FlowableTagDefault.class,
                    RxJava3ObservableTagDefault.class,
                    CoroutinesSuspendTagDefault.class, CoroutinesDeferredTagDefault.class,
                    CoroutinesJobTagDefault.class, CoroutinesFlowTagDefault.class);

            // When inspecting each via reflection
            // Then exactly one constructor exists, and it is private
            for (Class<?> cls : defaultClasses) {
                Constructor<?>[] ctors = cls.getDeclaredConstructors();
                assertThat(ctors)
                        .as("Class " + cls.getSimpleName() + " has exactly one declared constructor")
                        .hasSize(1);
                assertThat(Modifier.isPrivate(ctors[0].getModifiers()))
                        .as("Class " + cls.getSimpleName() + "'s sole constructor is private")
                        .isTrue();
            }
        }
    }

    @Nested
    class LeafTagSealedPermits {

        @Test
        void sync_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(SyncTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(SyncTagDefault.class);
        }

        @Test
        void async_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(AsyncTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(AsyncTagDefault.class);
        }

        @Test
        void reactive_mono_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(ReactiveMonoTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(ReactiveMonoTagDefault.class);
        }

        @Test
        void reactive_flux_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(ReactiveFluxTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(ReactiveFluxTagDefault.class);
        }

        @Test
        void rxjava3_single_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(RxJava3SingleTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(RxJava3SingleTagDefault.class);
        }

        @Test
        void rxjava3_maybe_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(RxJava3MaybeTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(RxJava3MaybeTagDefault.class);
        }

        @Test
        void rxjava3_completable_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(RxJava3CompletableTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(RxJava3CompletableTagDefault.class);
        }

        @Test
        void rxjava3_flowable_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(RxJava3FlowableTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(RxJava3FlowableTagDefault.class);
        }

        @Test
        void rxjava3_observable_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(RxJava3ObservableTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(RxJava3ObservableTagDefault.class);
        }

        @Test
        void coroutines_suspend_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(CoroutinesSuspendTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(CoroutinesSuspendTagDefault.class);
        }

        @Test
        void coroutines_deferred_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(CoroutinesDeferredTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(CoroutinesDeferredTagDefault.class);
        }

        @Test
        void coroutines_job_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(CoroutinesJobTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(CoroutinesJobTagDefault.class);
        }

        @Test
        void coroutines_flow_tag_permits_exactly_its_default() {
            assertThat(java.util.Set.of(CoroutinesFlowTag.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(CoroutinesFlowTagDefault.class);
        }
    }
}
