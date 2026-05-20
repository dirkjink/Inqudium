package eu.inqudium.proxy.introspection;

import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.event.InqEventPublisher;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerTerminal;
import eu.inqudium.pipeline.DetectionProxy;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.pipeline.introspection.InqIntrospector;
import eu.inqudium.pipeline.introspection.InqStackInfo;
import eu.inqudium.pipeline.introspection.MethodLayers;
import eu.inqudium.pipeline.introspection.ProxyStackInfo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link InqIntrospector} in
 * {@code inqudium-proxy}'s test sources, so that
 * {@code inqudium-proxy} is on the classpath and
 * {@link DetectionProxy#isPresent()} returns {@code true}.
 *
 * <p>The companion test in {@code inqudium-pipeline}'s
 * test sources ({@code InqIntrospectorTest}) exercises
 * the no-proxy-on-classpath branch; this class exercises
 * the positive proxy-paradigm dispatch path through the
 * {@code ProxyStackAdapterDelegation} reflective bridge.</p>
 */
class InqIntrospectorEndToEndTest {

    public interface OrderService {
        String greet(String name);
    }

    public static final class DefaultOrderService implements OrderService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    /** Minimal {@link InqDecorator} fixture used to populate the pipeline. */
    static final class FakeBulkhead implements InqDecorator<Object, Object> {

        private final String name;

        FakeBulkhead(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InqElementType elementType() {
            return InqElementType.BULKHEAD;
        }

        @Override
        public InqEventPublisher eventPublisher() {
            return null;
        }

        @Override
        public Object execute(long stackId, long callId, Object argument,
                              LayerTerminal<Object, Object> next) {
            return next.execute(stackId, callId, argument);
        }
    }

    @Nested
    class Proxy_paradigm_dispatch {

        @Test
        void should_detect_the_proxy_module_on_this_classpath() {
            // Sanity precondition for the rest of this class: if
            // DetectionProxy is false here, the reflective bridge
            // would short-circuit and the dispatch would never
            // reach ProxyStackAdapter. Fail loudly if so.
            assertThat(DetectionProxy.isPresent())
                    .as("inqudium-proxy must be on this module's test classpath")
                    .isTrue();
        }

        @Test
        void should_return_proxy_stack_info_for_a_pipeline_built_proxy() {
            // Given
            InqPipeline pipeline = InqPipeline.builder()
                    .shield(new FakeBulkhead("orderBh"))
                    .build();
            OrderService proxy = pipeline.protect(
                    OrderService.class, new DefaultOrderService());

            // When
            Optional<InqStackInfo> result = InqIntrospector.inspect(proxy);

            // Then
            assertThat(result).isPresent();
            InqStackInfo info = result.get();
            assertThat(info).isInstanceOf(ProxyStackInfo.class);
            assertThat(info.stackId()).isPositive();
            assertThat(info.targetType()).contains(OrderService.class);
            assertThat(info.elements())
                    .singleElement()
                    .satisfies(element -> {
                        assertThat(element.name()).isEqualTo("orderBh");
                        assertThat(element.elementType())
                                .isEqualTo(InqElementType.BULKHEAD);
                    });
            assertThat(info.methodLayers())
                    .extracting(MethodLayers::methodSignature)
                    .anyMatch(sig -> sig.contains("greet"));
        }

        @Test
        void should_return_empty_optional_for_a_non_inqudium_object() {
            // What is to be tested?
            //   With inqudium-proxy on the classpath, an arbitrary
            //   user object still must not match the proxy adapter —
            //   ProxyStackAdapter.supports() returns false for
            //   anything that isn't a JDK proxy with InqInvocationHandler.
            // How will the test case be deemed successful and why?
            //   inspect("not a stack") returns Optional.empty without
            //   reaching the adapter's inspect() method (which would
            //   throw IllegalArgumentException if called on a
            //   non-supported instance).
            // Why is it important to test this test case?
            //   Pins the supports() gate so a future regression that
            //   skipped supports() before inspect() would crash
            //   instead of silently returning empty.

            // Given / When
            Optional<InqStackInfo> result = InqIntrospector.inspect("not a stack");

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class Null_handling {

        @Test
        void should_return_empty_optional_when_input_is_null() {
            // Given / When / Then
            assertThat(InqIntrospector.inspect(null)).isEmpty();
        }
    }
}
