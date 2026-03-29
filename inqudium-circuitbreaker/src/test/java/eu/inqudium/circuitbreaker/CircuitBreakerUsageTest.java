package eu.inqudium.circuitbreaker;

import eu.inqudium.core.InqElementType;
import eu.inqudium.core.circuitbreaker.CircuitBreakerConfig;
import eu.inqudium.core.circuitbreaker.CircuitBreakerState;
import eu.inqudium.core.circuitbreaker.InqCallNotPermittedException;
import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.exception.InqRuntimeException;
import eu.inqudium.core.pipeline.InqPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates CircuitBreaker usage from a library user's perspective.
 *
 * <p>All standalone tests follow the real-world pattern: decorate once, then
 * invoke the wrapper — just like a Spring bean would be wired once and called
 * many times.
 */
@DisplayName("CircuitBreaker — User Perspective")
class CircuitBreakerUsageTest {

    // ── Simulated downstream service ──

    static class PaymentService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private boolean failing = false;

        String charge(String orderId) {
            callCount.incrementAndGet();
            if (failing) throw new RuntimeException("Payment gateway unavailable");
            return "receipt-" + orderId;
        }

        void setFailing(boolean failing) { this.failing = failing; }
        int getCallCount() { return callCount.get(); }
    }

    @Nested
    @DisplayName("Standalone usage")
    class Standalone {

        @Test
        void should_let_calls_through_when_circuit_is_closed() {
            // Given — decorate once, reuse the wrapper
            var service = new PaymentService();
            var cb = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
                    .failureRateThreshold(50)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .build());
            Supplier<String> resilientCharge = cb.decorateSupplier(() -> service.charge("order-1"));

            // When
            var result = resilientCharge.get();

            // Then
            assertThat(result).isEqualTo("receipt-order-1");
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_open_after_failure_rate_threshold_is_exceeded() {
            // Given — a single decorated wrapper reused across all calls
            var service = new PaymentService();
            var cb = CircuitBreaker.of("paymentService", CircuitBreakerConfig.builder()
                    .failureRateThreshold(50)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .build());
            Supplier<String> resilientCharge = cb.decorateSupplier(() -> service.charge("order"));

            // When — 2 successes, then 2 failures → 50% failure rate
            resilientCharge.get();
            resilientCharge.get();
            service.setFailing(true);
            catchThrowable(resilientCharge::get);
            catchThrowable(resilientCharge::get);

            // Then — circuit should be OPEN, subsequent calls rejected without reaching the service
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.OPEN);
            var beforeCount = service.getCallCount();
            assertThatThrownBy(resilientCharge::get)
                    .isInstanceOf(InqCallNotPermittedException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqCallNotPermittedException) ex;
                        assertThat(inqEx.getCode()).isEqualTo("INQ-CB-001");
                        assertThat(inqEx.getElementName()).isEqualTo("paymentService");
                        assertThat(inqEx.getState()).isEqualTo(CircuitBreakerState.OPEN);
                    });
            assertThat(service.getCallCount()).isEqualTo(beforeCount);
        }

        @Test
        void should_decorate_a_callable_wrapping_checked_exceptions() {
            // Given — decorate a Callable that throws a checked exception
            var cb = CircuitBreaker.ofDefaults("paymentService");
            Supplier<String> resilient = cb.decorateCallable(() -> {
                throw new java.io.IOException("disk full");
            });

            // When / Then — checked exception is wrapped, not swallowed
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqRuntimeException.class)
                    .hasCauseInstanceOf(java.io.IOException.class)
                    .satisfies(ex -> {
                        var ire = (InqRuntimeException) ex;
                        assertThat(ire.getCode()).isEqualTo("INQ-CB-000");
                        assertThat(ire.getElementName()).isEqualTo("paymentService");
                        assertThat(ire.getElementType()).isEqualTo(InqElementType.CIRCUIT_BREAKER);
                        assertThat(ire.hasElementContext()).isTrue();
                    });
        }

        @Test
        void should_decorate_a_runnable_for_fire_and_forget() {
            // Given
            var executed = new AtomicInteger(0);
            var cb = CircuitBreaker.ofDefaults("notificationService");
            Runnable resilientNotify = cb.decorateRunnable(executed::incrementAndGet);

            // When
            resilientNotify.run();

            // Then
            assertThat(executed).hasValue(1);
        }

        @Test
        void should_allow_catching_interventions_via_inq_failure() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();
            Supplier<String> resilient = cb.decorateSupplier(() -> "should not reach");

            // When
            var handled = new AtomicInteger(0);
            try {
                resilient.get();
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifCircuitBreakerOpen(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getElementName()).isEqualTo("paymentService");
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_protect_a_call_through_the_pipeline() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            Supplier<String> resilient = InqPipeline.of(() -> service.charge("pipeline-1"))
                    .shield(cb)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("receipt-pipeline-1");
            assertThat(cb.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        }

        @Test
        void should_reject_calls_when_circuit_opens_in_pipeline() {
            // Given
            var service = new PaymentService();
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();
            Supplier<String> resilient = InqPipeline.of(() -> service.charge("should-not-reach"))
                    .shield(cb)
                    .decorate();

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqCallNotPermittedException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId()).isNotNull().isNotEqualTo("None");
                        assertThat(inqEx.getCode()).isEqualTo("INQ-CB-001");
                    });
            assertThat(service.getCallCount()).isZero();
        }

        @Test
        void should_carry_the_same_call_id_across_pipeline_exceptions() {
            // Given
            var cb = CircuitBreaker.ofDefaults("paymentService");
            cb.transitionToOpenState();
            Supplier<String> resilient = InqPipeline.of(() -> "unreachable")
                    .shield(cb)
                    .decorate();

            // When / Then — the exception carries a pipeline-generated callId (UUID format)
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None")
                                .hasSizeGreaterThan(8);
                    });
        }
    }
}
