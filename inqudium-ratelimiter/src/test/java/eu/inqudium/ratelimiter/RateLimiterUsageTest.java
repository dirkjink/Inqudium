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
 *
 * <p>All standalone tests follow the real-world pattern: decorate once, then
 * invoke the wrapper repeatedly.
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
            // Given — decorate once, reuse the wrapper
            var client = new ApiClient();
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(5)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .build());
            Supplier<String> resilientFetch = rl.decorateSupplier(() -> client.fetchData("users"));

            // When — 3 calls, well within the limit of 5
            var r1 = resilientFetch.get();
            var r2 = resilientFetch.get();
            var r3 = resilientFetch.get();

            // Then
            assertThat(r1).isEqualTo("response-from-users");
            assertThat(r2).isEqualTo("response-from-users");
            assertThat(r3).isEqualTo("response-from-users");
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
            Supplier<String> resilientFetch = rl.decorateSupplier(() -> client.fetchData("data"));

            // When — exhaust the 2 permits
            resilientFetch.get();
            resilientFetch.get();

            // Then — 3rd call is rejected
            assertThatThrownBy(resilientFetch::get)
                    .isInstanceOf(InqRequestNotPermittedException.class)
                    .satisfies(ex -> {
                        var rlEx = (InqRequestNotPermittedException) ex;
                        assertThat(rlEx.getCode()).isEqualTo("INQ-RL-001");
                        assertThat(rlEx.getElementName()).isEqualTo("apiGateway");
                        assertThat(rlEx.getWaitEstimate()).isPositive();
                    });
            assertThat(client.getCallCount()).isEqualTo(2);
        }

        @Test
        void should_allow_catching_rate_limit_denials_via_inq_failure() {
            // Given
            var rl = RateLimiter.of("apiGateway", RateLimiterConfig.builder()
                    .limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofSeconds(10))
                    .build());
            Supplier<String> resilient = rl.decorateSupplier(() -> "data");
            resilient.get(); // consume the single permit

            // When
            var handled = new AtomicInteger(0);
            try {
                resilient.get();
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
            resilient.get(); // exhaust the single permit

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
