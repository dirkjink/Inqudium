package eu.inqudium.imperative.runtime;

import eu.inqudium.config.Inqudium;
import eu.inqudium.config.runtime.BulkheadHandle;
import eu.inqudium.config.runtime.InqRuntime;
import eu.inqudium.core.element.paradigm.AsyncTag;
import eu.inqudium.core.element.paradigm.SyncTag;
import eu.inqudium.imperative.bulkhead.InqBulkhead;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link BulkheadHandle#unwrap(Class)} surface introduced
 * to eliminate the {@code (InqBulkhead<...>) runtime.sync().bulkhead(...)}
 * cast pattern. Three groups: direct sync-handle unwrap, wrapped
 * async-handle unwrap (recursive delegation through
 * {@code BulkheadHandleAsAsyncView}), and the two error paths.
 */
class BulkheadHandleUnwrapTest {

    private static InqRuntime runtimeWithFooBulkhead() {
        return Inqudium.configure()
                .sync(s -> s.bulkhead("foo",
                        b -> b.balanced().maxConcurrentCalls(5)))
                .build();
    }

    @Nested
    class DirectSyncHandle {

        @Test
        void unwrap_returns_the_underlying_inq_bulkhead() {
            // Given a runtime with one bulkhead
            InqRuntime runtime = runtimeWithFooBulkhead();

            // When obtained via the sync surface and unwrapped
            BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("foo");
            InqBulkhead<?, ?> unwrapped = handle.unwrap(InqBulkhead.class);

            // Then the unwrapped instance is the same object — the
            // sync surface returns the InqBulkhead directly post-Q.7
            assertThat(unwrapped).isSameAs(handle);
        }
    }

    @Nested
    class WrappedAsyncHandle {

        @Test
        void unwrap_unwraps_the_async_view_to_the_underlying_inq_bulkhead() {
            // Given a runtime with one bulkhead
            InqRuntime runtime = runtimeWithFooBulkhead();

            // When obtained via the async surface and unwrapped
            BulkheadHandle<AsyncTag> asyncHandle =
                    runtime.async().bulkhead("foo");
            InqBulkhead<?, ?> unwrapped =
                    asyncHandle.unwrap(InqBulkhead.class);

            // Then the unwrapped instance is the underlying
            // sync InqBulkhead — the async view wraps the sync
            // handle, which IS-A InqBulkhead.
            assertThat(unwrapped).isNotSameAs(asyncHandle);
            BulkheadHandle<SyncTag> syncHandle =
                    runtime.sync().bulkhead("foo");
            assertThat(unwrapped).isSameAs(syncHandle);
        }
    }

    @Nested
    class TypeMismatch {

        @Test
        void unwrap_throws_class_cast_exception_for_unrelated_type() {
            // Given
            InqRuntime runtime = runtimeWithFooBulkhead();
            BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("foo");

            // When / Then
            assertThatThrownBy(() -> handle.unwrap(String.class))
                    .isInstanceOf(ClassCastException.class);
        }

        @Test
        void unwrap_throws_npe_for_null_target() {
            // Given
            InqRuntime runtime = runtimeWithFooBulkhead();
            BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("foo");

            // When / Then
            assertThatThrownBy(() -> handle.unwrap(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
