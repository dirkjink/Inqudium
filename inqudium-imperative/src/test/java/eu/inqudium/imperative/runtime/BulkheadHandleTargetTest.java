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

/**
 * Pins the {@link BulkheadHandle#target()} surface — the post-
 * Component/Handle-split replacement for the previous {@code unwrap}
 * pattern. Three groups: (a) {@code target()} returns the underlying
 * component (not the handle), (b) the sync and async handles for the
 * same name expose the same component instance, and (c) the
 * caller-witnessed variable type drives the inferred component class.
 */
class BulkheadHandleTargetTest {

    private static InqRuntime runtimeWithFooBulkhead() {
        return Inqudium.configure()
                .sync(s -> s.bulkhead("foo",
                        b -> b.balanced().maxConcurrentCalls(5)))
                .build();
    }

    @Nested
    class TargetReturnsComponent {

        @Test
        void target_returns_the_underlying_component_not_the_handle() {
            // Given a runtime with one bulkhead
            InqRuntime runtime = runtimeWithFooBulkhead();

            // When obtained via the sync surface and targeted
            BulkheadHandle<SyncTag> handle = runtime.sync().bulkhead("foo");
            InqBulkhead<?, ?> component = handle.target();

            // Then the component is a distinct object from the handle —
            // post-split, the handle wraps the component, it is not
            // the component.
            assertThat(component).isNotSameAs(handle);
            assertThat(component).isInstanceOf(InqBulkhead.class);
        }
    }

    @Nested
    class SyncAndAsyncShareComponent {

        @Test
        void sync_and_async_handles_for_same_name_expose_same_component() {
            // What is being tested: that the runtime keeps exactly one
            //                       bulkhead component per (paradigm-family, name)
            //                       and that both paradigm-tagged handle views
            //                       resolve target() to the same component reference.
            // Why it counts as success: identity equality of the two targets
            //                           proves the runtime did not duplicate
            //                           the component for the second paradigm view.
            // Why this matters: a duplicated component would mean two strategies,
            //                   two listener registries, two lifecycle phases —
            //                   silently breaking update routing and observability.

            // Given a runtime with one bulkhead
            InqRuntime runtime = runtimeWithFooBulkhead();

            // When both paradigm views are obtained
            BulkheadHandle<SyncTag> syncHandle = runtime.sync().bulkhead("foo");
            BulkheadHandle<AsyncTag> asyncHandle = runtime.async().bulkhead("foo");

            // Then their targets are the same component reference
            InqBulkhead<?, ?> syncTarget = syncHandle.target();
            InqBulkhead<?, ?> asyncTarget = asyncHandle.target();
            assertThat(syncTarget).isSameAs(asyncTarget);

            // And the handles themselves are different objects (different
            // paradigm-tagged wrappers around the shared component)
            assertThat(syncHandle).isNotSameAs(asyncHandle);
        }
    }

    @Nested
    class VariableTypeWitness {

        @Test
        void caller_variable_type_drives_the_inferred_component_class() {
            // What is being tested: that target()'s generic bound
            //                       <T extends InqElement.Kind.Bulkhead> infers
            //                       T from the receiving variable's declared
            //                       type, so call sites can pick any concrete
            //                       bulkhead component subtype they expect.
            // Why it counts as success: the assignment compiles and the
            //                           runtime value is an InqBulkhead with
            //                           the expected name; no explicit cast
            //                           was written.
            // Why this matters: pins the API contract that replaces the old
            //                   explicit (InqBulkhead<...>) cast on every
            //                   resolve site — the contract is "your variable
            //                   declares the type, target() returns that type".

            // Given
            InqRuntime runtime = runtimeWithFooBulkhead();

            // When the variable is typed as InqBulkhead<?, ?>
            InqBulkhead<?, ?> component = runtime.sync().bulkhead("foo").target();

            // Then T was inferred to InqBulkhead<?, ?> and the call returned
            // the matching component
            assertThat(component).isNotNull();
            assertThat(component.name()).isEqualTo("foo");
        }
    }
}
