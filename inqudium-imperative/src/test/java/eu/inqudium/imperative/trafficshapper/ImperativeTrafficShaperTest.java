package eu.inqudium.imperative.trafficshapper;

import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;
import eu.inqudium.core.trafficshaper.TrafficShaperEvent;
import eu.inqudium.core.trafficshaper.TrafficShaperException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImperativeTrafficShaper")
class ImperativeTrafficShaperTest {

  private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
  private TestClock clock;

  @BeforeEach
  void setUp() {
    clock = new TestClock(BASE_TIME);
  }

  private TrafficShaperConfig defaultConfig() {
    return TrafficShaperConfig.builder("test-shaper")
        .ratePerSecond(10) // 100ms interval
        .maxQueueDepth(5)
        .maxWaitDuration(Duration.ofSeconds(2))
        .build();
  }

  private ImperativeTrafficShaper createShaper() {
    return new ImperativeTrafficShaper(defaultConfig(), clock);
  }

  private ImperativeTrafficShaper createShaper(TrafficShaperConfig config) {
    return new ImperativeTrafficShaper(config, clock);
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
  // Successful Execution
  // ================================================================

  @Nested
  @DisplayName("Successful Execution")
  class SuccessfulExecution {

    @Test
    @DisplayName("should return the result of a callable that is admitted immediately")
    void should_return_the_result_of_a_callable_that_is_admitted_immediately() throws Exception {
      // Given
      ImperativeTrafficShaper shaper = createShaper();

      // When
      String result = shaper.execute(() -> "hello");

      // Then
      assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("should execute a runnable without throwing when admitted")
    void should_execute_a_runnable_without_throwing_when_admitted() throws Exception {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      AtomicInteger counter = new AtomicInteger(0);

      // When
      shaper.execute(counter::incrementAndGet);

      // Then
      assertThat(counter.get()).isEqualTo(1);
    }
  }

  // ================================================================
  // Traffic Shaping (Delay)
  // ================================================================

  @Nested
  @DisplayName("Traffic Shaping — Delay Behavior")
  class TrafficShapingDelay {

    @Test
    @DisplayName("should shape a burst by assigning increasing wait slots")
    void should_shape_a_burst_by_assigning_increasing_wait_slots() {
      // Given — use tryAcquireSlot to inspect without blocking
      ImperativeTrafficShaper shaper = createShaper(); // 100ms interval

      // When — burst of 4 requests
      ThrottlePermission p1 = shaper.tryAcquireSlot();
      ThrottlePermission p2 = shaper.tryAcquireSlot();
      ThrottlePermission p3 = shaper.tryAcquireSlot();
      ThrottlePermission p4 = shaper.tryAcquireSlot();

      // Then — evenly spaced: 0, 100ms, 200ms, 300ms
      assertThat(p1.waitDuration()).isEqualTo(Duration.ZERO);
      assertThat(p2.waitDuration()).isEqualTo(Duration.ofMillis(100));
      assertThat(p3.waitDuration()).isEqualTo(Duration.ofMillis(200));
      assertThat(p4.waitDuration()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("should shape traffic with real timing using a fast rate")
    void should_shape_traffic_with_real_timing_using_a_fast_rate() throws Exception {
      // Given — use real clock, 100 req/s (10ms interval)
      TrafficShaperConfig config = TrafficShaperConfig.builder("timing-test")
          .ratePerSecond(100)
          .maxQueueDepth(10)
          .maxWaitDuration(Duration.ofSeconds(1))
          .build();
      ImperativeTrafficShaper shaper = new ImperativeTrafficShaper(config);
      long start = System.nanoTime();

      // When — 5 requests in rapid succession
      for (int i = 0; i < 5; i++) {
        shaper.execute(() -> "ok");
      }
      long elapsed = (System.nanoTime() - start) / 1_000_000;

      // Then — should take at least ~40ms (4 intervals of ~10ms)
      assertThat(elapsed).isGreaterThanOrEqualTo(30); // with tolerance
    }
  }

  // ================================================================
  // Overflow Rejection
  // ================================================================

  @Nested
  @DisplayName("Overflow Rejection")
  class OverflowRejection {

    @Test
    @DisplayName("should throw TrafficShaperException when the queue is full")
    void should_throw_traffic_shaper_exception_when_the_queue_is_full() {
      // Given — maxQueueDepth=5, acquire 5 slots
      ImperativeTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.tryAcquireSlot();
      }

      // When / Then
      assertThatThrownBy(() -> shaper.execute(() -> "overflow"))
          .isInstanceOf(TrafficShaperException.class)
          .hasMessageContaining("test-shaper");
    }

    @Test
    @DisplayName("should include the queue depth and would-have-waited in the exception")
    void should_include_the_queue_depth_and_would_have_waited_in_the_exception() {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.tryAcquireSlot();
      }

      // When / Then
      assertThatThrownBy(() -> shaper.execute(() -> "overflow"))
          .isInstanceOf(TrafficShaperException.class)
          .satisfies(e -> {
            TrafficShaperException tse = (TrafficShaperException) e;
            assertThat(tse.getQueueDepth()).isEqualTo(5);
            assertThat(tse.getWouldHaveWaited()).isPositive();
          });
    }
  }

  // ================================================================
  // Fallback
  // ================================================================

  @Nested
  @DisplayName("Fallback Execution")
  class FallbackExecution {

    @Test
    @DisplayName("should return the primary result when admitted")
    void should_return_the_primary_result_when_admitted() throws Exception {
      // Given
      ImperativeTrafficShaper shaper = createShaper();

      // When
      String result = shaper.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("primary");
    }

    @Test
    @DisplayName("should return the fallback value on overflow rejection")
    void should_return_the_fallback_value_on_overflow_rejection() throws Exception {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.tryAcquireSlot();
      }

      // When
      String result = shaper.executeWithFallback(
          () -> "primary",
          () -> "fallback"
      );

      // Then
      assertThat(result).isEqualTo("fallback");
    }
  }

  // ================================================================
  // Event Listeners
  // ================================================================

  @Nested
  @DisplayName("Event Listeners")
  class EventListeners {

    @Test
    @DisplayName("should emit ADMITTED_IMMEDIATE event for a request that proceeds without delay")
    void should_emit_admitted_immediate_event_for_a_request_that_proceeds_without_delay() throws Exception {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.onEvent(events::add);

      // When
      shaper.execute(() -> "ok");

      // Then — ADMITTED_IMMEDIATE + EXECUTING
      assertThat(events).extracting(TrafficShaperEvent::type)
          .contains(TrafficShaperEvent.Type.ADMITTED_IMMEDIATE,
              TrafficShaperEvent.Type.EXECUTING);
    }

    @Test
    @DisplayName("should emit ADMITTED_DELAYED event for a request that must wait")
    void should_emit_admitted_delayed_event_for_a_request_that_must_wait() {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.onEvent(events::add);

      // When — first is immediate, second must wait
      shaper.tryAcquireSlot();
      shaper.tryAcquireSlot();

      // Then
      assertThat(events).extracting(TrafficShaperEvent::type)
          .contains(TrafficShaperEvent.Type.ADMITTED_DELAYED);
    }

    @Test
    @DisplayName("should emit REJECTED event when overflow occurs")
    void should_emit_rejected_event_when_overflow_occurs() {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.onEvent(events::add);

      // Fill the queue
      for (int i = 0; i < 5; i++) {
        shaper.tryAcquireSlot();
      }
      events.clear();

      // When
      try {
        shaper.execute(() -> "overflow");
      } catch (Exception e) {
      }

      // Then
      assertThat(events).extracting(TrafficShaperEvent::type)
          .contains(TrafficShaperEvent.Type.REJECTED);
    }

    @Test
    @DisplayName("should emit RESET event when resetting")
    void should_emit_reset_event_when_resetting() {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      List<TrafficShaperEvent> events = new ArrayList<>();
      shaper.onEvent(events::add);

      // When
      shaper.reset();

      // Then
      assertThat(events).extracting(TrafficShaperEvent::type)
          .containsExactly(TrafficShaperEvent.Type.RESET);
    }
  }

  // ================================================================
  // Reset
  // ================================================================

  @Nested
  @DisplayName("Reset")
  class Reset {

    @Test
    @DisplayName("should allow requests again after resetting from a full queue")
    void should_allow_requests_again_after_resetting_from_a_full_queue() throws Exception {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      for (int i = 0; i < 5; i++) {
        shaper.tryAcquireSlot();
      }

      // When
      shaper.reset();

      // Then — queue depth is 0 because waitForSlot() dequeues before the callable runs
      assertThat(shaper.execute(() -> "after-reset")).isEqualTo("after-reset");
      assertThat(shaper.getQueueDepth()).isZero();
    }
  }

  // ================================================================
  // Concurrency with Virtual Threads
  // ================================================================

  @Nested
  @DisplayName("Concurrency with Virtual Threads")
  class ConcurrencyWithVirtualThreads {

    @Test
    @DisplayName("should shape concurrent requests safely with virtual threads")
    void should_shape_concurrent_requests_safely_with_virtual_threads() throws Exception {
      // Given — 50 req/s, maxQueue=100, 80 concurrent requests via real clock
      TrafficShaperConfig config = TrafficShaperConfig.builder("concurrent")
          .ratePerSecond(200) // 5ms interval
          .maxQueueDepth(100)
          .maxWaitDuration(Duration.ofSeconds(5))
          .build();
      ImperativeTrafficShaper shaper = new ImperativeTrafficShaper(config);
      int threadCount = 80;
      AtomicInteger admitted = new AtomicInteger(0);
      AtomicInteger rejected = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(threadCount);

      // When
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              shaper.execute(() -> {
                admitted.incrementAndGet();
                return "ok";
              });
            } catch (TrafficShaperException e) {
              rejected.incrementAndGet();
            } catch (Exception e) {
            } finally {
              latch.countDown();
            }
          });
        }
        latch.await(15, TimeUnit.SECONDS);
      }

      // Then — all requests should have been processed (admitted + rejected = total)
      assertThat(admitted.get() + rejected.get()).isEqualTo(threadCount);
      // Most should have been admitted given the generous config
      assertThat(admitted.get()).isGreaterThan(0);
    }
  }

  // ================================================================
  // Introspection
  // ================================================================

  @Nested
  @DisplayName("Introspection")
  class Introspection {

    @Test
    @DisplayName("should return the current queue depth")
    void should_return_the_current_queue_depth() {
      // Given
      ImperativeTrafficShaper shaper = createShaper();
      shaper.tryAcquireSlot();
      shaper.tryAcquireSlot();

      // When / Then
      assertThat(shaper.getQueueDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("should return the estimated wait for the next request")
    void should_return_the_estimated_wait_for_the_next_request() {
      // Given
      ImperativeTrafficShaper shaper = createShaper(); // 100ms interval
      shaper.tryAcquireSlot();
      shaper.tryAcquireSlot();

      // When
      Duration wait = shaper.getEstimatedWait();

      // Then — should be around 200ms
      assertThat(wait).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("should return the configuration")
    void should_return_the_configuration() {
      // Given
      TrafficShaperConfig config = defaultConfig();
      ImperativeTrafficShaper shaper = createShaper(config);

      // When / Then
      assertThat(shaper.getConfig()).isEqualTo(config);
    }
  }
}
