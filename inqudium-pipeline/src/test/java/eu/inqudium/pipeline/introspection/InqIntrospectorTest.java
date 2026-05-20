package eu.inqudium.pipeline.introspection;

import eu.inqudium.pipeline.DetectionProxy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InqIntrospector} in the
 * {@code inqudium-pipeline} test classpath.
 *
 * <p>{@code inqudium-proxy} is <strong>not</strong> on this
 * module's test classpath (analogous to
 * {@code InqPipelineProtectWithoutProxyTest}), so
 * {@link DetectionProxy#isPresent()} returns {@code false}
 * here. The positive-path proxy dispatch test lives in
 * {@code inqudium-proxy}'s test sources, where the adapter
 * is actually loadable.</p>
 */
class InqIntrospectorTest {

    @Nested
    class Null_handling {

        @Test
        void should_return_empty_optional_when_input_is_null() {
            // Given / When
            Optional<InqStackInfo> result = InqIntrospector.inspect(null);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class Unrecognised_objects {

        @Test
        void should_return_empty_optional_for_an_arbitrary_user_object() {
            // Given
            Object plain = new Object();

            // When
            Optional<InqStackInfo> result = InqIntrospector.inspect(plain);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void should_return_empty_optional_for_a_string() {
            // What is to be tested?
            //   A common user-input type (String) that no adapter
            //   should claim. Guards against an overly-greedy
            //   adapter-supports check.
            // How will the test case be deemed successful and why?
            //   inspect("anything") returns Optional.empty.
            // Why is it important to test this test case?
            //   ADR-039's contract is that the introspector
            //   returns empty for unrelated objects without
            //   throwing — diagnostic tools must be safe to
            //   call on arbitrary inputs.

            // Given / When
            Optional<InqStackInfo> result = InqIntrospector.inspect("not a stack");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void should_return_empty_optional_for_a_non_inqudium_jdk_proxy() {
            // What is to be tested?
            //   A JDK proxy whose invocation handler is NOT an
            //   InqInvocationHandler. The proxy paradigm adapter
            //   would (when on the classpath) need to filter
            //   these out; the gate is the adapter's own
            //   supports() check. From the pipeline-side perspective
            //   without the proxy module, the result must be
            //   Optional.empty regardless.
            // How will the test case be deemed successful and why?
            //   inspect(someJdkProxy) returns Optional.empty.
            // Why is it important to test this test case?
            //   Pins the no-proxy-module branch: the introspector
            //   must not throw when DetectionProxy.isPresent()
            //   is false, even for objects that LOOK like proxies.

            // Given — a JDK proxy with a no-op invocation handler
            InvocationHandler handler = (proxy, method, args) -> null;
            Runnable jdkProxy = (Runnable) Proxy.newProxyInstance(
                    InqIntrospectorTest.class.getClassLoader(),
                    new Class<?>[]{Runnable.class},
                    handler);

            // When
            Optional<InqStackInfo> result = InqIntrospector.inspect(jdkProxy);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class Classpath_gate {

        @Test
        void should_short_circuit_when_proxy_module_is_absent() {
            // What is to be tested?
            //   DetectionProxy.isPresent() is false in this test
            //   module's classpath. The introspector's proxy
            //   branch must not be reached — ProxyStackAdapterDelegation
            //   must not be class-initialised — and the dispatch
            //   must terminate at the Optional.empty return.
            // How will the test case be deemed successful and why?
            //   1. DetectionProxy.isPresent() returns false (pre-check).
            //   2. inspect(anything) returns Optional.empty without
            //      throwing.
            // Why is it important to test this test case?
            //   This pins the "ADR-037 module-direction asymmetry
            //   handled gracefully" property: pipeline alone is
            //   usable without the proxy module on the classpath.

            // Given
            assertThat(DetectionProxy.isPresent())
                    .as("inqudium-proxy must NOT be on this module's test classpath")
                    .isFalse();

            // When
            Optional<InqStackInfo> result = InqIntrospector.inspect(new Object());

            // Then
            assertThat(result).isEmpty();
        }
    }
}
