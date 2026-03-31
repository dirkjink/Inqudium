package eu.inqudium.reactor.trafficshaper;

import eu.inqudium.core.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.trafficshaper.TrafficShaperException;
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

@DisplayName("ReactiveTrafficShaper")
class ReactiveTrafficShaperTest {

  private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
  private TestClock clock;

  @BeforeEach
  void setUp() {
    clock = new TestClock(BASE_TIME);
  }

  private TrafficShaperConfig defaultConfig() {
    return TrafficShaperConfig.builder("reactive-shaper")
        .ratePerSecond(10) // 100ms interval
        .maxQueueDepth(5)
        .maxWaitDuration(Duration.ofSeconds(2))
        .build();
  }

  private ReactiveTrafficShaper createShaper() {
    return new ReactiveTrafficShaper(defaultConfig(), clock);
  }

  private ReactiveTrafficShaper createShaper(TrafficShaperConfig config) {
    return new ReactiveTrafficShaper(config, clock);
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
  // Mono Execution — Immediate
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Immediate Admission")
  class MonoImmediate {

    @Test
    @DisplayName("should emit the upstream value immediately for the first request")
    void should_emit_the_upstream_value_immediately_for_the_first_request() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();

      // When
      Mono<String> result = shaper.execute(Mono.just("hello"));

      // Then
      StepVerifier.create(result)
          .expectNext("hello")
          .verifyComplete();
    }

    @Test
    @DisplayName("should support lazy mono creation via supplier")
    void should_support_lazy_mono_creation_via_supplier() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();

      // When
      Mono<String> result = shaper.execute(() -> Mono.just("lazy"));

      // Then
      StepVerifier.create(result)
          .expectNext("lazy")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono Execution — Delayed (Real Timing)
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Delayed Admission")
  class MonoDelayed {

    @Test
    @DisplayName("should delay the second request by the configured interval using real timing")
    void should_delay_the_second_request_by_the_configured_interval_using_real_timing() {
      // Given — use real clock, 100 req/s (10ms interval)
      TrafficShaperConfig config = TrafficShaperConfig.builder("timing")
          .ratePerSecond(100) // 10ms interval
          .maxQueueDepth(10)
          .maxWaitDuration(Duration.ofSeconds(1))
          .build();
      ReactiveTrafficShaper shaper = new ReactiveTrafficShaper(config);

      // When — two requests back to back
      Mono<String> first = shaper.execute(Mono.just("first"));
      Mono<String> second = shaper.execute(Mono.just("second"));

      // Then — both succeed (second delayed by ~10ms)
      StepVerifier.create(first)
          .expectNext("first")
          .verifyComplete();
      StepVerifier.create(second)
          .expectNext("second")
          .verifyComplete();
    }
  }

  // ================================================================
  // Mono Execution — Overflow Rejection
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Overflow Rejection")
  class MonoOverflow {

    @Test
    @DisplayName("should emit TrafficShaperException when the queue is full")
    void should_emit_traffic_shaper_exception_when_the_queue_is_full() {
      // Given — fill the queue with 5 never-completing monos so they stay queued
      ReactiveTrafficShaper shaper = createShaper(); // maxQueueDepth=5
      for (int i = 0; i < 5; i++) {
        // Mono.never() ensures doFinally (dequeue) never fires
        shaper.execute(Mono.<String>never()).subscribe();
      }

      // When — 6th request
      Mono<String> overflow = shaper.execute(Mono.just("overflow"));

      // Then
      StepVerifier.create(overflow)
          .expectError(TrafficShaperException.class)
          .verify();
    }

    @Test
    @DisplayName("should include the queue depth in the exception")
    void should_include_the_queue_depth_in_the_exception() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.execute(Mono.<String>never()).subscribe();
      }

      // When
      Mono<String> overflow = shaper.execute(Mono.just("overflow"));

      // Then
      StepVerifier.create(overflow)
          .expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(TrafficShaperException.class);
            TrafficShaperException tse = (TrafficShaperException) error;
            assertThat(tse.getQueueDepth()).isGreaterThanOrEqualTo(5);
          })
          .verify();
    }
  }

  // ================================================================
  // Mono Fallback
  // ================================================================

  @Nested
  @DisplayName("Mono Execution — Fallback")
  class MonoFallback {

    @Test
    @DisplayName("should return the primary value when admitted")
    void should_return_the_primary_value_when_admitted() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();

      // When
      Mono<String> result = shaper.executeWithFallback(
          Mono.just("primary"),
          e -> Mono.just("fallback")
      );

      // Then
      StepVerifier.create(result)
          .expectNext("primary")
          .verifyComplete();
    }

    @Test
    @DisplayName("should return the fallback value on overflow")
    void should_return_the_fallback_value_on_overflow() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.execute(Mono.<String>never()).subscribe();
      }

      // When
      Mono<String> result = shaper.executeWithFallback(
          Mono.just("overflow"),
          e -> Mono.just("fallback")
      );

      // Then
      StepVerifier.create(result)
          .expectNext("fallback")
          .verifyComplete();
    }
  }

  // ================================================================
  // Flux Execution
  // ================================================================

  @Nested
  @DisplayName("Flux Execution")
  class FluxExecution {

    @Test
    @DisplayName("should emit all upstream elements when admitted")
    void should_emit_all_upstream_elements_when_admitted() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();

      // When
      Flux<Integer> result = shaper.executeMany(Flux.just(1, 2, 3));

      // Then
      StepVerifier.create(result)
          .expectNext(1, 2, 3)
          .verifyComplete();
    }

    @Test
    @DisplayName("should emit TrafficShaperException for flux when the queue is full")
    void should_emit_traffic_shaper_exception_for_flux_when_the_queue_is_full() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.execute(Mono.<String>never()).subscribe();
      }

      // When
      Flux<String> result = shaper.executeMany(Flux.just("a", "b"));

      // Then
      StepVerifier.create(result)
          .expectError(TrafficShaperException.class)
          .verify();
    }

    @Test
    @DisplayName("should use fallback publisher on flux overflow")
    void should_use_fallback_publisher_on_flux_overflow() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.execute(Mono.<String>never()).subscribe();
      }

      // When
      Flux<String> result = shaper.executeManyWithFallback(
          Flux.just("overflow"),
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
      ReactiveTrafficShaper shaper = createShaper();

      // When
      Mono<String> result = Mono.just("transformed")
          .transformDeferred(shaper.monoOperator());

      // Then
      StepVerifier.create(result)
          .expectNext("transformed")
          .verifyComplete();
    }

    @Test
    @DisplayName("should work as a flux operator via transformDeferred")
    void should_work_as_a_flux_operator_via_transform_deferred() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();

      // When
      Flux<Integer> result = Flux.range(1, 3)
          .transformDeferred(shaper.fluxOperator());

      // Then
      StepVerifier.create(result)
          .expectNext(1, 2, 3)
          .verifyComplete();
    }
  }

  // ================================================================
  // Event Stream
  // ================================================================

  @Nested
  @DisplayName("Event Stream")
  class EventStream {

    @Test
    @DisplayName("should emit ADMITTED_IMMEDIATE event for the first request")
    void should_emit_admitted_immediate_event_for_the_first_request() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.events().subscribe(events::add);

      // When
      shaper.execute(Mono.just("ok")).block();

      // Then
      assertThat(events).extracting(TrafficShaperEvent::type)
          .contains(TrafficShaperEvent.Type.ADMITTED_IMMEDIATE);
    }

    @Test
    @DisplayName("should emit REJECTED event when overflow occurs")
    void should_emit_rejected_event_when_overflow_occurs() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.events().subscribe(events::add);
      for (int i = 0; i < 5; i++) {
        shaper.execute(Mono.<String>never()).subscribe();
      }
      events.clear();

      // When
      shaper.execute(Mono.just("overflow"))
          .onErrorResume(e -> Mono.empty())
          .block();

      // Then
      assertThat(events).extracting(TrafficShaperEvent::type)
          .contains(TrafficShaperEvent.Type.REJECTED);
    }
  }

  // ================================================================
  // Deferred Subscription Semantics
  // ================================================================

  @Nested
  @DisplayName("Deferred Subscription Semantics")
  class DeferredSubscription {

    @Test
    @DisplayName("should not acquire a slot until the mono is subscribed")
    void should_not_acquire_a_slot_until_the_mono_is_subscribed() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();

      // When — create but don't subscribe
      Mono<String> result = shaper.execute(Mono.just("deferred"));

      // Then — no slot acquired yet
      assertThat(shaper.getQueueDepth()).isZero();

      // When — subscribe
      StepVerifier.create(result)
          .expectNext("deferred")
          .verifyComplete();
    }
  }

  // ================================================================
  // Reset
  // ================================================================

  @Nested
  @DisplayName("Reset")
  class Reset {

    @Test
    @DisplayName("should accept requests again after resetting from a full queue")
    void should_accept_requests_again_after_resetting_from_a_full_queue() {
      // Given
      ReactiveTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.execute(Mono.<String>never()).subscribe();
      }

      // When
      shaper.reset();

      // Then
      StepVerifier.create(shaper.execute(Mono.just("after-reset")))
          .expectNext("after-reset")
          .verifyComplete();
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      TrafficShaperConfig config = defaultConfig();
      ReactiveTrafficShaper shaper = createShaper(config);

      // Then
      assertThat(shaper.getConfig()).isEqualTo(config);
      assertThat(shaper.getConfig().ratePerSecond()).isEqualTo(10.0);
    }
  }
}
