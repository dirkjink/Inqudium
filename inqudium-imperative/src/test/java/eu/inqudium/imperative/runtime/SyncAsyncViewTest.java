package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.Async;
import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.config.runtime.Sync;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.core.element.paradigm.SyncTag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the {@code Sync} / {@code Async} runtime
 * accessors introduced in Q.5a. The tests build a real runtime via
 * {@code Inqudium.configure()...build()}, then exercise the
 * paradigm-tagged views and verify that both views project the
 * same underlying {@code Sync} container.
 *
 * <p>Identity-sharing is verified by reference equality on the
 * underlying handle returned by the view wrappers'
 * {@code wrapped()} accessor (package-private, accessible because
 * the test runs from a sibling module's test classpath — the wrappers
 * live in {@code inqudium-config.runtime}; we observe them only via
 * the public {@link BulkheadHandle} surface and via the runtime's
 * own update propagation as proof of shared backing).</p>
 */
class SyncAsyncViewTest {

    @Nested
    class RuntimeAccessors {

        @Test
        void runtime_exposes_sync_and_async_accessors() {
            // Given a runtime with one bulkhead
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {

                // When obtained via both views
                Sync sync = runtime.sync();
                Async async = runtime.async();

                // Then both views work and expose the bulkhead
                assertThat(sync).isNotNull();
                assertThat(async).isNotNull();
                assertThat(sync.bulkheadNames()).contains("foo");
                assertThat(async.bulkheadNames()).contains("foo");
            }
        }

        @Test
        void deprecated_imperative_accessor_still_works() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {

                // When / Then
                assertThat(runtime.sync()).isNotNull();
                assertThat(runtime.sync().bulkheadNames()).contains("foo");
            }
        }

        @Test
        void sync_view_paradigm_returns_sync_tag() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                // When / Then
                assertThat(runtime.sync().paradigm()).isSameAs(SyncTag.INSTANCE);
            }
        }

        @Test
        void async_view_paradigm_returns_async_tag() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                // When / Then
                assertThat(runtime.async().paradigm()).isSameAs(AsyncTag.INSTANCE);
            }
        }
    }

    @Nested
    class TypedHandleSurface {

        @Test
        void sync_bulkhead_returns_handle_typed_with_sync_tag() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                // When
                BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("foo");
                // Then
                assertThat(handle).isNotNull();
                assertThat(handle.name()).isEqualTo("foo");
            }
        }

        @Test
        void async_bulkhead_returns_handle_typed_with_async_tag() {
            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                // When
                BulkheadHandle<AsyncTag> handle = runtime.async().bulkhead("foo");
                // Then
                assertThat(handle).isNotNull();
                assertThat(handle.name()).isEqualTo("foo");
            }
        }

        @Test
        void sync_handle_exposes_the_same_snapshot_as_imperative_handle() {
            // Given a runtime with one bulkhead
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(7)))
                    .build()) {
                // When
                BulkheadHandle<SyncTag> syncHandle = runtime.sync().bulkhead("foo");
                BulkheadHandle<SyncTag> impHandle = runtime.sync().bulkhead("foo");
                // Then both surface the same snapshot values
                assertThat(syncHandle.snapshot().name())
                        .isEqualTo(impHandle.snapshot().name());
                assertThat(syncHandle.snapshot().maxConcurrentCalls())
                        .isEqualTo(impHandle.snapshot().maxConcurrentCalls());
            }
        }
    }

    @Nested
    class SharedBackingInstance {

        @Test
        void sync_and_async_views_observe_the_same_runtime_state() {
            // What is to be tested? — Both views over the same name
            //   must read the same runtime state. We can't directly
            //   identity-check the wrapped handles from outside the
            //   inqudium-config.runtime package (wrappers are
            //   package-private), so we verify via the most concrete
            //   externally-observable shared property: the snapshot
            //   reference values are identical at any given moment.
            // Successful when? — every snapshot field read from the
            //   sync view matches the equivalent field on the async
            //   view's snapshot.
            // Why important? — If the views were backed by separate
            //   bulkhead instances, the snapshots would diverge after
            //   any runtime update; pinning identity here guards
            //   that.

            // Given
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(10)))
                    .build()) {

                // When
                BulkheadHandle<SyncTag> syncHandle = runtime.sync().bulkhead("foo");
                BulkheadHandle<AsyncTag> asyncHandle = runtime.async().bulkhead("foo");

                // Then
                assertThat(syncHandle.snapshot().name())
                        .isEqualTo(asyncHandle.snapshot().name());
                assertThat(syncHandle.snapshot().maxConcurrentCalls())
                        .isEqualTo(asyncHandle.snapshot().maxConcurrentCalls());
                assertThat(syncHandle.availablePermits())
                        .isEqualTo(asyncHandle.availablePermits());
            }
        }

        @Test
        void runtime_update_propagates_to_both_views() {
            // What is to be tested? — A runtime update applied through
            //   the deprecated imperative DSL is observable through
            //   both the sync and async views. Confirms façade
            //   propagation: the views read live state from the
            //   underlying registry, not cached snapshots taken at
            //   construction.
            // Successful when? — after updating maxConcurrentCalls
            //   from 10 to 20, both views read 20.
            // Why important? — If the views cached snapshots, runtime
            //   updates would silently fail to be observable. The
            //   façade contract is that the views are pure read-paths.

            // Given a runtime with one bulkhead at max=10
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo",
                            b -> b.balanced().maxConcurrentCalls(10)))
                    .build()) {

                // Confirm baseline through both views
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(10);
                assertThat(runtime.async().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(10);

                // When an update bumps the max to 20 via the legacy
                // imperative DSL (the only available DSL path today;
                // Q.5b adds sync()/async() DSL entry points)
                runtime.update(b -> b.sync(s -> s.bulkhead("foo",
                        bh -> bh.balanced().maxConcurrentCalls(20))));

                // Then both views see the new value
                assertThat(runtime.sync().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(20);
                assertThat(runtime.async().bulkhead("foo").snapshot()
                        .maxConcurrentCalls()).isEqualTo(20);
            }
        }
    }

    @Nested
    class FindBulkheadOptional {

        @Test
        void sync_find_returns_empty_for_unknown_name() {
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                assertThat(runtime.sync().findBulkhead("nope")).isEmpty();
            }
        }

        @Test
        void async_find_returns_empty_for_unknown_name() {
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                assertThat(runtime.async().findBulkhead("nope")).isEmpty();
            }
        }

        @Test
        void sync_find_returns_present_for_known_name() {
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                assertThat(runtime.sync().findBulkhead("foo")).isPresent();
            }
        }

        @Test
        void async_find_returns_present_for_known_name() {
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s.bulkhead("foo", b -> b.balanced()))
                    .build()) {
                assertThat(runtime.async().findBulkhead("foo")).isPresent();
            }
        }
    }

    @Nested
    class BulkheadNamesEnumeration {

        @Test
        void sync_view_lists_all_configured_bulkhead_names() {
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s
                            .bulkhead("a", b -> b.balanced())
                            .bulkhead("b", b -> b.balanced()))
                    .build()) {
                assertThat(runtime.sync().bulkheadNames()).containsExactlyInAnyOrder("a", "b");
            }
        }

        @Test
        void async_view_lists_all_configured_bulkhead_names() {
            try (InqRuntime runtime = Inqudium.configure()
                    .sync(s -> s
                            .bulkhead("a", b -> b.balanced())
                            .bulkhead("b", b -> b.balanced()))
                    .build()) {
                assertThat(runtime.async().bulkheadNames()).containsExactlyInAnyOrder("a", "b");
            }
        }
    }
}
