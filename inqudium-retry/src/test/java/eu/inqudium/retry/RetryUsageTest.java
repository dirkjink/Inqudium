package eu.inqudium.retry;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.retry.InqRetryExhaustedException;
import eu.inqudium.core.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates Retry usage from a library user's perspective.
 */
@DisplayName("Retry — User Perspective")
class RetryUsageTest {

    // ── Simulated flaky service ──

    static class InventoryService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private int failUntilAttempt;

        InventoryService(int failUntilAttempt) {
            this.failUntilAttempt = failUntilAttempt;
        }

        String checkStock(String sku) {
            int attempt = callCount.incrementAndGet();
            if (attempt < failUntilAttempt) {
                throw new RuntimeException("Service temporarily unavailable (attempt " + attempt + ")");
            }
            return "in-stock:" + sku;
        }

        int getCallCount() { return callCount.get(); }
    }

    @Nested
    @DisplayName("Standalone usage")
    class Standalone {

        @Test
        void should_succeed_on_first_attempt_when_service_is_healthy() {
            // Given
            var service = new InventoryService(1);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            // When
            var result = retry.executeSupplier(() -> service.checkStock("SKU-100"));

            // Then
            assertThat(result).isEqualTo("in-stock:SKU-100");
            assertThat(service.getCallCount()).isEqualTo(1);
        }

        @Test
        void should_retry_and_eventually_succeed_after_transient_failures() {
            // Given — service fails on first 2 attempts, succeeds on 3rd
            var service = new InventoryService(3);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            // When
            var result = retry.executeSupplier(() -> service.checkStock("SKU-200"));

            // Then
            assertThat(result).isEqualTo("in-stock:SKU-200");
            assertThat(service.getCallCount()).isEqualTo(3);
        }

        @Test
        void should_throw_retry_exhausted_when_all_attempts_fail() {
            // Given — service always fails
            var service = new InventoryService(Integer.MAX_VALUE);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            // When / Then
            assertThatThrownBy(() -> retry.executeSupplier(() -> service.checkStock("SKU-300")))
                    .isInstanceOf(InqRetryExhaustedException.class)
                    .satisfies(ex -> {
                        var retryEx = (InqRetryExhaustedException) ex;
                        assertThat(retryEx.getCode()).isEqualTo("INQ-RT-001");
                        assertThat(retryEx.getElementName()).isEqualTo("inventoryService");
                        assertThat(retryEx.getAttempts()).isEqualTo(3);
                        assertThat(retryEx.getLastCause())
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining("temporarily unavailable");
                    });
            assertThat(service.getCallCount()).isEqualTo(3);
        }

        @Test
        void should_carry_checked_exceptions_as_last_cause_of_retry_exhausted() {
            // Given
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(1)
                    .build());

            // When / Then — checked exception is unwrapped and stored as lastCause
            assertThatThrownBy(() ->
                    retry.executeCallable(() -> { throw new java.io.IOException("network error"); })
            ).isInstanceOf(InqRetryExhaustedException.class)
                    .satisfies(ex -> {
                        var retryEx = (InqRetryExhaustedException) ex;
                        assertThat(retryEx.getCode()).isEqualTo("INQ-RT-001");
                        assertThat(retryEx.getElementType()).isEqualTo(InqElementType.RETRY);
                        assertThat(retryEx.getAttempts()).isEqualTo(1);
                        assertThat(retryEx.getLastCause())
                                .isInstanceOf(java.io.IOException.class)
                                .hasMessage("network error");
                    });
        }

        @Test
        void should_allow_catching_exhausted_retries_via_inq_failure() {
            // Given
            var service = new InventoryService(Integer.MAX_VALUE);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(2)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            // When
            var handled = new AtomicInteger(0);
            try {
                retry.executeSupplier(() -> service.checkStock("fail"));
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifRetryExhausted(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getAttempts()).isEqualTo(2);
                            assertThat(info.getLastCause()).isNotNull();
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }

        @Test
        void should_decorate_a_supplier_for_lazy_execution() {
            // Given
            var service = new InventoryService(2);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            // When — decorate, no call yet
            Supplier<String> resilient = retry.decorateSupplier(() -> service.checkStock("lazy"));
            assertThat(service.getCallCount()).isZero();

            // Then — calling .get() triggers execution with retries
            var result = resilient.get();
            assertThat(result).isEqualTo("in-stock:lazy");
            assertThat(service.getCallCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_retry_a_call_through_the_pipeline() {
            // Given — service fails on first attempt, succeeds on 2nd
            var service = new InventoryService(2);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(3)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            Supplier<String> resilient = InqPipeline.of(() -> service.checkStock("pipeline-1"))
                    .shield(retry)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("in-stock:pipeline-1");
            assertThat(service.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_carry_a_pipeline_call_id_on_retry_exhausted_exception() {
            // Given
            var service = new InventoryService(Integer.MAX_VALUE);
            var retry = Retry.of("inventoryService", RetryConfig.builder()
                    .maxAttempts(2)
                    .initialInterval(Duration.ofMillis(10))
                    .build());

            Supplier<String> resilient = InqPipeline.of(() -> service.checkStock("fail"))
                    .shield(retry)
                    .decorate();

            // When / Then — exception carries a pipeline-generated callId
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqRetryExhaustedException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None");
                    });
        }
    }
}
