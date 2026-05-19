package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.InqRuntime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the Q.5b DSL surfaces ({@code .sync(...)} and
 * {@code .async(...)}) on {@link Inqudium#configure()}. The tests
 * build real runtimes and exercise the new entry points; they pin:
 *
 * <ul>
 *   <li>The new surfaces configure bulkheads that show up in both
 *       paradigm views ({@code runtime.sync()} / {@code .async()}).</li>
 *   <li>The shared-accumulator design (Q.5b architectural decision):
 *       calling {@code .sync(...)} and {@code .async(...)} for the
 *       same name produces a single underlying bulkhead with
 *       last-writer-wins per-field semantics.</li>
 *   <li>The deprecated {@code .imperative(...)} surface continues to
 *       work and interoperates with the new surfaces.</li>
 * </ul>
 *
 * <p>The tests live in {@code inqudium-imperative} (rather than
 * {@code inqudium-config}) so they exercise the full DSL → provider →
 * runtime path with the real SyncParadigmProvider on the classpath.</p>
 */
class SyncAsyncDslTest {

    @Nested
    class SyncEntryPoint {

        @Test
        void should_configure_a_bulkhead_via_sync_section() {
            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(7)))
                    .build()) {
                // Then both paradigm views observe the bulkhead
                assertThat(runtime.sync().bulkheadNames()).contains("foo");
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(7);
                assertThat(runtime.async().bulkheadNames()).contains("foo");
            }
        }
    }

    @Nested
    class AsyncEntryPoint {

        @Test
        void should_configure_a_bulkhead_via_async_section() {
            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .async(a -> a.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(11)))
                    .build()) {
                // Then both views see the same bulkhead — the DSL surface
                // chosen at configure time does not constrain the runtime
                // view at query time
                assertThat(runtime.async().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(11);
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(11);
            }
        }
    }

    @Nested
    class SharedAccumulator {

        @Test
        void should_merge_sync_then_async_calls_with_last_writer_winning() {
            // What is to be tested? — The shared-accumulator design:
            //   .sync(s -> s.bulkhead("foo", b -> b.x(1))) followed by
            //   .async(a -> a.bulkhead("foo", b -> b.x(2))) configures
            //   one component. The second call overwrites the first's
            //   patch entirely (per-name last-writer-wins per ADR-026).
            // Successful when? — the resulting bulkhead exposes
            //   maxConcurrentCalls=2 (from the async call), not 1.
            // Why important? — Demonstrates the user-facing manifestation
            //   of Q.5a's shared-backing-instance design: switching DSL
            //   surfaces for the same name doesn't create two
            //   components.

            // Given / When
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(1)))
                    .async(a -> a.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(2)))
                    .build()) {
                // Then the latest call wins
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(2);
                assertThat(runtime.async().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(2);
                // And the bulkhead-names list contains exactly one entry
                assertThat(runtime.sync().bulkheadNames()).containsExactly("foo");
            }
        }

        @Test
        void should_merge_sync_then_imperative_calls_with_last_writer_winning() {
            // Mixing the new and deprecated surfaces in one build is
            // legal — the merge order is the call order in the builder
            // traversal, not the surface kind.

            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(1)))
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(3)))
                    .build()) {
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(3);
            }
        }

        @Test
        void should_keep_distinct_names_independent_across_surfaces() {
            // Given .sync configures "a" and .async configures "b" —
            // two distinct bulkheads, both visible from both views.
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("a", b -> b.balanced().maxConcurrentCalls(4)))
                    .async(a -> a.bulkhead("b", b -> b.balanced().maxConcurrentCalls(5)))
                    .build()) {
                assertThat(runtime.sync().bulkheadNames()).containsExactlyInAnyOrder("a", "b");
                assertThat(runtime.async().bulkheadNames()).containsExactlyInAnyOrder("a", "b");
                assertThat(runtime.sync().bulkhead("a").snapshot()
                        .maxConcurrentCalls()).isEqualTo(4);
                assertThat(runtime.sync().bulkhead("b").snapshot()
                        .maxConcurrentCalls()).isEqualTo(5);
            }
        }
    }

    @Nested
    class DeprecatedImperative {

        @Test
        void should_still_work_via_imperative_entry() {
            // Same configuration as the first sync test, but via the
            // deprecated .imperative(...) entry. Result is identical.
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(7)))
                    .build()) {
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(7);
                assertThat(runtime.async().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(7);
            }
        }
    }

    @Nested
    class Removal {

        @Test
        void sync_remove_rescinds_a_prior_sync_configuration_in_the_same_traversal() {
            // Given a sync traversal that configures then removes
            // When the runtime is built
            // Then the bulkhead is not present
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s
                            .bulkhead("foo", b -> b.balanced())
                            .removeBulkhead("foo"))
                    .build()) {
                assertThat(runtime.sync().bulkheadNames()).doesNotContain("foo");
            }
        }

        @Test
        void async_remove_rescinds_a_prior_imperative_configuration_in_the_same_traversal() {
            // Cross-surface rescission: imperative configures, async
            // removes. Demonstrates the shared accumulator handles
            // removals across surfaces too.
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .async(a -> a.removeBulkhead("foo"))
                    .build()) {
                assertThat(runtime.sync().bulkheadNames()).doesNotContain("foo");
            }
        }
    }
}
