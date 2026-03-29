package eu.inqudium.ratelimiter;

import eu.inqudium.core.exception.InqException;
import eu.inqudium.core.exception.InqFailure;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.ratelimiter.InqRequestNotPermittedException;
import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates RateLimiter usage from a library user's perspective.
 */
@DisplayName("RateLimiter — User Perspective")
class RateLimiterUsageTest {

    // ── Simulated API client ──

    static class ApiClient {
        private final AtomicInteger callCount = new AtomicInteger(0);

        String fetchData(String endpoint) {
            callCount.incrementAndGet();
            return "response-from-" + endpoint;
        }

        int getCallCount() { return callCount.get(); }
    }

    @Nested
    @DisplayName("Standalone usage")
    class Standalone {

        @Test
        void should_permit_calls_within_the_rate_limit() {
            // Given
            var client = new ApiClient();
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(5)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .build());

            // When — 3 calls, well within the limit of 5
            var r1 = rl.executeSupplier(() -> client.fetchData("users"));
            var r2 = rl.executeSupplier(() -> client.fetchData("orders"));
            var r3 = rl.executeSupplier(() -> client.fetchData("products"));

            // Then
            assertThat(r1).isEqualTo("response-from-users");
            assertThat(r2).isEqualTo("response-from-orders");
            assertThat(r3).isEqualTo("response-from-products");
            assertThat(client.getCallCount()).isEqualTo(3);
        }

        @Test
        void should_reject_calls_that_exceed_the_rate_limit() {
            // Given
            var client = new ApiClient();
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(2)
                    .limitRefreshPeriod(Duration.ofSeconds(10))
                    .build());

            // When — exhaust the 2 permits
            rl.executeSupplier(() -> client.fetchData("call-1"));
            rl.executeSupplier(() -> client.fetchData("call-2"));

            // Then — 3rd call is rejected
            assertThatThrownBy(() -> rl.executeSupplier(() -> client.fetchData("call-3")))
                    .isInstanceOf(InqRequestNotPermittedException.class)
                    .satisfies(ex -> {
                        var rlEx = (InqRequestNotPermittedException) ex;
                        assertThat(rlEx.getCode()).isEqualTo("INQ-RL-001");
                        assertThat(rlEx.getElementName()).isEqualTo("apiGateway");
                        assertThat(rlEx.getWaitEstimate()).isPositive();
                    });

            // Service was never called for the rejected request
            assertThat(client.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_decorate_a_supplier_for_lazy_execution() {
            // Given
            var client = new ApiClient();
            var rl = RateLimiter.ofDefaults("apiGateway");

            // When — decorate, no call yet
            Supplier<String> resilient = rl.decorateSupplier(() -> client.fetchData("lazy"));
            assertThat(client.getCallCount()).isZero();

            // Then
            var result = resilient.get();
            assertThat(result).isEqualTo("response-from-lazy");
            assertThat(client.getCallCount()).isEqualTo(1);
        }

        @Test
        void should_allow_catching_rate_limit_denials_via_inq_failure() {
            // Given
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofSeconds(10))
                    .build());
            rl.executeSupplier(() -> "consume-permit");

            // When
            var handled = new AtomicInteger(0);
            try {
                rl.executeSupplier(() -> "rejected");
            } catch (RuntimeException e) {
                InqFailure.find(e)
                        .ifRateLimited(info -> {
                            handled.incrementAndGet();
                            assertThat(info.getElementName()).isEqualTo("apiGateway");
                            assertThat(info.getWaitEstimate()).isPositive();
                        })
                        .orElseThrow();
            }

            // Then
            assertThat(handled).hasValue(1);
        }

        @Test
        void should_support_manual_permit_acquisition() {
            // Given
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofSeconds(10))
                    .build());

            // When — acquire manually
            rl.acquirePermit();

            // Then — next acquire fails
            assertThatThrownBy(rl::acquirePermit)
                    .isInstanceOf(InqRequestNotPermittedException.class);
        }
    }

    @Nested
    @DisplayName("Pipeline usage")
    class Pipeline {

        @Test
        void should_rate_limit_a_call_through_the_pipeline() {
            // Given
            var client = new ApiClient();
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(5)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .build());

            Supplier<String> resilient = InqPipeline.of(() -> client.fetchData("pipeline"))
                    .shield(rl)
                    .decorate();

            // When
            var result = resilient.get();

            // Then
            assertThat(result).isEqualTo("response-from-pipeline");
        }

        @Test
        void should_carry_a_pipeline_call_id_on_rate_limit_denial() {
            // Given
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofSeconds(10))
                    .build());

            Supplier<String> resilient = InqPipeline.of(() -> "data")
                    .shield(rl)
                    .decorate();

            // Exhaust the single permit
            resilient.get();

            // When / Then
            assertThatThrownBy(resilient::get)
                    .isInstanceOf(InqRequestNotPermittedException.class)
                    .satisfies(ex -> {
                        var inqEx = (InqException) ex;
                        assertThat(inqEx.getCallId())
                                .isNotNull()
                                .isNotEqualTo("None");
                    });
        }
    }
}
