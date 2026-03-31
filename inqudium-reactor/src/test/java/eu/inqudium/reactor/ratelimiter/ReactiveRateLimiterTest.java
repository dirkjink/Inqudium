package eu.inqudium.reactor.ratelimiter;

import eu.inqudium.core.ratelimiter.RateLimiterConfig;
import eu.inqudium.core.ratelimiter.RateLimiterEvent;
import eu.inqudium.core.ratelimiter.RateLimiterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactiveRateLimiter")
class ReactiveRateLimiterTest {

  private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
  private TestClock clock;

  @BeforeEach
  void setUp() {
    clock = new TestClock(BASE_TIME);
  }

  private RateLimiterConfig defaultConfig() {
    return RateLimiterConfig.builder("reactive-test")
        .capacity(5)
        .refillPermits(5)
        .refillPeriod(Duration.ofSeconds(1))
        .defaultTimeout(Duration.ZERO)
        .build();
  }

  private ReactiveRateLimiter createLimiter() {
    return new ReactiveRateLimiter(defaultConfig(), clock);
  }

  private ReactiveRateLimiter createLimiter(RateLimiterConfig config) {
    return new ReactiveRateLimiter(config, clock);
  }

  private void exhaustPermits(ReactiveRateLimiter limiter, int count) {
    for (int i = 0; i < count; i++) {
      limiter.execute(Mono.just("consume")).block();
    }
  }

  static class TestClock extends Clock {
    private volatile Instant instant;

    TestClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  // ================================================================
  // Mono Execution — Success
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Success Path")
  class MonoSuccessPath {

    @Test
    @DisplayName("should emit the upstream value when a permit is available")
    void should_emit_the_upstream_value_when_a_permit_is_available() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When
      Mono<String> result = limiter.execute(Mono.just("hello"));

      // Then
      StepVerifier.create(result)
          .expectNext("hello")
          .verifyComplete();
    }

    @Test
    @DisplayName("should permit multiple calls up to the bucket capacity")
    void should_permit_multiple_calls_up_to_the_bucket_capacity() {
      // Given
      ReactiveRateLimiter limiter = createLimiter(); // capacity=5

      // When / Then — all 5 should succeed
      for (int i = 0; i < 5; i++) {
        StepVerifier.create(limiter.execute(Mono.just("ok")))
            .expectNext("ok")
            .verifyComplete();
      }
    }

    @Test
    @DisplayName("should support lazy mono creation via supplier")
    void should_support_lazy_mono_creation_via_supplier() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When
      Mono<String> result = limiter.execute(() -> Mono.just("lazy"));

      // Then
      StepVerifier.create(result)
          .expectNext("lazy")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono Execution — Rate Limited
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Rate Limited")
  class MonoRateLimited {

    @Test
    @DisplayName("should emit RateLimiterException when the bucket is exhausted")
    void should_emit_rate_limiter_exception_when_the_bucket_is_exhausted() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      Mono<String> result = limiter.execute(Mono.just("should fail"));

      // Then
      StepVerifier.create(result)
          .expectError(RateLimiterException.class)
          .verify();
    }

    @Test
    @DisplayName("should include the estimated wait duration in the exception")
    void should_include_the_estimated_wait_duration_in_the_exception() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      Mono<String> result = limiter.execute(Mono.just("fail"));

      // Then
      StepVerifier.create(result)
          .expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(RateLimiterException.class);
            RateLimiterException rle = (RateLimiterException) error;
            assertThat(rle.getWaitDuration()).isPositive();
            assertThat(rle.getRateLimiterName()).isEqualTo("reactive-test");
          })
          .verify();
    }

    @Test
    @DisplayName("should permit again after the refill period")
    void should_permit_again_after_the_refill_period() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      clock.advance(Duration.ofSeconds(1));
      Mono<String> result = limiter.execute(Mono.just("recovered"));

      // Then
      StepVerifier.create(result)
          .expectNext("recovered")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono Fallback
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Fallback")
  class MonoFallback {

    @Test
    @DisplayName("should return the primary value when permits are available")
    void should_return_the_primary_value_when_permits_are_available() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When
      Mono<String> result = limiter.executeWithFallback(
          Mono.just("primary"),
          e -> Mono.just("fallback")
      );

      // Then
      StepVerifier.create(result)
          .expectNext("primary")
          .verifyComplete();
    }

    @Test
    @DisplayName("should return the fallback value when rate-limited")
    void should_return_the_fallback_value_when_rate_limited() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      Mono<String> result = limiter.executeWithFallback(
          Mono.just("primary"),
          e -> Mono.just("fallback")
      );

      // Then
      StepVerifier.create(result)
          .expectNext("fallback")
          .verifyComplete();
    }

    @Test
    @DisplayName("should still propagate non-rate-limiter errors through the fallback")
    void should_still_propagate_non_rate_limiter_errors_through_the_fallback() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When — the upstream error is not a RateLimiterException
      Mono<String> result = limiter.executeWithFallback(
          Mono.error(new IllegalArgumentException("bad")),
          e -> Mono.just("fallback")
      );

      // Then
      StepVerifier.create(result)
          .expectError(IllegalArgumentException.class)
          .verify();
    }
  }

  // ================================================================
  // Non-Blocking Wait (Mono.delay)
  // ================================================================

  @Nested
  @DisplayName("Non-Blocking Wait Mode")
  class NonBlockingWait {

    @Test
    @DisplayName("should execute immediately when permits are available via executeWithWait")
    void should_execute_immediately_when_permits_are_available_via_execute_with_wait() {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("wait-test")
          .capacity(5)
          .refillPermits(5)
          .refillPeriod(Duration.ofSeconds(1))
          .defaultTimeout(Duration.ofSeconds(5))
          .build();
      ReactiveRateLimiter limiter = createLimiter(config);

      // When
      Mono<String> result = limiter.executeWithWait(() -> Mono.just("immediate"));

      // Then
      StepVerifier.create(result)
          .expectNext("immediate")
          .verifyComplete();
    }

    @Test
    @DisplayName("should emit RateLimiterException when the wait would exceed the timeout")
    void should_emit_rate_limiter_exception_when_the_wait_would_exceed_the_timeout() {
      // Given — timeout 100ms, refill period 1s
      RateLimiterConfig config = RateLimiterConfig.builder("timeout-wait")
          .capacity(1)
          .refillPermits(1)
          .refillPeriod(Duration.ofSeconds(1))
          .defaultTimeout(Duration.ofMillis(100))
          .build();
      ReactiveRateLimiter limiter = createLimiter(config);
      exhaustPermits(limiter, 1);

      // When
      Mono<String> result = limiter.executeWithWait(() -> Mono.just("should fail"));

      // Then
      StepVerifier.create(result)
          .expectError(RateLimiterException.class)
          .verify();
    }
  }

  // ================================================================
  // Flux Execution
  // ================================================================

  @Nested
  @DisplayName("Flux Execution")
  class FluxExecution {

    @Test
    @DisplayName("should emit all upstream elements when a permit is available")
    void should_emit_all_upstream_elements_when_a_permit_is_available() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When
      Flux<Integer> result = limiter.executeMany(Flux.just(1, 2, 3));

      // Then
      StepVerifier.create(result)
          .expectNext(1, 2, 3)
          .verifyComplete();
    }

    @Test
    @DisplayName("should emit RateLimiterException for flux when the bucket is exhausted")
    void should_emit_rate_limiter_exception_for_flux_when_the_bucket_is_exhausted() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      Flux<String> result = limiter.executeMany(Flux.just("a", "b"));

      // Then
      StepVerifier.create(result)
          .expectError(RateLimiterException.class)
          .verify();
    }

    @Test
    @DisplayName("should use fallback publisher when rate-limited for flux")
    void should_use_fallback_publisher_when_rate_limited_for_flux() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      Flux<String> result = limiter.executeManyWithFallback(
          Flux.just("primary"),
          e -> Flux.just("fallback-1", "fallback-2")
      );

      // Then
      StepVerifier.create(result)
          .expectNext("fallback-1", "fallback-2")
          .verifyComplete();
    }
  }

  // ================================================================
  // Operator Style (transformDeferred)
  // ================================================================

  @Nested
  @DisplayName("Operator Style Integration")
  class OperatorStyle {

    @Test
    @DisplayName("should work as a mono operator via transformDeferred")
    void should_work_as_a_mono_operator_via_transform_deferred() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When
      Mono<String> result = Mono.just("transformed")
          .transformDeferred(limiter.monoOperator());

      // Then
      StepVerifier.create(result)
          .expectNext("transformed")
          .verifyComplete();
    }

    @Test
    @DisplayName("should work as a flux operator via transformDeferred")
    void should_work_as_a_flux_operator_via_transform_deferred() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();

      // When
      Flux<Integer> result = Flux.range(1, 3)
          .transformDeferred(limiter.fluxOperator());

      // Then
      StepVerifier.create(result)
          .expectNext(1, 2, 3)
          .verifyComplete();
    }

    @Test
    @DisplayName("should rate-limit when used as an operator with an exhausted bucket")
    void should_rate_limit_when_used_as_an_operator_with_an_exhausted_bucket() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When
      Mono<String> result = Mono.just("nope")
          .transformDeferred(limiter.monoOperator());

      // Then
      StepVerifier.create(result)
          .expectError(RateLimiterException.class)
          .verify();
    }
  }

  // ================================================================
  // Event Stream
  // ================================================================

  @Nested
  @DisplayName("Event Stream")
  class EventStream {

    @Test
    @DisplayName("should emit PERMITTED events via the reactive event stream")
    void should_emit_permitted_events_via_the_reactive_event_stream() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.events().subscribe(events::add);

      // When
      limiter.execute(Mono.just("ok")).block();

      // Then
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().type()).isEqualTo(RateLimiterEvent.Type.PERMITTED);
    }

    @Test
    @DisplayName("should emit REJECTED events via the reactive event stream when rate-limited")
    void should_emit_rejected_events_via_the_reactive_event_stream_when_rate_limited() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.events().subscribe(events::add);
      exhaustPermits(limiter, 5);
      events.clear();

      // When
      limiter.execute(Mono.just("fail"))
          .onErrorResume(e -> Mono.empty())
          .block();

      // Then
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().type()).isEqualTo(RateLimiterEvent.Type.REJECTED);
    }

    @Test
    @DisplayName("should emit events for a complete permit-exhaust-refill-permit cycle")
    void should_emit_events_for_a_complete_permit_exhaust_refill_permit_cycle() {
      // Given
      RateLimiterConfig config = RateLimiterConfig.builder("event-cycle")
          .capacity(1)
          .refillPermits(1)
          .refillPeriod(Duration.ofSeconds(1))
          .defaultTimeout(Duration.ZERO)
          .build();
      ReactiveRateLimiter limiter = createLimiter(config);
      List<RateLimiterEvent> events = new ArrayList<>();
      limiter.events().subscribe(events::add);

      // When — permit, reject, refill, permit
      limiter.execute(Mono.just("ok")).block();
      limiter.execute(Mono.just("fail")).onErrorResume(e -> Mono.empty()).block();
      clock.advance(Duration.ofSeconds(1));
      limiter.execute(Mono.just("recovered")).block();

      // Then
      assertThat(events).hasSize(3);
      assertThat(events.get(0).type()).isEqualTo(RateLimiterEvent.Type.PERMITTED);
      assertThat(events.get(1).type()).isEqualTo(RateLimiterEvent.Type.REJECTED);
      assertThat(events.get(2).type()).isEqualTo(RateLimiterEvent.Type.PERMITTED);
    }
  }

  // ================================================================
  // Deferred Subscription Semantics
  // ================================================================

  @Nested
  @DisplayName("Deferred Subscription Semantics")
  class DeferredSubscription {

    @Test
    @DisplayName("should not acquire a permit until the mono is subscribed")
    void should_not_acquire_a_permit_until_the_mono_is_subscribed() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);

      // When — create the mono but do not subscribe
      Mono<String> result = limiter.execute(Mono.just("deferred"));

      // Then — no permit consumed yet for this mono
      assertThat(limiter.getAvailablePermits()).isZero();

      // When — advance time and then subscribe
      clock.advance(Duration.ofSeconds(1));
      StepVerifier.create(result)
          .expectNext("deferred")
          .verifyComplete();
    }
  }

  // ================================================================
  // Drain & Reset
  // ================================================================

  @Nested
  @DisplayName("Drain and Reset")
  class DrainAndReset {

    @Test
    @DisplayName("should reject all calls after draining")
    void should_reject_all_calls_after_draining() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      limiter.drain();

      // When
      Mono<String> result = limiter.execute(Mono.just("should fail"));

      // Then
      StepVerifier.create(result)
          .expectError(RateLimiterException.class)
          .verify();
    }

    @Test
    @DisplayName("should restore full capacity after resetting")
    void should_restore_full_capacity_after_resetting() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 5);
      limiter.reset();

      // When
      Mono<String> result = limiter.execute(Mono.just("after-reset"));

      // Then
      StepVerifier.create(result)
          .expectNext("after-reset")
          .verifyComplete();
      assertThat(limiter.getAvailablePermits()).isEqualTo(4);
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("should return the correct available permits count including refill")
    void should_return_the_correct_available_permits_count_including_refill() {
      // Given
      ReactiveRateLimiter limiter = createLimiter();
      exhaustPermits(limiter, 3);

      // When / Then
      assertThat(limiter.getAvailablePermits()).isEqualTo(2);

      // When — advance time
      clock.advance(Duration.ofSeconds(1));

      // Then — should be refilled to capacity
      assertThat(limiter.getAvailablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      RateLimiterConfig config = defaultConfig();
      ReactiveRateLimiter limiter = createLimiter(config);

      // Then
      assertThat(limiter.getConfig()).isEqualTo(config);
    }
  }
}
