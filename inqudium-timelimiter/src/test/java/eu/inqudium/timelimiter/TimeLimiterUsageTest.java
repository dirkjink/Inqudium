package eu.inqudium.timelimiter;

import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.timelimiter.InqTimeLimitExceededException;
import eu.inqudium.core.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates TimeLimiter usage from a library user's perspective.
 *
 * <p>All standalone tests follow the real-world pattern: decorate once, then
 * invoke the wrapper. The time limiter bounds the caller's wait time without
 * interrupting the downstream operation.
 */
@DisplayName("TimeLimiter — User Perspective")
class TimeLimiterUsageTest {

    // ── Simulated service with configurable latency ──

    static class ShippingService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final long latencyMs;

        ShippingService(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        String calculateShipping(String orderId) {
            callCount.incrementAndGet();
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "shipping-" + orderId;
        }

        int getCallCount() { return callCount.get(); }
    }

    @Nested
    @DisplayName("Standalone usage — synchronous supplier")
    class StandaloneSync {

        @Test
        void should_return_result_when_call_completes_within_timeout() {
            // Given — fast service (50ms), generous timeout (2s)
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilientCalc = tl.decorateSupplier(
                    () -> service.calculateShipping("order-1"));

            // When
            var result = resilientCalc.get();

            // Then
            assertThat(result).isEqualTo("shipping-order-1");
            assertThat(service.getCallCount()).isEqualTo(1);
        }

        @Test
        void should_throw_time_limit_exceeded_when_call_is_too_slow() {
            // Given — slow service (2s), tight timeout (100ms)
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilientCalc = tl.decorateSupplier(
                    () -> service.calculateShipping("slow"));

            // When / Then
            assertThatThrownBy(resilientCalc::get)
                    .isInstanceOf(InqTimeLimitExceededException.class)
                    .satisfies(ex -> {
                        var tlEx = (InqTimeLimitExceededException) ex;
                        assertThat(tlEx.getCode()).isEqualTo("INQ-TL-001");
                        assertThat(tlEx.getElementName()).isEqualTo("shippingService");
                        assertThat(tlEx.getConfiguredDuration()).isEqualTo(Duration.ofMillis(100));
                        assertThat(tlEx.getActualDuration()).isGreaterThanOrEqualTo(Duration.ofMillis(100));
                    });
        }

        @Test
        void should_allow_catching_timeouts_via_inq_failure() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilientCalc = tl.decorateSupplier(
                    () -> service.calculateShipping("timeout"));

            // When
            var handled = new AtomicInteger(0);
            try {
                resilientCalc.get();
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifTimeLimitExceeded(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getConfiguredDuration()).isEqualTo(Duration.ofMillis(100));
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }
    }

    @Nested
    @DisplayName("Standalone usage — future supplier")
    class StandaloneFuture {

        @Test
        void should_return_result_from_a_decorated_future_supplier() {
            // Given — decorate once, reuse the wrapper
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilientFuture = tl.decorateFutureSupplier(
                    () -> CompletableFuture.completedFuture("immediate-result"));

            // When
            var result = resilientFuture.get();

            // Then
            assertThat(result).isEqualTo("immediate-result");
        }

        @Test
        void should_return_result_from_an_async_future_supplier() {
            // Given
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilientFuture = tl.decorateFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> "async-result"));

            // When
            var result = resilientFuture.get();

            // Then
            assertThat(result).isEqualTo("async-result");
        }

        @Test
        void should_throw_time_limit_exceeded_for_slow_future() {
            // Given
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilientFuture = tl.decorateFutureSupplier(
                    () -> CompletableFuture.supplyAsync(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return "too-slow";
                    }));

            // When / Then
            assertThatThrownBy(resilientFuture::get)
                    .isInstanceOf(InqTimeLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_time_limit_a_call_through_the_pipeline() {
            // Given
            var service = new ShippingService(50);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofSeconds(2))
                    .build());
            Supplier<String> resilient = InqPipeline.of(
                            () -> service.calculateShipping("pipeline-1"))
                    .shield(tl)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("shipping-pipeline-1");
        }

        @Test
        void should_carry_a_pipeline_call_id_on_timeout_exception() {
            // Given
            var service = new ShippingService(2000);
            var tl = TimeLimiter.of("shippingService", TimeLimiterConfig.builder()
                    .timeoutDuration(Duration.ofMillis(100))
                    .build());
            Supplier<String> resilient = InqPipeline.of(
                            () -> service.calculateShipping("slow"))
                    .shield(tl)
                    .decorate();

            // When / Then — exception carries a pipeline-generated callId
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqTimeLimitExceededException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None");
                    });
        }
    }
}
